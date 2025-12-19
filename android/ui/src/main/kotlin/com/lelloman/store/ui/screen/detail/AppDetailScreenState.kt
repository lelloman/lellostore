package com.lelloman.store.ui.screen.detail

data class AppDetailScreenState(
    val app: AppDetailUiModel? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class AppDetailUiModel(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val latestVersion: AppVersionUiModel,
    val versions: List<AppVersionUiModel>,
    val installedVersion: AppVersionUiModel?,
    val canInstall: Boolean,
    val canUpdate: Boolean,
    val canOpen: Boolean,
)

data class AppVersionUiModel(
    val versionCode: Int,
    val versionName: String,
    val size: String,
    val uploadedAt: String,
)
