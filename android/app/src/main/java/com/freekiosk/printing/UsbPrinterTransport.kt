package com.freekiosk.printing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.freekiosk.DebugLog

/**
 * USB receipt printers, over the USB Printer Class (USB-IF class 07h).
 *
 * That class is what almost every USB receipt printer implements, so targeting it — rather than a
 * table of vendor ids — is what makes this work with printers nobody here has ever seen. Printers
 * that expose a vendor-specific interface instead are still handled, by falling back to the first
 * interface carrying a bulk OUT endpoint; they just cannot answer the two class requests below.
 *
 * Nothing is cached between jobs. A USB-C adapter with power passthrough re-enumerates its devices
 * whenever power is plugged or unplugged, so a UsbDevice held across that is stale and its
 * connection silently dead.
 */
class UsbPrinterTransport(private val context: Context) : PrinterTransport {

    companion object {
        private const val TAG = "UsbPrinterTransport"

        private const val ACTION_USB_PERMISSION = "com.freekiosk.printing.USB_PERMISSION"

        /** Class request 0: GET_DEVICE_ID, returns the printer's IEEE 1284 identity string. */
        private const val REQ_GET_DEVICE_ID = 0
        /** Class request 1: GET_PORT_STATUS, one byte of paper/select/error bits. */
        private const val REQ_GET_PORT_STATUS = 1
        /** Device-to-host | class | interface. */
        private const val REQ_TYPE_CLASS_INTERFACE_IN = 0xA1
        /** GET_PORT_STATUS bit 5: paper empty. */
        private const val PORT_STATUS_PAPER_EMPTY = 0x20

        private const val CONTROL_TIMEOUT_MS = 1_000
        private const val WRITE_TIMEOUT_MS = 5_000
        /** Chunk size for bulk writes. Small enough for modest printer buffers, big enough to be quick. */
        private const val CHUNK_BYTES = 4_096
    }

    override val kind: String = "usb"

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** A printer, the interface to claim, and its bulk OUT endpoint. */
    private data class Target(
        val device: UsbDevice,
        val iface: UsbInterface,
        val out: UsbEndpoint,
        /** True when this is a real printer-class interface, so the class requests are meaningful. */
        val isPrinterClass: Boolean,
    )

    private fun findTarget(): Target? {
        val devices = usbManager?.deviceList?.values ?: return null
        var fallback: Target? = null

        for (device in devices) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val out = bulkOutEndpoint(iface) ?: continue
                val isPrinter = iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER
                val target = Target(device, iface, out, isPrinter)
                // A printer-class interface always wins; anything else is only a fallback, because a
                // bulk OUT endpoint alone might be a scanner, a serial bridge or a storage device.
                if (isPrinter) return target
                if (fallback == null) fallback = target
            }
        }
        return fallback
    }

    private fun bulkOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (e in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(e)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                endpoint.direction == UsbConstants.USB_DIR_OUT
            ) {
                return endpoint
            }
        }
        return null
    }

    override fun find(): PrinterDescription? = findTarget()?.let { describe(it, connection = null) }

    override fun hasPermission(): Boolean {
        val target = findTarget() ?: return false
        return usbManager?.hasPermission(target.device) == true
    }

    override fun requestPermission(callback: (granted: Boolean) -> Unit) {
        val manager = usbManager
        val target = findTarget()
        if (manager == null || target == null) {
            callback(false)
            return
        }
        if (manager.hasPermission(target.device)) {
            callback(true)
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                try {
                    context.unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    DebugLog.w(TAG, "Permission receiver already unregistered: ${e.message}")
                }
                callback(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        manager.requestPermission(target.device, intent)
    }

    override fun status(): PrinterStatus {
        val target = findTarget()
            ?: return PrinterStatus(PrinterStatus.State.NO_PRINTER, PaperState.UNKNOWN, null)

        val manager = usbManager
        if (manager == null || !manager.hasPermission(target.device)) {
            return PrinterStatus(
                PrinterStatus.State.NO_PERMISSION,
                PaperState.UNKNOWN,
                describe(target, connection = null),
            )
        }

        var connection: UsbDeviceConnection? = null
        return try {
            connection = manager.openDevice(target.device)
                ?: return PrinterStatus(
                    PrinterStatus.State.ERROR,
                    PaperState.UNKNOWN,
                    describe(target, connection = null),
                )
            val paper = readPaperState(connection, target)
            val state =
                if (paper == PaperState.OUT) PrinterStatus.State.PAPER_OUT else PrinterStatus.State.READY
            PrinterStatus(state, paper, describe(target, connection))
        } catch (e: Exception) {
            DebugLog.errorProduction(TAG, "Status failed: ${e.message}")
            PrinterStatus(PrinterStatus.State.ERROR, PaperState.UNKNOWN, describe(target, null))
        } finally {
            connection?.close()
        }
    }

    override fun write(bytes: ByteArray) {
        val target = findTarget()
            ?: throw PrinterException(PrinterException.NO_PRINTER, "No USB printer attached")
        val manager = usbManager
            ?: throw PrinterException(PrinterException.OPEN_FAILED, "No USB service")
        if (!manager.hasPermission(target.device)) {
            throw PrinterException(PrinterException.NO_PERMISSION, "No permission for the USB printer")
        }

        val connection = manager.openDevice(target.device)
            ?: throw PrinterException(PrinterException.OPEN_FAILED, "Could not open the USB printer")
        try {
            if (!connection.claimInterface(target.iface, true)) {
                throw PrinterException(
                    PrinterException.OPEN_FAILED,
                    "Could not claim the printer interface",
                )
            }
            if (readPaperState(connection, target) == PaperState.OUT) {
                // Checked before writing, not after: a printer that is out of paper swallows the job
                // silently, and "printed" would then be a lie the page shows the customer.
                throw PrinterException(PrinterException.PAPER_OUT, "The printer is out of paper")
            }
            writeAllChunks(connection, target, bytes)
        } finally {
            runCatching { connection.releaseInterface(target.iface) }
            connection.close()
        }
    }

    private fun writeAllChunks(
        connection: UsbDeviceConnection,
        target: Target,
        bytes: ByteArray,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(CHUNK_BYTES, bytes.size - offset)
            val sent = connection.bulkTransfer(target.out, bytes, offset, length, WRITE_TIMEOUT_MS)
            if (sent <= 0) {
                throw PrinterException(
                    PrinterException.WRITE_FAILED,
                    "Printer stopped accepting data after $offset of ${bytes.size} bytes",
                )
            }
            // A short write is normal on bulk endpoints: advance by what was actually taken.
            offset += sent
        }
        DebugLog.d(TAG, "Wrote ${bytes.size} bytes to ${target.device.deviceName}")
    }

    /**
     * IEEE 1284 GET_PORT_STATUS. Only printer-class interfaces answer it, and even among those,
     * clones may return a constant — hence UNKNOWN rather than an assumption either way.
     */
    private fun readPaperState(connection: UsbDeviceConnection, target: Target): PaperState {
        if (!target.isPrinterClass) return PaperState.UNKNOWN
        val buffer = ByteArray(1)
        val read = connection.controlTransfer(
            REQ_TYPE_CLASS_INTERFACE_IN,
            REQ_GET_PORT_STATUS,
            0,
            target.iface.id,
            buffer,
            buffer.size,
            CONTROL_TIMEOUT_MS,
        )
        if (read != 1) return PaperState.UNKNOWN
        val paperEmpty = (buffer[0].toInt() and PORT_STATUS_PAPER_EMPTY) != 0
        return if (paperEmpty) PaperState.OUT else PaperState.OK
    }

    /**
     * IEEE 1284 GET_DEVICE_ID: "MFG:Xprinter;MDL:XP-58;CMD:ESC/POS;", length-prefixed big-endian.
     * This is how we learn the command set without a per-model table.
     */
    private fun readDeviceId(connection: UsbDeviceConnection, target: Target): Map<String, String> {
        if (!target.isPrinterClass) return emptyMap()
        val buffer = ByteArray(1024)
        val read = connection.controlTransfer(
            REQ_TYPE_CLASS_INTERFACE_IN,
            REQ_GET_DEVICE_ID,
            0,
            target.iface.id shl 8,
            buffer,
            buffer.size,
            CONTROL_TIMEOUT_MS,
        )
        if (read < 3) return emptyMap()

        val declared = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        val length = minOf(declared - 2, read - 2).coerceAtLeast(0)
        if (length == 0) return emptyMap()

        return String(buffer, 2, length, Charsets.US_ASCII)
            .split(';')
            .mapNotNull { field ->
                val parts = field.split(':', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    parts[0].trim().uppercase() to parts[1].trim()
                } else {
                    null
                }
            }
            .toMap()
    }

    private fun describe(target: Target, connection: UsbDeviceConnection?): PrinterDescription {
        val id = connection?.let { runCatching { readDeviceId(it, target) }.getOrDefault(emptyMap()) }
            ?: emptyMap()
        val manufacturer = id["MFG"] ?: id["MANUFACTURER"]
        val model = id["MDL"] ?: id["MODEL"]
        val commandSet = id["CMD"] ?: id["COMMAND SET"]

        // Best name we can manage, in descending order of trustworthiness. getProductName needs
        // permission and is often null on cheap hardware, so it is not the first choice.
        val name = listOfNotNull(manufacturer, model)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
            ?: runCatching { target.device.productName }.getOrNull()
            ?: "USB printer ${String.format("%04x:%04x", target.device.vendorId, target.device.productId)}"

        return PrinterDescription(
            kind = kind,
            displayName = name,
            vendorId = target.device.vendorId,
            productId = target.device.productId,
            manufacturer = manufacturer,
            model = model,
            commandSet = commandSet,
        )
    }
}
