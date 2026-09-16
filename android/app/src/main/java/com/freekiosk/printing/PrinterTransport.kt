package com.freekiosk.printing

/**
 * A way to reach a receipt printer and push already-encoded bytes at it.
 *
 * Deliberately narrow: the transport knows how to find the printer, whether it may talk to it, and
 * how to get bytes across. What those bytes MEAN is the [RasterDriver]'s business. Keeping the two
 * apart is what lets a Bluetooth SPP or a TCP:9100 transport arrive later without touching the
 * encoder, and a Star driver without touching the USB code.
 */
interface PrinterTransport {

    /** Short id used in settings and logs: "usb", later "bluetooth", "network". */
    val kind: String

    /**
     * The attached printer, or null when there is none. Must not require permission — it is what the
     * settings screen shows before the user has granted anything.
     */
    fun find(): PrinterDescription?

    /** Has the user granted access to the printer [find] returns? */
    fun hasPermission(): Boolean

    /**
     * Ask for access. The result arrives asynchronously because Android shows a system dialog.
     * A no-op resolving to true for transports that need no permission.
     */
    fun requestPermission(callback: (granted: Boolean) -> Unit)

    /** Live state, including paper where the printer will tell us. */
    fun status(): PrinterStatus

    /**
     * Open, write every byte, close. Blocking, and expected to be called off the main thread.
     * Throws [PrinterException] with a stable code on any failure.
     */
    fun write(bytes: ByteArray)
}

/** Identity of an attached printer. Everything except [kind] may be unknown. */
data class PrinterDescription(
    val kind: String,
    val displayName: String,
    val vendorId: Int? = null,
    val productId: Int? = null,
    /** IEEE 1284 MFG: — the manufacturer as the printer reports it. */
    val manufacturer: String? = null,
    /** IEEE 1284 MDL: */
    val model: String? = null,
    /** IEEE 1284 CMD: — e.g. "ESC/POS". Chooses the driver when we support more than one. */
    val commandSet: String? = null,
) {
    /** "0416:5011", handy in settings when a printer needs adding to the USB device filter. */
    val hardwareId: String?
        get() = if (vendorId != null && productId != null) {
            String.format("%04x:%04x", vendorId, productId)
        } else {
            null
        }
}

/**
 * Paper as the printer reports it.
 *
 * UNKNOWN is not a failure and must be treated as printable: plenty of cheap printers answer no
 * status request at all, and refusing to print to those would make the feature useless on exactly
 * the hardware it is aimed at.
 */
enum class PaperState { OK, OUT, UNKNOWN }

data class PrinterStatus(
    val state: State,
    val paper: PaperState,
    val description: PrinterDescription?,
) {
    enum class State {
        /** A printer is attached, we may talk to it, and it is not reporting out-of-paper. */
        READY,
        NO_PRINTER,
        NO_PERMISSION,
        PAPER_OUT,
        ERROR,
    }
}

/** Failure with a stable code, so JS can branch on the cause rather than on message text. */
class PrinterException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val NO_PRINTER = "NO_PRINTER"
        const val NO_PERMISSION = "NO_PERMISSION"
        const val PAPER_OUT = "PAPER_OUT"
        const val OPEN_FAILED = "OPEN_FAILED"
        const val WRITE_FAILED = "WRITE_FAILED"
        const val BAD_IMAGE = "BAD_IMAGE"
        /** The page could not be turned into dots — see PageRasterizer. */
        const val PAGE_RENDER_FAILED = "PAGE_RENDER_FAILED"
        /** No WebView on screen to print, e.g. the kiosk is in external-app mode. */
        const val NO_WEBVIEW = "NO_WEBVIEW"
    }
}
