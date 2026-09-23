package com.lelloman.store.ui.screen.detail

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** A capability of the installed APK, never an Activity name received from the server. */
internal object ParavoidControls {
    private const val ACTIVITY = "com.lelloman.paravoidandroid.runtime.UpdatesLauncher"

    fun available(context: Context, packageName: String): Boolean = intent(context, packageName) != null

    fun open(context: Context, packageName: String): Boolean {
        val intent = intent(context, packageName) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun intent(context: Context, packageName: String): Intent? {
        val intent = Intent().setComponent(ComponentName(packageName, ACTIVITY))
            // Reopen the controls themselves when a payload Activity sits above the
            // exported alias in the shell's existing task.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activity = try {
            context.packageManager.resolveActivity(intent, 0)?.activityInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled ||
            activity.packageName != packageName || activity.name != ACTIVITY) return null
        if (activity.permission != null &&
            context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED) return null
        return intent
    }
}
