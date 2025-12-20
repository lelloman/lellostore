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
                processApps(apps, installedApps, state)
            }.collect { result ->
                mutableState.value = mutableState.value.copy(
                    apps = result.filteredApps,
                    allCount = result.allCount,
                    installedCount = result.installedCount,
                    updatesCount = result.updatesCount,
                )
            }
        }
    }

    private data class ProcessedApps(
        val filteredApps: List<AppUiModel>,
        val allCount: Int,
        val installedCount: Int,
        val updatesCount: Int,
    )

    private fun processApps(
        apps: List<AppModel>,
        installedApps: List<InstalledAppModel>,
        state: CatalogScreenState,
    ): ProcessedApps {
        val installedMap = installedApps.associateBy { it.packageName }

        val uiModels = apps.map { app ->
            val installed = installedMap[app.packageName]
            AppUiModel(
                packageName = app.packageName,
                name = app.name,
                iconUrl = app.iconUrl,
                versionName = app.latestVersionName,
                description = app.description,
                isInstalled = installed != null,
                hasUpdate = installed != null && installed.versionCode < app.latestVersionCode,
            )
        }

        // Calculate counts before filtering
        val allCount = uiModels.size
        val installedCount = uiModels.count { it.isInstalled }
        val updatesCount = uiModels.count { it.hasUpdate }

        // Apply filter
        val filtered = when (state.filter) {
            CatalogFilter.All -> uiModels
            CatalogFilter.Installed -> uiModels.filter { it.isInstalled }
            CatalogFilter.Updates -> uiModels.filter { it.hasUpdate }
        }

        // Apply search
        val searched = if (state.searchQuery.isBlank()) {
            filtered
        } else {
            filtered.filter { app ->
                app.name.contains(state.searchQuery, ignoreCase = true) ||
                    app.packageName.contains(state.searchQuery, ignoreCase = true)
            }
        }

        // Apply sort
        val sorted = when (state.sortOption) {
            SortOption.NameAsc -> searched.sortedBy { it.name.lowercase() }
            SortOption.NameDesc -> searched.sortedByDescending { it.name.lowercase() }
        }

        return ProcessedApps(
            filteredApps = sorted,
            allCount = allCount,
            installedCount = installedCount,
            updatesCount = updatesCount,
        )
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

    fun onClearSearch() {
        mutableState.value = mutableState.value.copy(searchQuery = "")
    }

    fun onFilterChanged(filter: CatalogFilter) {
        mutableState.value = mutableState.value.copy(filter = filter)
    }

    fun onSortOptionChanged(sortOption: SortOption) {
        mutableState.value = mutableState.value.copy(sortOption = sortOption)
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
