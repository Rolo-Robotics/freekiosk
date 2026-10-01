package com.freekiosk.payment.stripe

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.freekiosk.DebugLog
import com.freekiosk.payment.PaymentCallback
import com.freekiosk.payment.PaymentError
import com.freekiosk.payment.PaymentErrorCodes
import com.freekiosk.payment.PaymentEvents
import com.freekiosk.payment.PaymentTerminalProvider
import com.freekiosk.payment.ProviderHost
import com.freekiosk.payment.TokenCallback
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.InternetReaderListener
import com.stripe.stripeterminal.external.callable.MobileReaderListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.SetupIntentCallback
import com.stripe.stripeterminal.external.callable.TapToPayReaderListener
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.AllowRedisplay
import com.stripe.stripeterminal.external.models.BatteryStatus
import com.stripe.stripeterminal.external.models.Cart
import com.stripe.stripeterminal.external.models.CartLineItem
import com.stripe.stripeterminal.external.models.CollectPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.CollectSetupIntentConfiguration
import com.stripe.stripeterminal.external.models.ConfirmPaymentIntentConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration.BluetoothConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration.InternetConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration.TapToPayConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration.UsbConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionStatus
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import com.stripe.stripeterminal.external.models.DisconnectReason
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration.BluetoothDiscoveryConfiguration
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration.InternetDiscoveryConfiguration
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration.TapToPayDiscoveryConfiguration
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration.UsbDiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.PaymentStatus
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.ReaderDisplayMessage
import com.stripe.stripeterminal.external.models.ReaderInputOptions
import com.stripe.stripeterminal.external.models.ReaderSoftwareUpdate
import com.stripe.stripeterminal.external.models.SetupIntent
import com.stripe.stripeterminal.external.models.TapUseCase
import com.stripe.stripeterminal.external.models.TerminalErrorCode
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Stripe Terminal SDK (5.x) behind the payment bridge.
 *
 * Reader modes: "tapToPay" (this device's NFC), "bluetooth", "usb", "internet".
 *
 * Payments are server-driven. The merchant backend creates the PaymentIntent. The page hands
 * over only its client secret, and this collects and confirms it. Capture and refunds stay
 * on the backend, so the amount is never decided by whatever runs in the page.
 *
 * The connection token is requested from the session owner (see ProviderHost.requestToken).
 * Stripe: "Don't cache or hardcode the connection token."
 *
 * Passthrough methods ([invoke]):
 *  - processSetupIntent {clientSecret, allowRedisplay?}: save a card for later (cancellable)
 *  - cancelPaymentIntent {clientSecret}
 *  - cancelReconnect {}: stop an automatic reconnect in progress
 *  - cancelReaderUpdate {}: stop a reader software update in progress
 */
class StripeTerminalProvider : PaymentTerminalProvider {

    companion object {
        const val ID = "stripe"
        private const val TAG = "StripeTerminal"
        private val MODES = listOf("tapToPay", "bluetooth", "usb", "internet")
    }

    override val id: String = ID

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var host: ProviderHost? = null
    @Volatile private var discoveryMode: String? = null
    @Volatile private var connectedMode: String? = null
    @Volatile private var defaultLocationId: String? = null

    /** Readers from the latest discovery, by the id the page sees (serial number, else id). */
    private val discovered = ConcurrentHashMap<String, Reader>()

    /** Running SDK operations by opId, so the page can cancel them. */
    private val operations = ConcurrentHashMap<String, Cancelable>()

    /** Cancels that arrived before the SDK handed back a Cancelable (during retrieve). */
    private val cancelledEarly = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var reconnectCancelable: Cancelable? = null
    @Volatile private var updateCancelable: Cancelable? = null

    // ==================== Setup ====================

    override fun capabilities(): JSONObject = JSONObject()
        .put("provider", ID)
        .put("readerModes", JSONArray(MODES))
        .put("methods", JSONArray(listOf("processSetupIntent", "cancelPaymentIntent", "cancelReconnect", "cancelReaderUpdate")))
        .put("collectPayment", JSONObject().put("requires", JSONArray(listOf("clientSecret"))))

    override fun initialize(context: Context, config: JSONObject, host: ProviderHost, callback: PaymentCallback) {
        this.host = host
        config.optString("locationId").takeIf { it.isNotBlank() }?.let { defaultLocationId = it }
        val logLevel = if (config.optBoolean("verboseLogs", false)) LogLevel.VERBOSE else LogLevel.NONE

        // The SDK wants its init on the main thread; React Native calls land on its own thread.
        mainHandler.post {
            try {
                if (!Terminal.isInitialized()) {
                    Terminal.init(context.applicationContext, logLevel, tokenProvider, terminalListener, null)
                }
                callback.onSuccess(getStatus())
            } catch (e: TerminalException) {
                callback.onError(errorOf(e))
            } catch (e: Exception) {
                callback.onError(PaymentError(PaymentErrorCodes.SDK_ERROR, e.message ?: "Terminal init failed"))
            }
        }
    }

    private val tokenProvider = object : ConnectionTokenProvider {
        override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
            val h = host
            if (h == null) {
                callback.onFailure(ConnectionTokenException("No payment session"))
                return
            }
            h.requestToken("stripeConnectionToken", JSONObject(), object : TokenCallback {
                override fun onToken(token: String) = callback.onSuccess(token)
                override fun onError(error: com.freekiosk.payment.PaymentError) =
                    callback.onFailure(ConnectionTokenException(error.message))
            })
        }
    }

    private val terminalListener = object : TerminalListener {
        override fun onConnectionStatusChange(status: ConnectionStatus) {
            emit(PaymentEvents.CONNECTION_STATUS, JSONObject().put("status", status.name))
        }

        override fun onPaymentStatusChange(status: PaymentStatus) {
            emit(PaymentEvents.PAYMENT_STATUS, JSONObject().put("status", status.name))
        }
    }

    // ==================== Readers ====================

    override fun discoverReaders(opId: String, options: JSONObject, callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        val mode = options.optString("mode", "bluetooth")
        val simulated = options.optBoolean("simulated", false)
        val timeout = options.optInt("timeout", 0)
        val config = when (mode) {
            "tapToPay" -> TapToPayDiscoveryConfiguration(isSimulated = simulated)
            "bluetooth" -> BluetoothDiscoveryConfiguration(timeout = timeout, isSimulated = simulated)
            "usb" -> UsbDiscoveryConfiguration(timeout = timeout, isSimulated = simulated)
            "internet" -> InternetDiscoveryConfiguration(
                timeout = timeout,
                location = options.optString("locationId").takeIf { it.isNotBlank() } ?: defaultLocationId,
                isSimulated = simulated,
            )
            else -> return callback.onError(
                PaymentError(PaymentErrorCodes.INVALID_ARGUMENT, "Unknown reader mode '$mode' (expected one of $MODES)")
            )
        }

        discovered.clear()
        discoveryMode = mode
        val cancelable = terminal.discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    discovered.clear()
                    val list = JSONArray()
                    for (reader in readers) {
                        val key = readerKey(reader) ?: continue
                        discovered[key] = reader
                        list.put(readerJson(reader))
                    }
                    emit(PaymentEvents.READERS_DISCOVERED, JSONObject().put("opId", opId).put("readers", list))
                }
            },
            object : Callback {
                override fun onSuccess() {
                    operations.remove(opId)
                    callback.onSuccess(JSONObject().put("opId", opId).put("readerCount", discovered.size))
                }

                override fun onFailure(e: TerminalException) {
                    operations.remove(opId)
                    callback.onError(errorOf(e))
                }
            },
        )
        track(opId, cancelable)
    }

    override fun connectReader(readerId: String, options: JSONObject, callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        val reader = discovered[readerId]
            ?: return callback.onError(PaymentError(PaymentErrorCodes.UNKNOWN_READER, "Reader $readerId was not in the last discovery"))
        val mode = discoveryMode
            ?: return callback.onError(PaymentError(PaymentErrorCodes.UNKNOWN_READER, "Discover readers first"))

        val autoReconnect = options.optBoolean("autoReconnect", true)
        val locationId = options.optString("locationId").takeIf { it.isNotBlank() }
            ?: defaultLocationId
            ?: reader.location?.id

        val needsLocation = mode != "internet"
        if (needsLocation && locationId.isNullOrBlank()) {
            return callback.onError(
                PaymentError(PaymentErrorCodes.INVALID_ARGUMENT, "A Stripe locationId is required to connect a $mode reader")
            )
        }

        val config = when (mode) {
            "tapToPay" -> TapToPayConnectionConfiguration(
                TapUseCase.Pay(locationId!!),
                autoReconnect,
                tapToPayListener,
                options.optString("merchantDisplayName").takeIf { it.isNotBlank() },
            )
            "bluetooth" -> BluetoothConnectionConfiguration(locationId!!, autoReconnect, mobileListener)
            "usb" -> UsbConnectionConfiguration(locationId!!, autoReconnect, mobileListener)
            else -> InternetConnectionConfiguration(internetListener, options.optBoolean("failIfInUse", true))
        }

        terminal.connectReader(reader, config, object : ReaderCallback {
            override fun onSuccess(reader: Reader) {
                connectedMode = mode
                callback.onSuccess(JSONObject().put("reader", readerJson(reader)).put("mode", mode))
            }

            override fun onFailure(e: TerminalException) = callback.onError(errorOf(e))
        })
    }

    override fun disconnectReader(callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        if (terminal.connectedReader == null) {
            callback.onSuccess(JSONObject().put("disconnected", false))
            return
        }
        terminal.disconnectReader(object : Callback {
            override fun onSuccess() {
                connectedMode = null
                callback.onSuccess(JSONObject().put("disconnected", true))
            }

            override fun onFailure(e: TerminalException) = callback.onError(errorOf(e))
        })
    }

    // ==================== Payments ====================

    override fun collectPayment(opId: String, request: JSONObject, callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        if (terminal.connectedReader == null) {
            return callback.onError(PaymentError(PaymentErrorCodes.NOT_CONNECTED, "Connect a reader first"))
        }
        val clientSecret = request.optString("clientSecret")
        if (clientSecret.isBlank()) {
            return callback.onError(
                PaymentError(PaymentErrorCodes.INVALID_ARGUMENT, "clientSecret of a server-created PaymentIntent is required")
            )
        }

        terminal.retrievePaymentIntent(clientSecret, object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                if (cancelledEarly.remove(opId)) {
                    callback.onError(PaymentError(PaymentErrorCodes.CANCELLED, "Cancelled before the reader was ready"))
                    return
                }
                val collectConfig = CollectPaymentIntentConfiguration.Builder()
                    .skipTipping(request.optBoolean("skipTipping", false))
                    .build()
                val confirmConfig = ConfirmPaymentIntentConfiguration.Builder().build()

                beginTransaction()
                val cancelable = terminal.processPaymentIntent(
                    paymentIntent,
                    collectConfig,
                    confirmConfig,
                    object : PaymentIntentCallback {
                        override fun onSuccess(paymentIntent: PaymentIntent) {
                            operations.remove(opId)
                            endTransaction()
                            callback.onSuccess(paymentIntentJson(paymentIntent))
                        }

                        override fun onFailure(e: TerminalException) {
                            operations.remove(opId)
                            endTransaction()
                            callback.onError(errorOf(e))
                        }
                    },
                )
                track(opId, cancelable)
            }

            override fun onFailure(e: TerminalException) {
                cancelledEarly.remove(opId)
                callback.onError(errorOf(e))
            }
        })
    }

    override fun cancel(opId: String, callback: PaymentCallback) {
        val cancelable = operations.remove(opId)
        if (cancelable == null) {
            // Still retrieving the intent, or already settled: stop it before it starts.
            cancelledEarly.add(opId)
            callback.onSuccess(JSONObject().put("cancelled", false))
            return
        }
        cancelable.cancel(object : Callback {
            override fun onSuccess() = callback.onSuccess(JSONObject().put("cancelled", true))
            override fun onFailure(e: TerminalException) = callback.onError(errorOf(e))
        })
    }

    // ==================== Reader display & updates ====================

    override fun setReaderDisplay(cart: JSONObject, callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        val built = try {
            val items = cart.optJSONArray("lineItems") ?: JSONArray()
            val lineItems = (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                CartLineItem.Builder(item.getString("description"), item.optInt("quantity", 1), item.getLong("amount")).build()
            }
            Cart.Builder(cart.getString("currency"), cart.optLong("tax", 0L), cart.getLong("total"), lineItems).build()
        } catch (e: Exception) {
            return callback.onError(
                PaymentError(PaymentErrorCodes.INVALID_ARGUMENT, "cart needs currency, total and lineItems[{description, quantity, amount}]")
            )
        }
        terminal.setReaderDisplay(built, voidCallback(callback))
    }

    override fun clearReaderDisplay(callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        terminal.clearReaderDisplay(voidCallback(callback))
    }

    override fun installReaderUpdate(callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        // Progress arrives through MobileReaderListener as readerUpdate events.
        terminal.installAvailableUpdate()
        callback.onSuccess(JSONObject().put("started", true))
    }

    override fun getStatus(): JSONObject {
        val status = JSONObject().put("provider", ID).put("initialized", Terminal.isInitialized())
        if (!Terminal.isInitialized()) return status
        val terminal = Terminal.getInstance()
        return status
            .put("connectionStatus", terminal.connectionStatus.name)
            .put("paymentStatus", terminal.paymentStatus.name)
            .put("mode", connectedMode ?: JSONObject.NULL)
            .put("reader", terminal.connectedReader?.let { readerJson(it) } ?: JSONObject.NULL)
    }

    // ==================== Passthrough ====================

    override fun invoke(opId: String, method: String, args: JSONObject, callback: PaymentCallback) {
        when (method) {
            "processSetupIntent" -> processSetupIntent(opId, args, callback)
            "cancelPaymentIntent" -> cancelPaymentIntent(args, callback)
            "cancelReconnect" -> cancelStored(reconnectCancelable, callback).also { reconnectCancelable = null }
            "cancelReaderUpdate" -> cancelStored(updateCancelable, callback).also { updateCancelable = null }
            else -> callback.onError(PaymentError(PaymentErrorCodes.UNKNOWN_METHOD, "Unknown Stripe method '$method'"))
        }
    }

    private fun processSetupIntent(opId: String, args: JSONObject, callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        val clientSecret = args.optString("clientSecret")
        if (clientSecret.isBlank()) {
            return callback.onError(PaymentError(PaymentErrorCodes.INVALID_ARGUMENT, "clientSecret is required"))
        }
        val allowRedisplay = try {
            AllowRedisplay.valueOf(args.optString("allowRedisplay", "ALWAYS").uppercase())
        } catch (e: IllegalArgumentException) {
            return callback.onError(PaymentError(PaymentErrorCodes.INVALID_ARGUMENT, "Unknown allowRedisplay value"))
        }

        terminal.retrieveSetupIntent(clientSecret, object : SetupIntentCallback {
            override fun onSuccess(setupIntent: SetupIntent) {
                if (cancelledEarly.remove(opId)) {
                    callback.onError(PaymentError(PaymentErrorCodes.CANCELLED, "Cancelled before the reader was ready"))
                    return
                }
                beginTransaction()
                val cancelable = terminal.processSetupIntent(
                    setupIntent,
                    allowRedisplay,
                    CollectSetupIntentConfiguration.Builder().build(),
                    object : SetupIntentCallback {
                        override fun onSuccess(setupIntent: SetupIntent) {
                            operations.remove(opId)
                            endTransaction()
                            callback.onSuccess(
                                JSONObject().put("id", setupIntent.id ?: JSONObject.NULL)
                                    .put("status", setupIntent.status?.toString() ?: JSONObject.NULL)
                            )
                        }

                        override fun onFailure(e: TerminalException) {
                            operations.remove(opId)
                            endTransaction()
                            callback.onError(errorOf(e))
                        }
                    },
                )
                track(opId, cancelable)
            }

            override fun onFailure(e: TerminalException) {
                cancelledEarly.remove(opId)
                callback.onError(errorOf(e))
            }
        })
    }

    private fun cancelPaymentIntent(args: JSONObject, callback: PaymentCallback) {
        val terminal = terminalOrFail(callback) ?: return
        val clientSecret = args.optString("clientSecret")
        if (clientSecret.isBlank()) {
            return callback.onError(PaymentError(PaymentErrorCodes.INVALID_ARGUMENT, "clientSecret is required"))
        }
        terminal.retrievePaymentIntent(clientSecret, object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                terminal.cancelPaymentIntent(paymentIntent, object : PaymentIntentCallback {
                    override fun onSuccess(paymentIntent: PaymentIntent) = callback.onSuccess(paymentIntentJson(paymentIntent))
                    override fun onFailure(e: TerminalException) = callback.onError(errorOf(e))
                })
            }

            override fun onFailure(e: TerminalException) = callback.onError(errorOf(e))
        })
    }

    private fun cancelStored(cancelable: Cancelable?, callback: PaymentCallback) {
        if (cancelable == null || cancelable.isCompleted) {
            callback.onSuccess(JSONObject().put("cancelled", false))
            return
        }
        cancelable.cancel(object : Callback {
            override fun onSuccess() = callback.onSuccess(JSONObject().put("cancelled", true))
            override fun onFailure(e: TerminalException) = callback.onError(errorOf(e))
        })
    }

    override fun shutdown() {
        for ((_, cancelable) in operations) {
            if (!cancelable.isCompleted) cancelable.cancel(noopCallback)
        }
        operations.clear()
        endTransaction()
        if (Terminal.isInitialized() && Terminal.getInstance().connectedReader != null) {
            Terminal.getInstance().disconnectReader(noopCallback)
        }
        host = null
    }

    // ==================== Reader listeners ====================

    private val mobileListener = object : MobileReaderListener {
        override fun onRequestReaderInput(options: ReaderInputOptions) {
            emit(
                PaymentEvents.READER_MESSAGE,
                JSONObject().put("kind", "input").put("options", JSONArray(options.options.map { it.toString() })),
            )
        }

        override fun onRequestReaderDisplayMessage(message: ReaderDisplayMessage) {
            emit(PaymentEvents.READER_MESSAGE, JSONObject().put("kind", "display").put("message", message.toString()))
        }

        override fun onStartInstallingUpdate(update: ReaderSoftwareUpdate, cancelable: Cancelable?) {
            updateCancelable = cancelable
            emit(PaymentEvents.READER_UPDATE, JSONObject().put("phase", "started"))
        }

        override fun onReportReaderSoftwareUpdateProgress(progress: Float) {
            emit(PaymentEvents.READER_UPDATE, JSONObject().put("phase", "progress").put("progress", progress.toDouble()))
        }

        override fun onFinishInstallingUpdate(update: ReaderSoftwareUpdate?, e: TerminalException?) {
            updateCancelable = null
            val payload = JSONObject().put("phase", "finished")
            if (e != null) payload.put("error", errorOf(e).toJson())
            emit(PaymentEvents.READER_UPDATE, payload)
        }

        override fun onReportAvailableUpdate(update: ReaderSoftwareUpdate) {
            emit(PaymentEvents.READER_UPDATE, JSONObject().put("phase", "available"))
        }

        override fun onReportLowBatteryWarning() {
            emit(PaymentEvents.BATTERY, JSONObject().put("low", true))
        }

        override fun onBatteryLevelUpdate(batteryLevel: Float, batteryStatus: BatteryStatus, isCharging: Boolean) {
            emit(
                PaymentEvents.BATTERY,
                JSONObject().put("level", batteryLevel.toDouble()).put("status", batteryStatus.toString()).put("charging", isCharging),
            )
        }

        override fun onDisconnect(reason: DisconnectReason) = onReaderDisconnected(reason)

        override fun onReaderReconnectStarted(reader: Reader, cancelReconnect: Cancelable, reason: DisconnectReason) =
            onReconnectStarted(cancelReconnect, reason)

        override fun onReaderReconnectSucceeded(reader: Reader) = onReconnectFinished(true)

        override fun onReaderReconnectFailed(reader: Reader) = onReconnectFinished(false)
    }

    private val tapToPayListener = object : TapToPayReaderListener {
        override fun onDisconnect(reason: DisconnectReason) = onReaderDisconnected(reason)

        override fun onReaderReconnectStarted(reader: Reader, cancelReconnect: Cancelable, reason: DisconnectReason) =
            onReconnectStarted(cancelReconnect, reason)

        override fun onReaderReconnectSucceeded(reader: Reader) = onReconnectFinished(true)

        override fun onReaderReconnectFailed(reader: Reader) = onReconnectFinished(false)
    }

    private val internetListener = object : InternetReaderListener {
        override fun onDisconnect(reason: DisconnectReason) = onReaderDisconnected(reason)
    }

    private fun onReaderDisconnected(reason: DisconnectReason) {
        connectedMode = null
        endTransaction()
        emit(PaymentEvents.READER_DISCONNECTED, JSONObject().put("reason", reason.toString()))
    }

    private fun onReconnectStarted(cancelReconnect: Cancelable, reason: DisconnectReason) {
        reconnectCancelable = cancelReconnect
        emit(PaymentEvents.READER_RECONNECT, JSONObject().put("phase", "started").put("reason", reason.toString()))
    }

    private fun onReconnectFinished(succeeded: Boolean) {
        reconnectCancelable = null
        if (!succeeded) connectedMode = null
        emit(PaymentEvents.READER_RECONNECT, JSONObject().put("phase", if (succeeded) "succeeded" else "failed"))
    }

    // ==================== Helpers ====================

    private fun emit(type: String, payload: JSONObject) {
        host?.emit(type, payload)
    }

    /** Tap to Pay draws its own full-screen UI; external readers prompt on their own screen. */
    private fun beginTransaction() = host?.setTransactionActive(true, connectedMode == "tapToPay")

    private fun endTransaction() = host?.setTransactionActive(false, false)

    private fun track(opId: String, cancelable: Cancelable) {
        operations[opId] = cancelable
        // The SDK may have settled before handing the Cancelable back.
        if (cancelable.isCompleted) operations.remove(opId)
        if (cancelledEarly.remove(opId) && !cancelable.isCompleted) {
            operations.remove(opId)
            cancelable.cancel(noopCallback)
        }
    }

    private fun terminalOrFail(callback: PaymentCallback): Terminal? {
        if (!Terminal.isInitialized()) {
            callback.onError(PaymentError(PaymentErrorCodes.NOT_INITIALIZED, "Call initialize first"))
            return null
        }
        return Terminal.getInstance()
    }

    private fun voidCallback(callback: PaymentCallback) = object : Callback {
        override fun onSuccess() = callback.onSuccess(JSONObject())
        override fun onFailure(e: TerminalException) = callback.onError(errorOf(e))
    }

    private val noopCallback = object : Callback {
        override fun onSuccess() {}
        override fun onFailure(e: TerminalException) {
            DebugLog.w(TAG, "Background cancel failed: ${e.errorCode.name}")
        }
    }

    private fun errorOf(e: TerminalException): PaymentError =
        if (e.errorCode == TerminalErrorCode.CANCELED) {
            PaymentError(PaymentErrorCodes.CANCELLED, e.errorMessage)
        } else {
            PaymentError(e.errorCode.name, e.errorMessage)
        }

    private fun readerKey(reader: Reader): String? = reader.serialNumber ?: reader.id

    private fun readerJson(reader: Reader): JSONObject = JSONObject()
        .put("id", readerKey(reader) ?: JSONObject.NULL)
        .put("serialNumber", reader.serialNumber ?: JSONObject.NULL)
        .put("label", reader.label ?: JSONObject.NULL)
        .put("deviceType", reader.deviceType.name)
        .put("locationId", reader.location?.id ?: JSONObject.NULL)
        .put("batteryLevel", reader.batteryLevel?.toDouble() ?: JSONObject.NULL)
        .put("softwareVersion", reader.softwareVersion)
        .put("networkStatus", reader.networkStatus?.name ?: JSONObject.NULL)
        .put("updateAvailable", reader.availableUpdate != null)
        .put("simulated", reader.isSimulated)

    /** Deliberately leaves out the client secret: the page already has it, and it must not echo. */
    private fun paymentIntentJson(intent: PaymentIntent): JSONObject = JSONObject()
        .put("id", intent.id ?: JSONObject.NULL)
        .put("status", intent.status?.name ?: JSONObject.NULL)
        .put("amount", intent.amount)
        .put("amountReceived", intent.amountReceived)
        .put("currency", intent.currency)
}
