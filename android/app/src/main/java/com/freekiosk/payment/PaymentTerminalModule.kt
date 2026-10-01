package com.freekiosk.payment

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONException
import org.json.JSONObject

/**
 * React Native side of the payment bridge. A thin adapter over [PaymentTerminalManager].
 *
 * Arguments and results cross the bridge as JSON strings, which keeps this module a pure
 * passthrough and lets the provider-specific [invoke] carry arbitrary shapes.
 *
 * Events arrive as one `paymentTerminalEvent` whose payload is a JSON string
 * `{sessionId, type, payload}`.
 *
 * Never logs arguments or results: they carry client secrets and tokens.
 */
class PaymentTerminalModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val EVENT = "paymentTerminalEvent"
    }

    init {
        PaymentTerminalManager.attach(reactContext)
    }

    override fun getName(): String = "PaymentTerminalModule"

    private val sink = PaymentEventSink { sessionId, type, payload ->
        val body = JSONObject().put("sessionId", sessionId).put("type", type).put("payload", payload)
        try {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(EVENT, body.toString())
        } catch (e: Exception) {
            // JS is gone (reload, teardown): nothing to deliver to.
        }
    }

    private fun promiseCallback(promise: Promise) = object : PaymentCallback {
        override fun onSuccess(result: JSONObject) = promise.resolve(result.toString())
        override fun onError(error: PaymentError) = promise.reject(error.code, error.message)
    }

    /** Runs [block], turning the bridge's own exceptions into promise rejections. */
    private inline fun guarded(promise: Promise, block: () -> Unit) {
        try {
            block()
        } catch (e: PaymentException) {
            promise.reject(e.error.code, e.error.message)
        } catch (e: JSONException) {
            promise.reject(PaymentErrorCodes.INVALID_ARGUMENT, "Malformed JSON argument")
        } catch (e: Exception) {
            promise.reject(PaymentErrorCodes.SDK_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun json(value: String?): JSONObject =
        if (value.isNullOrBlank()) JSONObject() else JSONObject(value)

    @ReactMethod
    fun getInfo(promise: Promise) = guarded(promise) {
        promise.resolve(PaymentTerminalManager.info().toString())
    }

    @ReactMethod
    fun getReadiness(providerId: String?, promise: Promise) = guarded(promise) {
        promise.resolve(PaymentReadiness.check(reactApplicationContext, providerId).toString())
    }

    @ReactMethod
    fun openSession(owner: String, promise: Promise) = guarded(promise) {
        promise.resolve(PaymentTerminalManager.openSession(owner, sink))
    }

    @ReactMethod
    fun closeSession(sessionId: String, promise: Promise) = guarded(promise) {
        PaymentTerminalManager.closeSession(sessionId)
        promise.resolve(null)
    }

    @ReactMethod
    fun closeSessionsOwnedBy(owner: String, reason: String, promise: Promise) = guarded(promise) {
        PaymentTerminalManager.closeSessionsOwnedBy(owner, reason)
        promise.resolve(null)
    }

    @ReactMethod
    fun initialize(sessionId: String, providerId: String, configJson: String?, promise: Promise) =
        guarded(promise) {
            PaymentTerminalManager.initialize(sessionId, providerId, json(configJson), promiseCallback(promise))
        }

    @ReactMethod
    fun discoverReaders(sessionId: String, opId: String, optionsJson: String?, promise: Promise) =
        guarded(promise) {
            PaymentTerminalManager.discoverReaders(sessionId, opId, json(optionsJson), promiseCallback(promise))
        }

    @ReactMethod
    fun connectReader(sessionId: String, readerId: String, optionsJson: String?, promise: Promise) =
        guarded(promise) {
            PaymentTerminalManager.connectReader(sessionId, readerId, json(optionsJson), promiseCallback(promise))
        }

    @ReactMethod
    fun disconnectReader(sessionId: String, promise: Promise) = guarded(promise) {
        PaymentTerminalManager.disconnectReader(sessionId, promiseCallback(promise))
    }

    @ReactMethod
    fun collectPayment(sessionId: String, opId: String, requestJson: String?, promise: Promise) =
        guarded(promise) {
            PaymentTerminalManager.collectPayment(sessionId, opId, json(requestJson), promiseCallback(promise))
        }

    @ReactMethod
    fun cancel(sessionId: String, opId: String, promise: Promise) = guarded(promise) {
        PaymentTerminalManager.cancel(sessionId, opId, promiseCallback(promise))
    }

    @ReactMethod
    fun setReaderDisplay(sessionId: String, cartJson: String?, promise: Promise) = guarded(promise) {
        PaymentTerminalManager.setReaderDisplay(sessionId, json(cartJson), promiseCallback(promise))
    }

    @ReactMethod
    fun clearReaderDisplay(sessionId: String, promise: Promise) = guarded(promise) {
        PaymentTerminalManager.clearReaderDisplay(sessionId, promiseCallback(promise))
    }

    @ReactMethod
    fun installReaderUpdate(sessionId: String, promise: Promise) = guarded(promise) {
        PaymentTerminalManager.installReaderUpdate(sessionId, promiseCallback(promise))
    }

    @ReactMethod
    fun getStatus(sessionId: String, promise: Promise) = guarded(promise) {
        promise.resolve(PaymentTerminalManager.getStatus(sessionId).toString())
    }

    @ReactMethod
    fun invoke(sessionId: String, opId: String, method: String, argsJson: String?, promise: Promise) =
        guarded(promise) {
            PaymentTerminalManager.invoke(sessionId, opId, method, json(argsJson), promiseCallback(promise))
        }

    @ReactMethod
    fun provideToken(sessionId: String, requestId: String, token: String, promise: Promise) =
        guarded(promise) {
            PaymentTerminalManager.provideToken(sessionId, requestId, token)
            promise.resolve(null)
        }

    @ReactMethod
    fun failTokenRequest(sessionId: String, requestId: String, message: String, promise: Promise) =
        guarded(promise) {
            PaymentTerminalManager.failTokenRequest(sessionId, requestId, message)
            promise.resolve(null)
        }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    override fun invalidate() {
        // The JS runtime is going away (reload). A session opened from it can no longer be
        // answered, so release it rather than leave the terminal held by nobody.
        PaymentTerminalManager.closeSessionsOwnedBy(PaymentOwners.WEBVIEW, "jsReload")
        super.invalidate()
    }
}

/** Session owner names. One per transport, so a reload only frees its own transport's session. */
object PaymentOwners {
    const val WEBVIEW = "webview"
}
