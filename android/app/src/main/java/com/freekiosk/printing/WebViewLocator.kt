package com.freekiosk.printing

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Finds the kiosk's WebView in the view tree.
 *
 * The React Native WebView is a view, not something a native module can be handed a reference to,
 * so printing the current page means going and finding it — the same approach PrintModule takes for
 * the print dialog.
 */
internal object WebViewLocator {

    fun find(activity: Activity): WebView? = search(activity.window.decorView)

    private fun search(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                search(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
