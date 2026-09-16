package com.freekiosk.printing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAdapterBridge
import android.print.PrintAttributes
import android.webkit.WebView
import com.freekiosk.DebugLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Turns the page the WebView is showing into printer dots, honouring its print stylesheet.
 *
 * This is what makes the feature useful to a web app that knows nothing about this kiosk: the page
 * describes its receipt in CSS (`@media print`, `@page { size: 48mm auto; margin: 0 }`) and gets
 * exactly that on paper, with its own fonts, its own language and its own QR codes. No bitmap
 * plumbing, no printer commands, no per-app integration.
 *
 * The route is the platform's own: WebView renders the page to PDF exactly as it would for the print
 * dialog, and PdfRenderer turns that into pixels at the printer's dot pitch.
 */
object PageRasterizer {

    private const val TAG = "PageRasterizer"

    /** Receipt printers are 203dpi almost universally. */
    private const val DPI = 203

    /**
     * Page height offered to the layout, in thousandths of an inch (~280mm).
     *
     * Paper is a continuous roll, which no page size can express, so we lay out on a tall page and
     * crop the blank remainder. Anything longer simply paginates, and the pages are joined back
     * together below, so the only cost of guessing is a seam.
     */
    private const val PAGE_HEIGHT_MILS = 11_000

    /** Rendering happens while a customer waits; this is a stuck-adapter guard, not a target. */
    private const val RENDER_TIMEOUT_SECONDS = 30L

    /**
     * Render [webView]'s current page. Must be called OFF the main thread: the adapter runs there,
     * and this blocks until it is done.
     */
    fun rasterize(
        context: Context,
        webView: WebView,
        jobName: String,
        options: PrintOptions,
    ): MonoBitmap {
        val pdf = File.createTempFile("freekiosk-print-", ".pdf", context.cacheDir)
        try {
            renderToPdf(webView, jobName, options.widthDots, pdf)
            return rasterizePdf(pdf, options)
        } finally {
            if (!pdf.delete()) DebugLog.w(TAG, "Could not delete ${pdf.name}")
        }
    }

    private fun renderToPdf(webView: WebView, jobName: String, widthDots: Int, target: File) {
        val attributes = PrintAttributes.Builder()
            .setMediaSize(
                // The media width IS the printable width, because margins are zero: dots back to
                // thousandths of an inch at the printer's own resolution.
                PrintAttributes.MediaSize(
                    "freekiosk_receipt",
                    "Receipt roll",
                    widthDots * 1000 / DPI,
                    PAGE_HEIGHT_MILS,
                ),
            )
            .setResolution(PrintAttributes.Resolution("thermal", "Thermal", DPI, DPI))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build()

        val latch = CountDownLatch(1)
        val failure = arrayOfNulls<String>(1)
        val cancellationSignal = CancellationSignal()

        // The adapter is a view-thread object: every call, and every callback, belongs on main.
        Handler(Looper.getMainLooper()).post {
            val adapter = try {
                webView.createPrintDocumentAdapter(jobName)
            } catch (e: Exception) {
                failure[0] = "Could not create the print adapter: ${e.message}"
                latch.countDown()
                return@post
            }

            var descriptor: ParcelFileDescriptor? = null
            val finish = { error: String? ->
                failure[0] = error
                runCatching { descriptor?.close() }
                runCatching { adapter.onFinish() }
                latch.countDown()
            }

            try {
                adapter.onStart()
                PrintAdapterBridge.layout(
                    adapter,
                    attributes,
                    cancellationSignal,
                    onFinished = { info ->
                        if (info != null && info.pageCount == 0) {
                            finish("The page produced nothing to print")
                            return@layout
                        }
                        try {
                            descriptor = ParcelFileDescriptor.open(
                                target,
                                ParcelFileDescriptor.MODE_READ_WRITE or
                                    ParcelFileDescriptor.MODE_CREATE or
                                    ParcelFileDescriptor.MODE_TRUNCATE,
                            )
                            PrintAdapterBridge.write(
                                adapter,
                                descriptor!!,
                                cancellationSignal,
                                onFinished = { finish(null) },
                                onFailed = { error -> finish("Write failed: ${error ?: "unknown"}") },
                            )
                        } catch (e: Exception) {
                            finish("Could not open the spool file: ${e.message}")
                        }
                    },
                    onFailed = { error -> finish("Layout failed: ${error ?: "unknown"}") },
                )
            } catch (e: Throwable) {
                // Includes the case where a future Android refuses the callback shim.
                finish("Print adapter refused: ${e.message}")
            }
        }

        if (!latch.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            cancellationSignal.cancel()
            throw PrinterException(
                PrinterException.PAGE_RENDER_FAILED,
                "The page took longer than ${RENDER_TIMEOUT_SECONDS}s to render",
            )
        }
        failure[0]?.let { throw PrinterException(PrinterException.PAGE_RENDER_FAILED, it) }
        if (target.length() == 0L) {
            throw PrinterException(PrinterException.PAGE_RENDER_FAILED, "The page rendered nothing")
        }
    }

    private fun rasterizePdf(pdf: File, options: PrintOptions): MonoBitmap {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) {
                    throw PrinterException(PrinterException.PAGE_RENDER_FAILED, "No pages were rendered")
                }
                val pages = ArrayList<MonoBitmap>(renderer.pageCount)
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val height = maxOf(1, page.height * options.widthDots / page.width)
                        val bitmap = Bitmap.createBitmap(
                            options.widthDots,
                            height,
                            Bitmap.Config.ARGB_8888,
                        )
                        // PdfRenderer draws onto transparency and leaves untouched areas alone, so
                        // the page has to start as white paper or everything reads as ink.
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val mono = MonoBitmap
                            .fromBitmap(bitmap, options.widthDots, options.threshold)
                            // Per page, so a receipt that spilled onto a second page does not carry
                            // the first page's empty tail into the middle of the printout.
                            .cropTrailingBlankRows()
                        bitmap.recycle()
                        if (mono.height > 0) pages.add(mono)
                    }
                }
                if (pages.isEmpty()) {
                    throw PrinterException(PrinterException.BAD_IMAGE, "Nothing to print — the page is blank")
                }
                DebugLog.d(TAG, "Rasterised ${pages.size} page(s) at ${options.widthDots} dots")
                return MonoBitmap.concat(pages)
            }
        }
    }
}
