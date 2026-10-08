package com.lelloman.store.setup

import android.content.Context

/** One-time migration only. Fresh product installs have no personal server default. */
object LegacyDeploymentMigration {
    @android.annotation.SuppressLint("UseKtx") // The persisted migration must succeed before using its result.
    fun initialServer(context: Context, configuredDefault: String): String {
        val prefs = context.getSharedPreferences("server-selection-migration", Context.MODE_PRIVATE)
        if (!prefs.contains("completed")) {
            val wasConfigured = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).all.isNotEmpty()
            val legacy = if (wasConfigured) "https://store.lelloman.com" else ""
            check(prefs.edit().putBoolean("completed", true).putString("legacy_server", legacy).commit())
        }
        return configuredDefault.ifBlank { prefs.getString("legacy_server", "").orEmpty() }
    }
}
