package com.lelloman.store.ui.screen.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel() {

    private val mutableState = MutableStateFlow(UpdatesScreenState())
    val state: StateFlow<UpdatesScreenState> = mutableState.asStateFlow()

    init {
        observeUpdates()
        refreshUpdates()
    }

    private fun observeUpdates() {
        viewModelScope.launch {
            interactor.watchUpdates().collect { updates ->
                mutableState.value = mutableState.value.copy(
                    updates = updates,
                    isLoading = false,
                )
            }
        }
    }

    fun refreshUpdates() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isRefreshing = true, error = null)
            interactor.checkForUpdates()
                .onSuccess {
                    mutableState.value = mutableState.value.copy(isRefreshing = false)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        isRefreshing = false,
                        error = error.message ?: "Failed to check for updates",
                    )
                }
        }
    }

    fun onUpdateClick(packageName: String) {
        viewModelScope.launch {
            interactor.downloadAndInstall(packageName)
        }
    }

    interface Interactor {
        fun watchUpdates(): Flow<List<UpdateUiModel>>
        suspend fun checkForUpdates(): Result<Unit>
        suspend fun downloadAndInstall(packageName: String)
    }
}

data class UpdatesScreenState(
    val updates: List<UpdateUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

data class UpdateUiModel(
    val packageName: String,
    val appName: String,
    val iconUrl: String,
    val installedVersion: String,
    val availableVersion: String,
    val updateSize: String,
)
