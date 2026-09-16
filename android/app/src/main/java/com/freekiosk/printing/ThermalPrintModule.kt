package com.freekiosk.printing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.freekiosk.DebugLog
import java.util.concurrent.Executors

/**
 * Silent printing to a receipt printer, which Android's print framework cannot do: the system print
 * dialog is unavoidable there, print services included, so an unattended kiosk can never use it.
 *
 * The module itself holds no configuration. Every call carries its options, so the settings stay in
 * one place on the JS side (AsyncStorage, exported and backed up with everything else) instead of
 * being mirrored in two.
 */
class ThermalPrintModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "ThermalPrintModule"

        /** Refuse absurd payloads rather than trying to decode them into memory. */
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }

    override fun getName(): String = NAME

    /**
     * One job at a time. A printer is a single serial resource, and two overlapping writes would
     * interleave into one unreadable receipt.
     */
    private val executor = Executors.newSingleThreadExecutor()

    private val transport: PrinterTransport by lazy { UsbPrinterTransport(reactApplicationContext) }
    private val driver: RasterDriver = EscPosRasterDriver()

    @ReactMethod
    fun status(promise: Promise) {
        executor.execute {
            try {
                promise.resolve(statusMap(transport.status()))
            } catch (e: Exception) {
                DebugLog.errorProduction(NAME, "Status failed: ${e.message}")
                promise.reject("ERROR", "Could not read printer status: ${e.message}", e)
            }
        }
    }

    /**
     * Ask for USB access.
     *
     * A grant made this way lasts only until the printer is unplugged. The durable route is letting
     * Android make this app the default handler for the device (see UsbPrinterAttachActivity), which
     * is what keeps an unattended tablet printing across reboots and power blips.
     */
    @ReactMethod
    fun requestPermission(promise: Promise) {
        try {
            transport.requestPermission { granted -> promise.resolve(granted) }
        } catch (e: Exception) {
            DebugLog.errorProduction(NAME, "Permission request failed: ${e.message}")
            promise.reject("ERROR", "Could not request printer access: ${e.message}", e)
        }
    }

    /**
     * Print the page the WebView is showing, as its print stylesheet describes it.
     *
     * This is the zero-integration path: a web app that already prints to paper anywhere else needs
     * no code here at all, and keeps its own fonts, languages and QR codes.
     */
    @ReactMethod
    fun printPage(jobName: String?, options: ReadableMap?, promise: Promise) {
        val opts = printOptions(options)
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.reject(PrinterException.NO_WEBVIEW, "No activity is in the foreground")
            return
        }
        val webView = WebViewLocator.find(activity)
        if (webView == null) {
            promise.reject(PrinterException.NO_WEBVIEW, "No web page is being displayed")
            return
        }

        executor.execute {
            try {
                val image = PageRasterizer.rasterize(
                    reactApplicationContext,
                    webView,
                    jobName?.takeIf { it.isNotBlank() } ?: "FreeKiosk receipt",
                    opts,
                )
                transport.write(driver.encode(image, opts))
                DebugLog.d(NAME, "Printed page: ${image.width}x${image.height} dots")
                promise.resolve(true)
            } catch (e: PrinterException) {
                DebugLog.errorProduction(NAME, "Page print failed (${e.code}): ${e.message}")
                promise.reject(e.code, e.message, e)
            } catch (e: Exception) {
                DebugLog.errorProduction(NAME, "Page print failed: ${e.message}")
                promise.reject("ERROR", "Could not print the page: ${e.message}", e)
            }
        }
    }

    /** Print a PNG/JPEG, given as base64 (a `data:` prefix is tolerated). */
    @ReactMethod
    fun printImage(base64: String?, options: ReadableMap?, promise: Promise) {
        val opts = printOptions(options)
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                bitmap = decodeImage(base64)
                printBitmap(bitmap, opts)
                promise.resolve(true)
            } catch (e: PrinterException) {
                DebugLog.errorProduction(NAME, "Print failed (${e.code}): ${e.message}")
                promise.reject(e.code, e.message, e)
            } catch (e: Exception) {
                DebugLog.errorProduction(NAME, "Print failed: ${e.message}")
                promise.reject("ERROR", "Could not print: ${e.message}", e)
            } finally {
                bitmap?.recycle()
            }
        }
    }

    /** Print the built-in self-test page — no web app involved. */
    @ReactMethod
    fun printTestPage(options: ReadableMap?, promise: Promise) {
        val opts = printOptions(options)
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                val description = transport.find()
                bitmap = TestPageRenderer.render(
                    opts.widthDots,
                    description?.displayName,
                    description?.commandSet,
                )
                printBitmap(bitmap, opts)
                promise.resolve(true)
            } catch (e: PrinterException) {
                DebugLog.errorProduction(NAME, "Test page failed (${e.code}): ${e.message}")
                promise.reject(e.code, e.message, e)
            } catch (e: Exception) {
                DebugLog.errorProduction(NAME, "Test page failed: ${e.message}")
                promise.reject("ERROR", "Could not print the test page: ${e.message}", e)
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun printBitmap(bitmap: Bitmap, options: PrintOptions) {
        val mono = MonoBitmap.fromBitmap(bitmap, options.widthDots, options.threshold)
            .cropTrailingBlankRows()
        if (mono.height == 0) {
            throw PrinterException(PrinterException.BAD_IMAGE, "Nothing to print — the image is blank")
        }
        transport.write(driver.encode(mono, options))
        DebugLog.d(NAME, "Printed ${mono.width}x${mono.height} dots via ${transport.kind}")
    }

    private fun decodeImage(base64: String?): Bitmap {
        if (base64.isNullOrBlank()) {
            throw PrinterException(PrinterException.BAD_IMAGE, "No image data")
        }
        // Tolerate a data URL, since that is what canvas.toDataURL() hands a web app.
        val payload = base64.substringAfter("base64,", base64).trim()
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw PrinterException(PrinterException.BAD_IMAGE, "Image data is not valid base64", e)
        }
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw PrinterException(PrinterException.BAD_IMAGE, "Image is larger than ${MAX_IMAGE_BYTES / 1024 / 1024}MB")
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw PrinterException(PrinterException.BAD_IMAGE, "Image could not be decoded")
    }

    private fun printOptions(options: ReadableMap?): PrintOptions {
        val defaults = PrintOptions()
        if (options == null) return defaults
        return PrintOptions(
            widthDots = options.readInt("widthDots", defaults.widthDots).coerceIn(8, 4096),
            feedLines = options.readInt("feedLines", defaults.feedLines).coerceIn(0, 255),
            cut = if (options.hasKey("cut")) options.getBoolean("cut") else defaults.cut,
            threshold = options.readInt("threshold", defaults.threshold).coerceIn(1, 254),
        )
    }

    private fun ReadableMap.readInt(key: String, fallback: Int): Int =
        if (hasKey(key) && !isNull(key)) getDouble(key).toInt() else fallback

    private fun statusMap(status: PrinterStatus): WritableMap = Arguments.createMap().apply {
        putString("state", status.state.name.lowercase())
        putString("paper", status.paper.name.lowercase())
        val description = status.description
        if (description == null) {
            putNull("printer")
        } else {
            putMap(
                "printer",
                Arguments.createMap().apply {
                    putString("transport", description.kind)
                    putString("name", description.displayName)
                    putString("manufacturer", description.manufacturer)
                    putString("model", description.model)
                    putString("commandSet", description.commandSet)
                    putString("hardwareId", description.hardwareId)
                },
            )
        }
    }
}
