package com.lelloman.store.localdata.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object PreferencesKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val UPDATE_CHECK_INTERVAL = stringPreferencesKey("update_check_interval")
    val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
}
