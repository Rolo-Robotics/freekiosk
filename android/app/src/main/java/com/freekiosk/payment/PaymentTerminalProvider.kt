package com.freekiosk.payment

import android.content.Context
import org.json.JSONObject

/**
 * One payment SDK (Stripe Terminal, Adyen POS Mobile, ...) behind the payment bridge.
 *
 * The contract is deliberately thin. The generic calls cover what every SDK really has in
 * common - discover, connect, collect, cancel - and [invoke] passes anything else straight to
 * the SDK under a provider-specific method name. Stripe's typed PaymentIntent model and Adyen's
 * nexo messages do not map onto each other, and a schema that pretended they did would lose
 * whatever it could not express.
 *
 * Every argument and result is JSON so the same provider serves the React Native bridge and,
 * later, a bound service for external apps. Implementations must never log tokens, client
 * secrets or payment payloads: release builds keep logcat.
 *
 * Callbacks may arrive on any thread. [PaymentTerminalManager] serialises what it needs to.
 */
interface PaymentTerminalProvider {

    /** Stable identifier, e.g. "stripe". */
    val id: String

    /** Which reader types and passthrough methods this provider supports. */
    fun capabilities(): JSONObject

    /**
     * Initialise the SDK. Safe to call again: a provider that is already initialised
     * answers with its status instead of failing.
     */
    fun initialize(context: Context, config: JSONObject, host: ProviderHost, callback: PaymentCallback)

    /**
     * Start discovery. Found readers are reported as [PaymentEvents.READERS_DISCOVERED] events
     * until the discovery ends, is cancelled through [cancel] with the same [opId], or a reader
     * is connected. [callback] fires once, when discovery ends.
     */
    fun discoverReaders(opId: String, options: JSONObject, callback: PaymentCallback)

    /** Connect a reader reported by the last discovery, by its [readerId] (serial number). */
    fun connectReader(readerId: String, options: JSONObject, callback: PaymentCallback)

    fun disconnectReader(callback: PaymentCallback)

    /**
     * Collect and confirm a payment the merchant backend has already created. The request
     * carries a reference to it (Stripe: the PaymentIntent client secret), never an amount the
     * page made up. Capture stays on the backend.
     */
    fun collectPayment(opId: String, request: JSONObject, callback: PaymentCallback)

    /** Cancel the operation started under [opId]. Unknown ids resolve with `cancelled=false`. */
    fun cancel(opId: String, callback: PaymentCallback)

    fun setReaderDisplay(cart: JSONObject, callback: PaymentCallback)

    fun clearReaderDisplay(callback: PaymentCallback)

    fun installReaderUpdate(callback: PaymentCallback)

    /** Snapshot of SDK state: initialised, connection status, connected reader, busy. */
    fun getStatus(): JSONObject

    /**
     * Provider-specific passthrough. Unknown methods fail with [PaymentErrorCodes.UNKNOWN_METHOD].
     * Operations that run long are started under [opId] so [cancel] can stop them.
     */
    fun invoke(opId: String, method: String, args: JSONObject, callback: PaymentCallback)

    /** Tear everything down: cancel pending work, disconnect, drop listeners. */
    fun shutdown()
}

/**
 * What a provider can ask of the bridge. Implemented by [PaymentTerminalManager].
 */
interface ProviderHost {
    /** Forward an SDK event to whoever owns the current session. */
    fun emit(type: String, payload: JSONObject)

    /**
     * Ask the session owner for a fresh SDK credential (Stripe connection token, Adyen
     * authentication response). The owner fetches it from its own backend. FreeKiosk never
     * holds the secret API key that mints it.
     */
    fun requestToken(kind: String, request: JSONObject, callback: TokenCallback)

    /**
     * Mark the stretch where a card is being read. [ownsScreen] is true when the SDK draws its
     * own full-screen UI on this device (Tap to Pay): FreeKiosk's overlays then have to go,
     * because the SDK refuses PIN entry while any overlay window is up. An external reader
     * shows its prompts and takes the PIN itself, so overlays can stay.
     */
    fun setTransactionActive(active: Boolean, ownsScreen: Boolean)
}

interface TokenCallback {
    fun onToken(token: String)
    fun onError(error: PaymentError)
}

interface PaymentCallback {
    fun onSuccess(result: JSONObject)
    fun onError(error: PaymentError)
}

data class PaymentError(val code: String, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("code", code).put("message", message)
}

class PaymentException(val error: PaymentError) : Exception(error.message) {
    constructor(code: String, message: String) : this(PaymentError(code, message))
}

object PaymentErrorCodes {
    const val UNSUPPORTED = "UNSUPPORTED"
    const val NOT_INITIALIZED = "NOT_INITIALIZED"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val NO_SESSION = "NO_SESSION"
    const val BUSY = "BUSY"
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val UNKNOWN_METHOD = "UNKNOWN_METHOD"
    const val UNKNOWN_READER = "UNKNOWN_READER"
    const val TOKEN_TIMEOUT = "TOKEN_TIMEOUT"
    const val TOKEN_FAILED = "TOKEN_FAILED"
    const val CANCELLED = "CANCELLED"
    const val SDK_ERROR = "SDK_ERROR"
}

object PaymentEvents {
    const val READERS_DISCOVERED = "readersDiscovered"
    const val CONNECTION_STATUS = "connectionStatus"
    const val PAYMENT_STATUS = "paymentStatus"
    /** Instruction for the customer: "insert or tap card", "remove card", ... */
    const val READER_MESSAGE = "readerMessage"
    const val READER_UPDATE = "readerUpdate"
    const val READER_DISCONNECTED = "readerDisconnected"
    const val READER_RECONNECT = "readerReconnect"
    const val BATTERY = "battery"
    /** The owner must answer with provideToken(requestId, token). Carries no secret. */
    const val TOKEN_REQUEST = "tokenRequest"
    /** A card is being read, or no longer is. Emitted by the manager, not by providers. */
    const val TRANSACTION_ACTIVE = "transactionActive"
    /** The session was closed from the FreeKiosk side (page navigated away, kiosk reset). */
    const val SESSION_CLOSED = "sessionClosed"
}
