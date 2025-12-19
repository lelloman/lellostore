package com.lelloman.store.domain.config

import kotlinx.coroutines.flow.StateFlow

interface ConfigStore {
    val serverUrl: StateFlow<String>
    suspend fun setServerUrl(url: String): SetServerUrlResult

    sealed interface SetServerUrlResult {
        data object Success : SetServerUrlResult
        data object InvalidUrl : SetServerUrlResult
    }
}
