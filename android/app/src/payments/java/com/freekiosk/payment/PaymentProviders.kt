package com.freekiosk.payment

import android.app.Application
import com.freekiosk.payment.stripe.StripeTerminalProvider
import com.stripe.stripeterminal.TerminalApplicationDelegate
import com.stripe.stripeterminal.taptopay.TapToPay

/**
 * -Ppayments build: the payment SDKs compiled into this APK.
 *
 * The default build compiles src/nopayments/.../PaymentProviders.kt instead, which has the
 * same shape and no SDK.
 */
object PaymentProviders {
    val available: List<String> = listOf(StripeTerminalProvider.ID)

    fun create(id: String): PaymentTerminalProvider? = when (id) {
        StripeTerminalProvider.ID -> StripeTerminalProvider()
        else -> null
    }

    /** True inside Stripe's dedicated Tap to Pay process, where the app must not start. */
    fun isInPaymentSdkProcess(): Boolean = TapToPay.isInTapToPayProcess()

    /** Required by the Stripe Terminal SDK in every Application.onCreate. */
    fun onApplicationCreate(app: Application) {
        TerminalApplicationDelegate.onCreate(app)
    }
}
