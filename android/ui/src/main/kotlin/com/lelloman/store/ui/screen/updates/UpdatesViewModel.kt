package com.lelloman.store.ui.screen.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.download.DownloadFailureKind
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.DownloadState
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
                val visiblePackages = updates.mapTo(HashSet()) { it.packageName }
                mutableState.value = mutableState.value.copy(
                    updates = updates,
                    isLoading = false,
                    outcomes = mutableState.value.outcomes.filterKeys { it in visiblePackages },
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
        setOutcome(packageName, null)
        viewModelScope.launch {
            val outcome = when (val result = interactor.downloadAndInstall(packageName)) {
                is DownloadResult.Failed -> UpdateOutcome.Failed(result.reason, result.kind)
                DownloadResult.PermissionRequired -> UpdateOutcome.PermissionRequired
                DownloadResult.AwaitingConfirmation -> UpdateOutcome.AwaitingConfirmation
                DownloadResult.Success,
                DownloadResult.Cancelled,
                DownloadResult.UserActionRequired -> null
            }
            setOutcome(packageName, outcome)
        }
    }

    fun onGrantPermissionClick() {
        interactor.openInstallPermissionSettings()
    }

    private fun setOutcome(packageName: String, outcome: UpdateOutcome?) {
        val current = mutableState.value
        val outcomes = if (outcome == null) {
            current.outcomes - packageName
        } else {
            current.outcomes + (packageName to outcome)
        }
        mutableState.value = current.copy(outcomes = outcomes)
    }

    interface Interactor {
        fun watchUpdates(): Flow<List<UpdateUiModel>>
        suspend fun checkForUpdates(): Result<Unit>
        suspend fun downloadAndInstall(packageName: String): DownloadResult
        fun openInstallPermissionSettings()
    }
}

/** Result of the last user-started update of a package, shown inline in its row. */
sealed interface UpdateOutcome {
    data class Failed(
        val reason: String,
        val kind: DownloadFailureKind = DownloadFailureKind.GENERIC,
    ) : UpdateOutcome
    data object PermissionRequired : UpdateOutcome
    data object AwaitingConfirmation : UpdateOutcome
}

data class UpdatesScreenState(
    val updates: List<UpdateUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    /** Last outcome per package name; cleared when a new attempt for that package starts. */
    val outcomes: Map<String, UpdateOutcome> = emptyMap(),
)

data class UpdateUiModel(
    val packageName: String,
    val appName: String,
    val iconUrl: String,
    val installedVersion: String,
    val availableVersion: String,
    val updateSize: String,
    val releaseChannel: ReleaseChannel,
    val downloadState: DownloadState? = null,
    val downloadProgress: Float = 0f,
)
