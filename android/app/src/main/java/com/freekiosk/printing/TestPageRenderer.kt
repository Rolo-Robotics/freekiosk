package com.freekiosk.printing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The self-test print: proves the transport, the driver and the paper width in one page, with no web
 * app involved. It is the first thing to run against an unfamiliar printer.
 *
 * What each part is for:
 *  - the ruler tells you whether the configured dot width matches the paper (a wrong width wraps or
 *    leaves a margin);
 *  - the currency and accent line is where a printer that ignored the raster and fell back to text
 *    would show mojibake;
 *  - the grey ramp shows where the threshold lands;
 *  - the hairlines confirm single-dot rows survive.
 */
object TestPageRenderer {

    /** Generous canvas; the blank remainder is cropped before printing. */
    private const val CANVAS_HEIGHT = 1_400

    fun render(widthDots: Int, printerName: String?, commandSet: String?): Bitmap {
        val bitmap = Bitmap.createBitmap(widthDots, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 22f
            typeface = Typeface.MONOSPACE
        }
        val title = Paint(body).apply {
            textSize = 30f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val rule = Paint().apply { color = Color.BLACK }

        var y = 34f
        canvas.drawText("FreeKiosk", 0f, y, title)
        y += 34f
        canvas.drawText("Printer test page", 0f, y, title)
        y += 30f

        canvas.drawRect(0f, y, widthDots.toFloat(), y + 3f, rule)
        y += 26f

        canvas.drawText("Width: $widthDots dots", 0f, y, body)
        y += 26f
        canvas.drawText("Printer: ${printerName ?: "unknown"}", 0f, y, body)
        y += 26f
        canvas.drawText("Commands: ${commandSet ?: "unreported"}", 0f, y, body)
        y += 26f
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        canvas.drawText(stamp, 0f, y, body)
        y += 32f

        // Column ruler: as many characters as fit, so a mis-set width is obvious at a glance.
        val columns = (widthDots / body.measureText("0")).toInt()
        val ruler = StringBuilder()
        for (i in 1..columns) ruler.append(if (i % 10 == 0) "|" else (i % 10).toString())
        canvas.drawText(ruler.toString(), 0f, y, body)
        y += 26f
        canvas.drawText("$columns columns at ${body.textSize.toInt()}px", 0f, y, body)
        y += 32f

        canvas.drawText("Currency: £10.50 €9,99 \$7.25", 0f, y, body)
        y += 26f
        canvas.drawText("Accents: café ijsje größe niño", 0f, y, body)
        y += 32f

        // Grey ramp — everything darker than the threshold becomes solid black.
        val swatch = widthDots / 8f
        for (i in 0 until 8) {
            val level = 255 - i * 32
            canvas.drawRect(
                i * swatch,
                y,
                (i + 1) * swatch,
                y + 40f,
                Paint().apply { color = Color.rgb(level, level, level) },
            )
        }
        y += 68f

        // Hairlines: one-dot rows, which is what fine table rules on a receipt come down to.
        for (i in 0 until 3) {
            canvas.drawRect(0f, y, widthDots.toFloat(), y + 1f, rule)
            y += 8f
        }
        y += 24f

        canvas.drawText("If this is legible, printing works.", 0f, y, body)
        return bitmap
    }
}
