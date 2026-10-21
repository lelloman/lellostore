package com.lelloman.store.ui.screen.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

fun Context.hasPushBatteryExemption(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/** Shared push needs network access during Doze. Some OEMs only expose the list screen. */
@SuppressLint("BatteryLife")
fun Context.openPushBatterySettings() {
    val intents = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
    )
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) { }
    }
}

@SuppressLint("BatteryLife")
private fun Context.batteryExemptionRequest() =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())

/**
 * Whether Android offers the direct "allow unrestricted battery use" prompt here. Many TV builds
 * don't; there [openPushBatterySettings] would only land on a settings list.
 */
fun Context.canRequestPushBatteryExemption(): Boolean =
    packageManager.resolveActivity(batteryExemptionRequest(), 0) != null
