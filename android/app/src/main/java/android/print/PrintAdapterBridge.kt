package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

/**
 * The one reason this file lives in `android.print`.
 *
 * `PrintDocumentAdapter.LayoutResultCallback` and `WriteResultCallback` are public abstract classes
 * with PACKAGE-PRIVATE constructors, so they can only be subclassed from inside this package. That
 * is deliberate on Android's part: the framework expects PrintManager to drive an adapter, which
 * always means the system print dialog.
 *
 * A kiosk cannot use that dialog — nobody is there to tap it — so the adapter has to be driven
 * directly. Everything else about printing is ordinary app code; this shim exists only to make the
 * two callbacks constructible, and deliberately contains no logic of its own.
 *
 * If a future Android blocks this, the failure is visible: the callbacks simply never fire, the
 * caller times out, and page printing reports PAGE_RENDER_FAILED. Web apps that render their own
 * bitmap and call printImage are unaffected.
 */
object PrintAdapterBridge {

    /** Lay the document out for [attributes]. Callbacks arrive on the calling (main) thread. */
    fun layout(
        adapter: PrintDocumentAdapter,
        attributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        onFinished: (info: PrintDocumentInfo?) -> Unit,
        onFailed: (error: CharSequence?) -> Unit,
    ) {
        adapter.onLayout(
            null,
            attributes,
            cancellationSignal,
            object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    onFinished(info)
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    onFailed(error)
                }

                override fun onLayoutCancelled() {
                    onFailed("Layout cancelled")
                }
            },
            null,
        )
    }

    /** Write every page of the laid-out document into [destination]. */
    fun write(
        adapter: PrintDocumentAdapter,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        onFinished: () -> Unit,
        onFailed: (error: CharSequence?) -> Unit,
    ) {
        adapter.onWrite(
            arrayOf(PageRange.ALL_PAGES),
            destination,
            cancellationSignal,
            object : PrintDocumentAdapter.WriteResultCallback() {
                override fun onWriteFinished(pages: Array<out PageRange>?) {
                    onFinished()
                }

                override fun onWriteFailed(error: CharSequence?) {
                    onFailed(error)
                }

                override fun onWriteCancelled() {
                    onFailed("Write cancelled")
                }
            },
        )
    }
}
