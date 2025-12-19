package com.lelloman.store.ui.screen.catalog

data class CatalogScreenState(
    val apps: List<AppUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val filter: CatalogFilter = CatalogFilter.All,
)

data class AppUiModel(
    val packageName: String,
    val name: String,
    val iconUrl: String,
    val versionName: String,
    val isInstalled: Boolean,
    val hasUpdate: Boolean,
)

enum class CatalogFilter {
    All,
    Installed,
    Updates,
}
