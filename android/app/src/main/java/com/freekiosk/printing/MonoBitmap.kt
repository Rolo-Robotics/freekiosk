package com.freekiosk.printing

import android.graphics.Bitmap

/**
 * A one-bit-per-pixel image, in the row-major, MSB-first layout every raster printer command wants.
 * A set bit is a dot the printer burns (black).
 *
 * Thermal printers have no grey: the conversion from an anti-aliased page to dots happens here,
 * once, so the driver only ever deals in bits.
 */
class MonoBitmap(
    val width: Int,
    val height: Int,
    val rows: ByteArray,
) {
    val bytesPerRow: Int = bytesPerRow(width)

    init {
        val expected = bytesPerRow * height
        require(rows.size == expected) { "Expected $expected bytes for ${width}x$height, got ${rows.size}" }
    }

    /** The bytes for [rowCount] rows starting at [startRow], as one raster band. */
    fun band(startRow: Int, rowCount: Int): ByteArray {
        val from = startRow * bytesPerRow
        val to = (startRow + rowCount) * bytesPerRow
        return rows.copyOfRange(from, to)
    }

    /** Is every bit in this row clear (i.e. blank paper)? */
    private fun isRowBlank(row: Int): Boolean {
        val from = row * bytesPerRow
        for (i in from until from + bytesPerRow) {
            if (rows[i].toInt() != 0) return false
        }
        return true
    }

    /**
     * Drop blank rows from the bottom.
     *
     * Load-bearing for page printing: a receipt is laid out on a page whose height we had to pick in
     * advance, so whatever we choose is mostly empty. Printing that emptiness would feed — and on a
     * cutter, waste — a long strip of blank paper after every receipt.
     */
    fun cropTrailingBlankRows(): MonoBitmap {
        var lastInked = height - 1
        while (lastInked >= 0 && isRowBlank(lastInked)) lastInked--
        if (lastInked == height - 1) return this
        val newHeight = lastInked + 1
        if (newHeight <= 0) return MonoBitmap(width, 0, ByteArray(0))
        return MonoBitmap(width, newHeight, rows.copyOfRange(0, newHeight * bytesPerRow))
    }

    companion object {
        fun bytesPerRow(width: Int): Int = (width + 7) / 8

        /**
         * Stack images top to bottom. Used to rejoin a receipt that the page layout split across
         * pages, so it prints as the single continuous strip the paper actually is.
         */
        fun concat(parts: List<MonoBitmap>): MonoBitmap {
            require(parts.isNotEmpty()) { "Nothing to join" }
            if (parts.size == 1) return parts.first()
            val width = parts.first().width
            require(parts.all { it.width == width }) { "Every part must share a width" }

            val height = parts.sumOf { it.height }
            val rows = ByteArray(bytesPerRow(width) * height)
            var offset = 0
            for (part in parts) {
                part.rows.copyInto(rows, offset)
                offset += part.rows.size
            }
            return MonoBitmap(width, height, rows)
        }

        /** Default luminance cut. Anti-aliased text keeps its shape either side of the middle. */
        const val DEFAULT_THRESHOLD = 128

        /**
         * Convert an ordinary bitmap to dots at exactly [targetWidth].
         *
         * Wider images are scaled down (never up — enlarging only blurs text into the threshold),
         * narrower ones are centred. Transparency is composited onto white first, because a page
         * with a transparent background would otherwise come out as a solid black rectangle.
         */
        fun fromBitmap(
            source: Bitmap,
            targetWidth: Int,
            threshold: Int = DEFAULT_THRESHOLD,
        ): MonoBitmap {
            require(targetWidth > 0) { "targetWidth must be positive" }
            if (source.width <= 0 || source.height <= 0) {
                throw PrinterException(PrinterException.BAD_IMAGE, "Image has no pixels")
            }

            val scaled = if (source.width > targetWidth) {
                val height = maxOf(1, (source.height.toLong() * targetWidth / source.width).toInt())
                Bitmap.createScaledBitmap(source, targetWidth, height, true)
            } else {
                source
            }
            val xOffset = (targetWidth - scaled.width) / 2
            val height = scaled.height
            val bytesPerRow = bytesPerRow(targetWidth)
            val rows = ByteArray(bytesPerRow * height)
            val line = IntArray(scaled.width)

            for (y in 0 until height) {
                scaled.getPixels(line, 0, scaled.width, 0, y, scaled.width, 1)
                val rowStart = y * bytesPerRow
                for (x in 0 until scaled.width) {
                    if (isInk(line[x], threshold)) {
                        val dot = x + xOffset
                        if (dot in 0 until targetWidth) {
                            rows[rowStart + (dot shr 3)] =
                                (rows[rowStart + (dot shr 3)].toInt() or (0x80 shr (dot and 7))).toByte()
                        }
                    }
                }
            }

            if (scaled !== source) scaled.recycle()
            return MonoBitmap(targetWidth, height, rows)
        }

        private fun isInk(pixel: Int, threshold: Int): Boolean {
            val alpha = (pixel ushr 24) and 0xFF
            val red = (pixel ushr 16) and 0xFF
            val green = (pixel ushr 8) and 0xFF
            val blue = pixel and 0xFF
            // Rec. 601 luma, then composited over white so a transparent pixel reads as paper.
            val luma = (red * 299 + green * 587 + blue * 114) / 1000
            val overWhite = (luma * alpha + 255 * (255 - alpha)) / 255
            return overWhite < threshold
        }
    }
}
