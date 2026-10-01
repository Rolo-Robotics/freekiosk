package com.freekiosk.payment

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Preflight checklist for taking payments on this device.
 *
 * Tap to Pay is attested by the SDK: Stripe and Adyen refuse live payments, or PIN entry,
 * when the device looks tamperable. Several of the conditions they check are things a
 * kiosk commonly has on (developer options, an accessibility service, overlays), so this
 * reports them up front instead of letting the first customer find out.
 *
 * Each check says which reader modes it matters for:
 *  - "tapToPay": the device's own NFC, attested by the SDK;
 *  - "bluetooth": external Bluetooth readers;
 *  - "all": every mode.
 *
 * This is advice, not the SDK's verdict: only the SDK knows its current rules. The
 * thresholds below are the published ones (Stripe: Android 13, Adyen: Android 12).
 */
object PaymentReadiness {

    private const val PATCH_MAX_AGE_MONTHS = 12

    fun check(context: Context, providerId: String?): JSONObject {
        val checks = JSONArray()
        fun add(id: String, ok: Boolean, appliesTo: String, detail: String) {
            checks.put(
                JSONObject().put("id", id).put("ok", ok).put("appliesTo", appliesTo).put("detail", detail)
            )
        }

        val pm = context.packageManager

        // --- Build & platform ---
        add(
            "buildSupport",
            PaymentProviders.available.isNotEmpty(),
            "all",
            if (PaymentProviders.available.isEmpty()) "This FreeKiosk build has no payment SDK (build with -Ppayments)"
            else "Providers: ${PaymentProviders.available.joinToString()}",
        )

        val minTapToPaySdk = if (providerId == "adyen") Build.VERSION_CODES.S else Build.VERSION_CODES.TIRAMISU
        add(
            "androidVersion",
            Build.VERSION.SDK_INT >= minTapToPaySdk,
            "tapToPay",
            "API ${Build.VERSION.SDK_INT}, Tap to Pay needs API $minTapToPaySdk or later",
        )

        val nfc = NfcAdapter.getDefaultAdapter(context)
        add("nfcPresent", nfc != null, "tapToPay", if (nfc == null) "No NFC hardware" else "NFC hardware present")
        add("nfcEnabled", nfc?.isEnabled == true, "tapToPay", "NFC must be switched on")

        add("gms", isInstalled(pm, "com.google.android.gms"), "tapToPay",
            "Google Mobile Services are required for device attestation")

        val hardwareKeystore = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            pm.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE, 100)
        add("hardwareKeystore", hardwareKeystore, "tapToPay", "Hardware-backed keystore (version 100+)")

        val patchAge = securityPatchAgeMonths()
        add(
            "securityPatch",
            patchAge != null && patchAge <= PATCH_MAX_AGE_MONTHS,
            "tapToPay",
            "Security patch ${Build.VERSION.SECURITY_PATCH} (must be under $PATCH_MAX_AGE_MONTHS months old)",
        )

        // --- Things the kiosk itself may have turned on ---
        val devOptions = globalInt(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
        add("developerOptionsOff", !devOptions, "tapToPay",
            "Developer options must be off (switch them off after ADB provisioning)")

        val usbDebugging = globalInt(context, Settings.Global.ADB_ENABLED) == 1
        val wifiDebugging = globalInt(context, "adb_wifi_enabled") == 1
        add("debuggingOff", !usbDebugging && !wifiDebugging, "tapToPay", "USB and wireless debugging must be off")

        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        add("releaseBuild", !debuggable, "tapToPay",
            if (debuggable) "Debug build: only simulated readers will work" else "Release build")

        val a11y = enabledAccessibilityServices(context)
        add(
            "noAccessibilityServices",
            a11y.isEmpty(),
            "tapToPay",
            if (a11y.isEmpty()) "No accessibility service running"
            else "Accessibility services block PIN entry: ${a11y.joinToString()}",
        )

        // --- Readers & location ---
        val locationGranted = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        add("locationPermission", locationGranted, "all", "Location permission is required by the SDK")

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm?.isLocationEnabled == true
        } else {
            lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }
        add("locationEnabled", locationOn, "all", "Location services must be on or payments are disabled")

        val bluetoothGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (granted(context, Manifest.permission.BLUETOOTH_SCAN) &&
                granted(context, Manifest.permission.BLUETOOTH_CONNECT))
        add("bluetoothPermission", bluetoothGranted, "bluetooth", "Bluetooth scan/connect permissions")

        fun readyFor(mode: String): Boolean {
            for (i in 0 until checks.length()) {
                val c = checks.getJSONObject(i)
                val applies = c.getString("appliesTo")
                if ((applies == "all" || applies == mode) && !c.getBoolean("ok")) return false
            }
            return true
        }

        return JSONObject()
            .put("checks", checks)
            .put("tapToPayReady", readyFor("tapToPay"))
            .put("bluetoothReady", readyFor("bluetooth"))
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun globalInt(context: Context, key: String): Int = try {
        Settings.Global.getInt(context.contentResolver, key, 0)
    } catch (e: Exception) {
        0
    }

    private fun enabledAccessibilityServices(context: Context): List<String> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return emptyList()
        return try {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun securityPatchAgeMonths(): Int? = try {
        val patch = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH)
        if (patch == null) {
            null
        } else {
            val then = Calendar.getInstance().apply { time = patch }
            val now = Calendar.getInstance()
            (now.get(Calendar.YEAR) - then.get(Calendar.YEAR)) * 12 +
                (now.get(Calendar.MONTH) - then.get(Calendar.MONTH))
        }
    } catch (e: Exception) {
        null
    }
}
