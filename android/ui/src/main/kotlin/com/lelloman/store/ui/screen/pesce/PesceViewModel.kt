package com.lelloman.store.ui.screen.pesce

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.remote.RemoteAppChoice
import com.lelloman.store.domain.remote.RemoteDeviceOperations
import com.lelloman.store.domain.remote.RemoteDeviceSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PescePickerState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val apps: List<RemoteAppChoice> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val error: String? = null,
)

sealed interface PesceAction {
    data object Refresh : PesceAction
    data class Connect(val id: String) : PesceAction
    data object Disconnect : PesceAction
    data object Copy : PesceAction
    data object Launch : PesceAction
    data object EnableTcp : PesceAction
    data object DisableTcp : PesceAction
    data object TestTcp : PesceAction
    data object PickApps : PesceAction
    data object ClosePicker : PesceAction
    data class Search(val query: String) : PesceAction
    data class Select(val pkg: String) : PesceAction
    data object Install : PesceAction
    data object Resume : PesceAction
    data object Cancel : PesceAction
    data object Clear : PesceAction
}

@HiltViewModel
class PesceViewModel @Inject constructor(
    private val session: RemoteDeviceSession,
    private val operations: RemoteDeviceOperations,
    auth: AuthStore,
) : ViewModel() {
    val connection = session.state
    val authState = auth.authState
    private val mutablePicker = MutableStateFlow(PescePickerState())
    val picker = mutablePicker.asStateFlow()

    init { session.refreshDevices() }

    fun act(action: PesceAction) {
        when (action) {
            PesceAction.Refresh -> session.refreshDevices()
            is PesceAction.Connect -> session.connect(action.id)
            PesceAction.Disconnect -> session.disconnect()
            PesceAction.Copy -> operations.copyStore()
            PesceAction.Launch -> operations.launchStore()
            PesceAction.EnableTcp -> operations.enableTcpIp()
            PesceAction.DisableTcp -> operations.disableTcpIp()
            PesceAction.TestTcp -> operations.testTcpIp()
            PesceAction.PickApps -> {
                mutablePicker.update { it.copy(open = true) }
                refreshCatalog()
            }
            PesceAction.ClosePicker -> mutablePicker.update { it.copy(open = false) }
            is PesceAction.Search -> mutablePicker.update { it.copy(query = action.query) }
            is PesceAction.Select -> mutablePicker.update {
                it.copy(selected = if (action.pkg in it.selected) it.selected - action.pkg else it.selected + action.pkg)
            }
            PesceAction.Install -> {
                val state = picker.value
                operations.installApps(state.apps.filter { it.packageName in state.selected })
                mutablePicker.update { it.copy(open = false, selected = emptySet()) }
            }
            PesceAction.Resume -> operations.resumeBatch()
            PesceAction.Cancel -> operations.cancel()
            PesceAction.Clear -> operations.clearResults()
        }
    }

    fun refreshCatalog() {
        if (picker.value.loading) return
        mutablePicker.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            operations.loadCatalog().fold(
                onSuccess = { apps -> mutablePicker.update { it.copy(loading = false, apps = apps, selected = it.selected.intersect(apps.map { app -> app.packageName }.toSet())) } },
                onFailure = { error -> mutablePicker.update { it.copy(loading = false, error = error.message) } },
            )
        }
    }
}
