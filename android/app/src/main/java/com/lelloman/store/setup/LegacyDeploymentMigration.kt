package com.lelloman.store.setup

import android.content.Context
import com.lelloman.store.domain.auth.OidcConfig
import com.lelloman.store.domain.config.ServerMetadata

/** One-time migration only. Fresh product installs have no personal server default. */
object LegacyDeploymentMigration {
    private const val SERVER = "https://store.lelloman.com"
    private val oidc = OidcConfig("https://auth.lelloman.com",
        "22cd4a2d-a771-41e3-b76e-3f83ff8e9bbf", "com.lelloman.store:/oauth2redirect")

    // Compatibility for installations predating discovery, never a generic default.
    fun metadata(context: Context?, server: String): ServerMetadata? {
        if (context == null || server != SERVER) return null
        val previous = context.getSharedPreferences("server-selection-migration", Context.MODE_PRIVATE)
            .getString("legacy_server", "")
        return if (previous == SERVER) ServerMetadata("LelloStore", oidc, true, false) else null
    }

    fun canMigrateSession(context: Context?, server: String, saved: OidcConfig): Boolean {
        val expected = metadata(context, server)?.oidc ?: return false
        return saved.issuerUrl == expected.issuerUrl && saved.clientId == expected.clientId &&
            saved.redirectUri == expected.redirectUri
    }
    @android.annotation.SuppressLint("UseKtx") // The persisted migration must succeed before using its result.
    fun initialServer(context: Context, configuredDefault: String): String {
        val prefs = context.getSharedPreferences("server-selection-migration", Context.MODE_PRIVATE)
        if (!prefs.contains("completed")) {
            val wasConfigured = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).all.isNotEmpty()
            val legacy = if (wasConfigured) SERVER else ""
            check(prefs.edit().putBoolean("completed", true).putString("legacy_server", legacy).commit())
        }
        return configuredDefault.ifBlank { prefs.getString("legacy_server", "").orEmpty() }
    }
}
