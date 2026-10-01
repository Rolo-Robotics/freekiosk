package com.freekiosk.payment

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.freekiosk.DebugLog

/**
 * Payment settings read natively, straight from the AsyncStorage database (same trick as
 * ScreenCapture and KioskWatchdogService). Native paths such as the REST server answer
 * without waiting on JS, so they cannot ask the JS side.
 */
object PaymentSettings {

    private const val TAG = "PaymentSettings"
    private const val KEY_ENABLED = "@kiosk_payments_enabled"

    const val REMOTE_JS_BLOCKED = "Remote JavaScript is disabled while the payment terminal bridge is enabled"

    fun isBridgeEnabled(context: Context): Boolean = try {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath("RKStorage").absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            db.rawQuery("SELECT value FROM catalystLocalStorage WHERE key = ?", arrayOf(KEY_ENABLED)).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "true"
            }
        } finally {
            db.close()
        }
    } catch (e: Exception) {
        // No database yet means nothing was ever enabled.
        DebugLog.w(TAG, "Could not read payment settings: ${e.message}")
        false
    }
}
