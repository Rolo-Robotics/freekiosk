package com.freekiosk.printing

import java.io.ByteArrayOutputStream

/** How a receipt is printed once it has been reduced to dots. */
data class PrintOptions(
    /** Print width in dots. 384 for 58mm paper, 576 for 80mm, both at the usual 203dpi. */
    val widthDots: Int = 384,
    /** Blank lines fed after the receipt, so the last line clears the tear bar. */
    val feedLines: Int = 4,
    /** Send a cut command. Off by default: plenty of 58mm printers have no cutter. */
    val cut: Boolean = false,
    /** Luminance below which a pixel becomes a dot. */
    val threshold: Int = MonoBitmap.DEFAULT_THRESHOLD,
)

/**
 * Turns dots into the bytes one printer dialect understands.
 *
 * Only ESC/POS exists today. The interface is here because Star's printers speak their own graphics
 * mode, and the transport, the rasteriser and the settings should not have to care which is in use —
 * the command set reported over IEEE 1284 can pick the driver later.
 */
interface RasterDriver {
    /** Short id, as reported by IEEE 1284 CMD: where the printer bothers to answer. */
    val commandSet: String

    fun encode(image: MonoBitmap, options: PrintOptions): ByteArray
}

/**
 * ESC/POS raster, the most widely supported way to print anything on a receipt printer.
 *
 * Raster rather than text mode on purpose. Text mode drags in code pages — which differ per clone,
 * and which is how £, € and accented characters come out as garbage — and native QR commands, which
 * many cheap printers do not implement. Sending dots sidesteps both: whatever the page looked like
 * is what prints, in any language.
 */
class EscPosRasterDriver : RasterDriver {

    companion object {
        private const val ESC = 0x1B
        private const val GS = 0x1D

        /**
         * Rows per GS v 0 command. Cheap printers have small input buffers and drop the remainder of
         * an oversized raster, so the image goes out in bands rather than one huge command.
         */
        private const val BAND_ROWS = 128
    }

    override val commandSet: String = "ESC/POS"

    override fun encode(image: MonoBitmap, options: PrintOptions): ByteArray {
        val out = ByteArrayOutputStream(image.rows.size + 64)

        // ESC @ — reset. Clears whatever mode a previous job left behind.
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))

        var row = 0
        while (row < image.height) {
            val rows = minOf(BAND_ROWS, image.height - row)
            // GS v 0 m xL xH yL yH — print raster bit image, m=0 (normal density).
            out.write(byteArrayOf(GS.toByte(), 'v'.code.toByte(), '0'.code.toByte(), 0))
            out.write(byteArrayOf(lowByte(image.bytesPerRow), highByte(image.bytesPerRow)))
            out.write(byteArrayOf(lowByte(rows), highByte(rows)))
            out.write(image.band(row, rows))
            row += rows
        }

        if (options.feedLines > 0) {
            // ESC d n — feed n lines.
            out.write(byteArrayOf(ESC.toByte(), 'd'.code.toByte(), options.feedLines.coerceIn(0, 255).toByte()))
        }
        if (options.cut) {
            // GS V 66 0 — feed and partial cut. Ignored by printers with no cutter.
            out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0))
        }
        return out.toByteArray()
    }

    private fun lowByte(value: Int): Byte = (value and 0xFF).toByte()

    private fun highByte(value: Int): Byte = ((value shr 8) and 0xFF).toByte()
}
