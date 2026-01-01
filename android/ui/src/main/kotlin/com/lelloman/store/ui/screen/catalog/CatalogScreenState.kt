package com.lelloman.store.ui.screen.catalog

data class CatalogScreenState(
    val apps: List<AppUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val filter: CatalogFilter = CatalogFilter.All,
    val sortOption: SortOption = SortOption.NameAsc,
    val allCount: Int = 0,
    val installedCount: Int = 0,
    val updatesCount: Int = 0,
)

data class AppUiModel(
    val packageName: String,
    val name: String,
    val iconUrl: String,
    val versionName: String,
    val description: String?,
    val isInstalled: Boolean,
    val hasUpdate: Boolean,
)

enum class CatalogFilter {
    All,
    Installed,
    Updates,
}

enum class SortOption {
    NameAsc,
    NameDesc,
}
