package com.lelloman.store.ui.screen.detail

import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.preferences.AutoUpdateOverride
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.preferences.ReleaseChannelOverride

data class AppDetailScreenState(
    val app: AppDetailUiModel? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val downloadState: DownloadState? = null,
    val downloadProgress: Float = 0f,
)

data class AppDetailUiModel(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val latestVersion: AppVersionUiModel?,
    val versions: List<AppVersionUiModel>,
    val installedVersion: AppVersionUiModel?,
    val canInstall: Boolean,
    val canUpdate: Boolean,
    val canOpen: Boolean,
    val autoUpdateOverride: AutoUpdateOverride,
    val releaseChannelOverride: ReleaseChannelOverride,
    val effectiveAutoUpdate: Boolean,
    val effectiveReleaseChannel: ReleaseChannel,
    val hasBetaAccess: Boolean,
    val isPolicyConfigurable: Boolean,
)

data class AppVersionUiModel(
    val versionCode: Int,
    val versionName: String,
    val size: String,
    val uploadedAt: String,
)
