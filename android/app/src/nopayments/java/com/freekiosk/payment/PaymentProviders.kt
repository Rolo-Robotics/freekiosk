package com.freekiosk.payment

import android.app.Application

/**
 * Build without -Ppayments: no payment SDK is compiled in. The bridge still loads and
 * answers every call with UNSUPPORTED, so the web API can tell the page why.
 *
 * The -Ppayments build replaces this file with src/payments/java/.../PaymentProviders.kt.
 */
object PaymentProviders {
    val available: List<String> = emptyList()

    fun create(id: String): PaymentTerminalProvider? = null

    fun isInPaymentSdkProcess(): Boolean = false

    fun onApplicationCreate(app: Application) {}
}
