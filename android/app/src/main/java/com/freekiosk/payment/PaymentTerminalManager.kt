package com.freekiosk.payment

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.freekiosk.DebugLog
import org.json.JSONObject
import java.util.UUID

/**
 * Receives the events of one session. The React Native bridge is one sink; a bound service
 * for external apps would be another.
 */
fun interface PaymentEventSink {
    fun onEvent(sessionId: String, type: String, payload: JSONObject)
}

/**
 * Owns the one payment SDK instance on the device and decides who may drive it.
 *
 * - One session at a time. The terminal is a single physical resource and the Stripe
 *   `Terminal` is itself a process-wide singleton, so a second caller gets BUSY rather than
 *   interleaving its calls with the first. The same owner opening again takes over, which is
 *   what a reloaded page does.
 * - The provider outlives sessions: a connected reader stays connected across page reloads.
 *   Closing a session cancels only the operations that session started.
 * - SDK credentials are asked of the session owner through [PaymentEvents.TOKEN_REQUEST] and
 *   answered with [provideToken]. Nothing here ever sees the secret key that mints them.
 * - [isTransactionActive] tells the watchdog, lock task re-entry and remote screenshots to stand
 *   back while the SDK owns the screen, the same way PrintModule.isPrintActive does for printing.
 *
 * Independent of React Native on purpose, so a non-RN transport can reuse it unchanged.
 */
object PaymentTerminalManager {

    private const val TAG = "PaymentTerminal"

    /** How long the owner has to answer a token request before the SDK call fails. */
    private const val TOKEN_TIMEOUT_MS = 30_000L

    /**
     * A transaction that never reports an end would otherwise pin the kiosk guards off for
     * ever. Tap to Pay with PIN entry and a slow customer fits well inside this.
     */
    private const val TRANSACTION_MAX_LIFETIME_MS = 5 * 60_000L

    private class Session(val id: String, val owner: String, val sink: PaymentEventSink) {
        val operations = mutableSetOf<String>()
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var provider: PaymentTerminalProvider? = null
    private var session: Session? = null
    private val pendingTokens = mutableMapOf<String, Pair<TokenCallback, Runnable>>()

    @Volatile
    private var transactionStartedAt: Long = 0L

    @Volatile
    private var overlaysSuspended = false

    /**
     * True while the SDK is collecting a payment. Read by MainActivity, KioskWatchdogService,
     * OverlayService and the remote screenshot paths.
     */
    @JvmStatic
    val isTransactionActive: Boolean
        get() {
            val started = transactionStartedAt
            if (started == 0L) return false
            if (System.currentTimeMillis() - started > TRANSACTION_MAX_LIFETIME_MS) {
                transactionStartedAt = 0L
                if (overlaysSuspended) {
                    overlaysSuspended = false
                    PaymentKioskGuards.setOverlaysSuspended(appContext, false)
                }
                return false
            }
            return true
        }

    fun attach(context: Context) {
        synchronized(lock) {
            if (appContext == null) appContext = context.applicationContext
        }
    }

    /** Providers compiled into this build. Empty unless built with -Ppayments. */
    fun availableProviders(): List<String> = PaymentProviders.available

    fun info(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("supported", PaymentProviders.available.isNotEmpty())
            .put("providers", org.json.JSONArray(PaymentProviders.available))
            .put("activeProvider", provider?.id ?: JSONObject.NULL)
            .put("sessionOpen", session != null)
            .put("transactionActive", isTransactionActive)
    }

    // ==================== Sessions ====================

    fun openSession(owner: String, sink: PaymentEventSink): String {
        val closed: Session?
        val opened: Session
        synchronized(lock) {
            val current = session
            if (current != null && current.owner != owner) {
                throw PaymentException(PaymentErrorCodes.BUSY, "The payment terminal is in use by another client")
            }
            closed = current
            opened = Session(UUID.randomUUID().toString(), owner, sink)
            session = opened
        }
        closed?.let { releaseSession(it, "replaced") }
        DebugLog.d(TAG, "Session opened for $owner")
        return opened.id
    }

    fun closeSession(sessionId: String) {
        val closed = synchronized(lock) {
            val current = session ?: return
            if (current.id != sessionId) return
            session = null
            current
        }
        releaseSession(closed, "closed")
    }

    /** Close whatever session [owner] holds, e.g. when its page navigates away or unmounts. */
    fun closeSessionsOwnedBy(owner: String, reason: String) {
        val closed = synchronized(lock) {
            val current = session ?: return
            if (current.owner != owner) return
            session = null
            current
        }
        closed.sink.onEvent(closed.id, PaymentEvents.SESSION_CLOSED, JSONObject().put("reason", reason))
        releaseSession(closed, reason)
    }

    private fun releaseSession(closed: Session, reason: String) {
        val ops = synchronized(lock) { closed.operations.toList().also { closed.operations.clear() } }
        val p = provider
        for (opId in ops) {
            p?.cancel(opId, object : PaymentCallback {
                override fun onSuccess(result: JSONObject) {}
                override fun onError(error: PaymentError) {}
            })
        }
        failPendingTokens(PaymentError(PaymentErrorCodes.NO_SESSION, "Session $reason"))
        DebugLog.d(TAG, "Session released ($reason), cancelled ${ops.size} operation(s)")
    }

    // ==================== Calls (all require the current session) ====================

    fun initialize(sessionId: String, providerId: String, config: JSONObject, callback: PaymentCallback) {
        val context = synchronized(lock) {
            requireSession(sessionId)
            appContext
        } ?: return callback.onError(PaymentError(PaymentErrorCodes.NOT_INITIALIZED, "Payment bridge not attached"))

        val p = synchronized(lock) {
            val existing = provider
            when {
                existing == null -> PaymentProviders.create(providerId)?.also { provider = it }
                existing.id == providerId -> existing
                else -> throw PaymentException(
                    PaymentErrorCodes.BUSY,
                    "Provider ${existing.id} is already initialised; restart the app to switch provider",
                )
            }
        } ?: return callback.onError(
            PaymentError(PaymentErrorCodes.UNSUPPORTED, "Provider '$providerId' is not included in this build")
        )

        p.initialize(context, config, host, callback)
    }

    fun discoverReaders(sessionId: String, opId: String, options: JSONObject, callback: PaymentCallback) {
        val p = providerFor(sessionId, opId)
        p.discoverReaders(opId, options, finishing(sessionId, opId, callback))
    }

    fun connectReader(sessionId: String, readerId: String, options: JSONObject, callback: PaymentCallback) {
        providerFor(sessionId).connectReader(readerId, options, callback)
    }

    fun disconnectReader(sessionId: String, callback: PaymentCallback) {
        providerFor(sessionId).disconnectReader(callback)
    }

    fun collectPayment(sessionId: String, opId: String, request: JSONObject, callback: PaymentCallback) {
        val p = providerFor(sessionId, opId)
        p.collectPayment(opId, request, finishing(sessionId, opId, callback))
    }

    fun cancel(sessionId: String, opId: String, callback: PaymentCallback) {
        providerFor(sessionId).cancel(opId, callback)
    }

    fun setReaderDisplay(sessionId: String, cart: JSONObject, callback: PaymentCallback) {
        providerFor(sessionId).setReaderDisplay(cart, callback)
    }

    fun clearReaderDisplay(sessionId: String, callback: PaymentCallback) {
        providerFor(sessionId).clearReaderDisplay(callback)
    }

    fun installReaderUpdate(sessionId: String, callback: PaymentCallback) {
        providerFor(sessionId).installReaderUpdate(callback)
    }

    fun getStatus(sessionId: String): JSONObject = providerFor(sessionId).getStatus()

    fun invoke(sessionId: String, opId: String, method: String, args: JSONObject, callback: PaymentCallback) {
        val p = providerFor(sessionId, opId)
        p.invoke(opId, method, args, finishing(sessionId, opId, callback))
    }

    // ==================== Tokens ====================

    fun provideToken(sessionId: String, requestId: String, token: String) {
        synchronized(lock) { requireSession(sessionId) }
        val entry = synchronized(lock) { pendingTokens.remove(requestId) } ?: return
        mainHandler.removeCallbacks(entry.second)
        entry.first.onToken(token)
    }

    fun failTokenRequest(sessionId: String, requestId: String, message: String) {
        synchronized(lock) { requireSession(sessionId) }
        val entry = synchronized(lock) { pendingTokens.remove(requestId) } ?: return
        mainHandler.removeCallbacks(entry.second)
        entry.first.onError(PaymentError(PaymentErrorCodes.TOKEN_FAILED, message))
    }

    private fun failPendingTokens(error: PaymentError) {
        val entries = synchronized(lock) { pendingTokens.values.toList().also { pendingTokens.clear() } }
        for ((callback, timeout) in entries) {
            mainHandler.removeCallbacks(timeout)
            callback.onError(error)
        }
    }

    // ==================== Internals ====================

    private val host = object : ProviderHost {
        override fun emit(type: String, payload: JSONObject) {
            val current = synchronized(lock) { session } ?: return
            current.sink.onEvent(current.id, type, payload)
        }

        override fun requestToken(kind: String, request: JSONObject, callback: TokenCallback) {
            val current = synchronized(lock) { session }
            if (current == null) {
                callback.onError(PaymentError(PaymentErrorCodes.NO_SESSION, "No client is attached to supply a token"))
                return
            }
            val requestId = UUID.randomUUID().toString()
            val timeout = Runnable {
                val expired = synchronized(lock) { pendingTokens.remove(requestId) }
                expired?.first?.onError(PaymentError(PaymentErrorCodes.TOKEN_TIMEOUT, "No token supplied in time"))
            }
            synchronized(lock) { pendingTokens[requestId] = callback to timeout }
            mainHandler.postDelayed(timeout, TOKEN_TIMEOUT_MS)
            current.sink.onEvent(
                current.id,
                PaymentEvents.TOKEN_REQUEST,
                JSONObject(request.toString()).put("requestId", requestId).put("kind", kind),
            )
        }

        override fun setTransactionActive(active: Boolean, ownsScreen: Boolean) {
            transactionStartedAt = if (active) System.currentTimeMillis() else 0L
            val suspend = active && ownsScreen
            if (overlaysSuspended != suspend) {
                overlaysSuspended = suspend
                PaymentKioskGuards.setOverlaysSuspended(appContext, suspend)
            }
            DebugLog.d(TAG, "Transaction active: $active (owns screen: $ownsScreen)")
            // JS holds its own guards (page reloads, URL rotation, screensaver, updates).
            emit(PaymentEvents.TRANSACTION_ACTIVE, JSONObject().put("active", active).put("ownsScreen", ownsScreen))
        }
    }

    private fun requireSession(sessionId: String): Session {
        val current = session
        if (current == null || current.id != sessionId) {
            throw PaymentException(PaymentErrorCodes.NO_SESSION, "No open payment session with this id")
        }
        return current
    }

    private fun providerFor(sessionId: String, opId: String? = null): PaymentTerminalProvider =
        synchronized(lock) {
            val current = requireSession(sessionId)
            val p = provider ?: throw PaymentException(PaymentErrorCodes.NOT_INITIALIZED, "Call initialize first")
            if (opId != null) current.operations.add(opId)
            p
        }

    /** Forget [opId] once it settles, so closing the session does not try to cancel it. */
    private fun finishing(sessionId: String, opId: String, callback: PaymentCallback) = object : PaymentCallback {
        private fun done() = synchronized(lock) {
            session?.takeIf { it.id == sessionId }?.operations?.remove(opId)
        }

        override fun onSuccess(result: JSONObject) {
            done()
            callback.onSuccess(result)
        }

        override fun onError(error: PaymentError) {
            done()
            callback.onError(error)
        }
    }
}
