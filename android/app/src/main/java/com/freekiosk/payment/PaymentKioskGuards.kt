package com.freekiosk.payment

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.freekiosk.BlockingOverlayManager
import com.freekiosk.OverlayService

/**
 * The kiosk features that have to stand back while a payment SDK owns the screen.
 *
 * The watchdog, lock task re-entry and remote screenshots read
 * [PaymentTerminalManager.isTransactionActive] directly, the same way they read
 * PrintModule.isPrintActive. Overlays are windows that already exist, so they are
 * taken down and put back from here.
 */
internal object PaymentKioskGuards {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun setOverlaysSuspended(context: Context?, suspended: Boolean) {
        OverlayService.setSuspendedForPayment(suspended)
        val app = context ?: return
        // WindowManager calls belong on the main thread; SDK callbacks arrive on any.
        mainHandler.post {
            BlockingOverlayManager.getInstance(app).setSuspendedForPayment(suspended)
        }
    }
}
