package com.lelloman.store.ui.screen.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lelloman.store.ui.model.AppModel
import com.lelloman.store.ui.model.InstalledAppModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CatalogScreenState())
    val state: StateFlow<CatalogScreenState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<CatalogScreenEvent>()
    val events: SharedFlow<CatalogScreenEvent> = mutableEvents.asSharedFlow()

    init {
        observeApps()
        refresh()
    }

    private fun observeApps() {
        viewModelScope.launch {
            combine(
                interactor.watchApps(),
                interactor.watchInstalledApps(),
                mutableState,
            ) { apps, installedApps, state ->
                applyFilterAndSearch(apps, installedApps, state.filter, state.searchQuery)
            }.collect { filteredApps ->
                mutableState.value = mutableState.value.copy(apps = filteredApps)
            }
        }
    }

    private fun applyFilterAndSearch(
        apps: List<AppModel>,
        installedApps: List<InstalledAppModel>,
        filter: CatalogFilter,
        searchQuery: String,
    ): List<AppUiModel> {
        val installedMap = installedApps.associateBy { it.packageName }

        val uiModels = apps.map { app ->
            val installed = installedMap[app.packageName]
            AppUiModel(
                packageName = app.packageName,
                name = app.name,
                iconUrl = app.iconUrl,
                versionName = app.latestVersionName,
                isInstalled = installed != null,
                hasUpdate = installed != null && installed.versionCode < app.latestVersionCode,
            )
        }

        val filtered = when (filter) {
            CatalogFilter.All -> uiModels
            CatalogFilter.Installed -> uiModels.filter { it.isInstalled }
            CatalogFilter.Updates -> uiModels.filter { it.hasUpdate }
        }

        return if (searchQuery.isBlank()) {
            filtered
        } else {
            filtered.filter { app ->
                app.name.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    fun onRefresh() {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isRefreshing = true, error = null)
            interactor.refreshApps()
                .onSuccess {
                    interactor.refreshInstalledApps()
                    mutableState.value = mutableState.value.copy(isRefreshing = false)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        isRefreshing = false,
                        error = error.message ?: "Failed to refresh apps",
                    )
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query)
    }

    fun onFilterChanged(filter: CatalogFilter) {
        mutableState.value = mutableState.value.copy(filter = filter)
    }

    fun onAppClicked(app: AppUiModel) {
        viewModelScope.launch {
            mutableEvents.emit(CatalogScreenEvent.NavigateToAppDetail(app.packageName))
        }
    }

    fun onErrorDismissed() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    interface Interactor {
        fun watchApps(): Flow<List<AppModel>>
        fun watchInstalledApps(): Flow<List<InstalledAppModel>>
        suspend fun refreshApps(): Result<Unit>
        suspend fun refreshInstalledApps()
    }
}
