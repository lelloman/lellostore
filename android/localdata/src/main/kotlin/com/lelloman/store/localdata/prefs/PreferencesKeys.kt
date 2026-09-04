package com.lelloman.store.localdata.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object PreferencesKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val UPDATE_CHECK_INTERVAL = stringPreferencesKey("update_check_interval")
    val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
    val AUTO_UPDATE_DEFAULT = booleanPreferencesKey("auto_update_default")
    val RELEASE_CHANNEL_DEFAULT = stringPreferencesKey("release_channel_default")
    val INSTALLATION_CHANNELS = stringPreferencesKey("installation_channels")

    fun autoUpdateOverride(packageName: String) =
        stringPreferencesKey("app.$packageName.auto_update_override")

    fun releaseChannelOverride(packageName: String) =
        stringPreferencesKey("app.$packageName.release_channel_override")
}
