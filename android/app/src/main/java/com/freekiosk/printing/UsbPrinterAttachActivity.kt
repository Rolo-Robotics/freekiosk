package com.freekiosk.printing

import android.app.Activity
import android.os.Bundle
import com.freekiosk.DebugLog

/**
 * Exists only to be declared, not to be seen.
 *
 * Android grants lasting USB access when an app is the DEFAULT HANDLER for a device, which it can
 * only offer if the app declares a USB_DEVICE_ATTACHED filter. Asking through
 * UsbManager.requestPermission instead produces a grant that dies with the next unplug — no good for
 * a tablet that must keep printing through reboots and through the re-enumeration a powered USB-C
 * adapter causes whenever its power is plugged or unplugged.
 *
 * So: plug the printer in, choose this app, tick "always". From then on access is silent, which
 * matters because lock task mode suppresses system dialogs anyway.
 *
 * It finishes immediately rather than showing anything, and stays out of the back stack, so the
 * kiosk never visibly leaves the page it is displaying.
 */
class UsbPrinterAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.d("UsbPrinterAttach", "USB printer attached; default-handler access confirmed")
        finish()
    }
}
