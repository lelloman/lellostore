package com.lelloman.store.localdata.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.config.ConfigStore.SetServerUrlResult
import com.lelloman.store.localdata.prefs.PreferencesKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.net.URI

class ConfigStoreImpl(
    private val dataStore: DataStore<Preferences>,
    private val defaultServerUrl: String,
    scope: CoroutineScope,
) : ConfigStore {

    override val serverUrl: StateFlow<String> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.SERVER_URL] ?: defaultServerUrl }
        .stateIn(scope, SharingStarted.Eagerly, defaultServerUrl)

    override suspend fun setServerUrl(url: String): SetServerUrlResult {
        if (!isValidHttpUrl(url)) return SetServerUrlResult.InvalidUrl
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SERVER_URL] = url
        }
        return SetServerUrlResult.Success
    }

    private fun isValidHttpUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
