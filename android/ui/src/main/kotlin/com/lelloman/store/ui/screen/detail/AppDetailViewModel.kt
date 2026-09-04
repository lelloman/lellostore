package com.lelloman.store.ui.screen.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lelloman.store.ui.model.AppDetailModel
import com.lelloman.store.ui.model.AppVersionModel
import com.lelloman.store.ui.model.InstalledAppModel
import com.lelloman.store.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.preferences.AppAccessLevel
import com.lelloman.store.domain.preferences.AppUpdatePolicyResolver
import com.lelloman.store.domain.preferences.AutoUpdateOverride
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.preferences.ReleaseChannelOverride
import com.lelloman.store.domain.updates.ProtectedStorePackages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val interactor: Interactor,
) : ViewModel() {

    private val packageName: String = savedStateHandle.toRoute<Screen.AppDetail>().packageName

    private val mutableState = MutableStateFlow(AppDetailScreenState())
    val state: StateFlow<AppDetailScreenState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AppDetailScreenEvent>()
    val events: SharedFlow<AppDetailScreenEvent> = mutableEvents.asSharedFlow()

    init {
        observeApp()
        observeDownloadProgress()
        refreshApp()
        refreshInstalledApp()
    }

    private fun observeApp() {
        viewModelScope.launch {
            val preferenceInputs = combine(
                interactor.autoUpdateDefault(),
                interactor.releaseChannelDefault(),
                interactor.autoUpdateOverride(packageName),
                interactor.releaseChannelOverride(packageName),
            ) { autoDefault, channelDefault, autoOverride, channelOverride ->
                PreferenceInputs(autoDefault, channelDefault, autoOverride, channelOverride)
            }
            combine(
                interactor.watchApp(packageName),
                interactor.watchInstalledVersion(packageName),
                preferenceInputs,
            ) { appDetail, installedApp, preferences ->
                appDetail?.let { app ->
                    createUiModel(app, installedApp, preferences)
                }
            }.collect { uiModel ->
                mutableState.value = mutableState.value.copy(
                    app = uiModel,
                    isLoading = uiModel == null && mutableState.value.error == null,
                )
            }
        }
    }

    private fun observeDownloadProgress() {
        viewModelScope.launch {
            interactor.watchDownloadProgress(packageName).collect { progress ->
                mutableState.value = mutableState.value.copy(
                    downloadState = progress?.state,
                    downloadProgress = progress?.progress ?: 0f,
                )
            }
        }
    }

    private fun createUiModel(
        app: AppDetailModel,
        installed: InstalledAppModel?,
        preferences: PreferenceInputs,
    ): AppDetailUiModel {
        val policy = AppUpdatePolicyResolver.resolve(
            autoUpdateDefault = preferences.autoUpdateDefault,
            releaseChannelDefault = preferences.releaseChannelDefault,
            autoUpdateOverride = preferences.autoUpdateOverride,
            releaseChannelOverride = preferences.releaseChannelOverride,
            accessLevel = app.accessLevel,
            hasBetaRelease = app.versions.any { it.isBeta },
        )
        val channelVersions = when (policy.effectiveChannel) {
            ReleaseChannel.Stable -> app.versions.filterNot { it.isBeta }
            ReleaseChannel.Beta -> app.versions
        }
        val latestVersion = channelVersions.maxByOrNull { it.versionCode }
        val installedVersionUi = installed?.let { inst ->
            app.versions.find { it.versionCode == inst.versionCode }?.toUiModel()
                ?: AppVersionUiModel(
                    versionCode = inst.versionCode,
                    versionName = inst.versionName,
                    size = "",
                    uploadedAt = "",
                )
        }

        return AppDetailUiModel(
            packageName = app.packageName,
            name = app.name,
            description = app.description,
            iconUrl = app.iconUrl,
            latestVersion = latestVersion?.toUiModel(),
            versions = app.versions.map { it.toUiModel() },
            installedVersion = installedVersionUi,
            canInstall = installed == null && latestVersion != null,
            canUpdate = installed != null &&
                latestVersion != null &&
                installed.versionCode < latestVersion.versionCode,
            canOpen = installed != null,
            autoUpdateOverride = preferences.autoUpdateOverride,
            releaseChannelOverride = preferences.releaseChannelOverride,
            effectiveAutoUpdate = policy.autoUpdateEnabled,
            effectiveReleaseChannel = policy.effectiveChannel,
            hasBetaAccess = app.accessLevel == AppAccessLevel.Beta,
            isPolicyConfigurable = !ProtectedStorePackages.contains(app.packageName),
        )
    }

    private fun AppVersionModel.toUiModel(): AppVersionUiModel {
        return AppVersionUiModel(
            versionCode = versionCode,
            versionName = versionName,
            size = formatSize(size),
            uploadedAt = formatDate(uploadedAtMillis),
        )
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    private fun formatDate(millis: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return dateFormat.format(Date(millis))
    }

    private fun refreshApp() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)
            interactor.refreshApp(packageName)
                .onSuccess {
                    mutableState.value = mutableState.value.copy(isLoading = false)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load app details",
                    )
                }
        }
    }

    fun onInstallClick() {
        val app = mutableState.value.app ?: return
        val latestVersion = app.latestVersion ?: return
        val currentDownloadState = mutableState.value.downloadState

        // If download is in progress, cancel it
        if (currentDownloadState != null && currentDownloadState != DownloadState.COMPLETED &&
            currentDownloadState != DownloadState.FAILED && currentDownloadState != DownloadState.CANCELLED) {
            interactor.cancelDownload(packageName)
            return
        }

        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(installationFailure = null)
            val result = interactor.downloadAndInstall(
                packageName = app.packageName,
                versionCode = latestVersion.versionCode,
            )
            mutableState.value = mutableState.value.copy(
                installationFailure = result as? DownloadResult.Failed,
            )
        }
    }

    fun onUpdateClick() {
        onInstallClick() // Same action as install
    }

    fun onOpenClick() {
        viewModelScope.launch {
            mutableEvents.emit(AppDetailScreenEvent.OpenApp(packageName))
        }
    }

    fun onCancelDownload() {
        interactor.cancelDownload(packageName)
    }

    fun onRetry() {
        refreshApp()
    }

    fun onResume() {
        refreshInstalledApp()
    }

    private fun refreshInstalledApp() {
        viewModelScope.launch {
            interactor.refreshInstalledApp(packageName)
        }
    }

    fun onGrantPermissionClick() {
        interactor.openInstallPermissionSettings()
    }

    fun onAutoUpdateOverrideChanged(override: AutoUpdateOverride) {
        if (mutableState.value.app?.isPolicyConfigurable != true) return
        viewModelScope.launch { interactor.setAutoUpdateOverride(packageName, override) }
    }

    fun onReleaseChannelOverrideChanged(override: ReleaseChannelOverride) {
        if (mutableState.value.app?.isPolicyConfigurable != true) return
        if (override == ReleaseChannelOverride.Beta && mutableState.value.app?.hasBetaAccess != true) return
        viewModelScope.launch { interactor.setReleaseChannelOverride(packageName, override) }
    }

    interface Interactor {
        fun watchApp(packageName: String): Flow<AppDetailModel?>
        fun watchInstalledVersion(packageName: String): Flow<InstalledAppModel?>
        fun watchDownloadProgress(packageName: String): Flow<DownloadProgress?>
        fun autoUpdateDefault(): StateFlow<Boolean>
        fun releaseChannelDefault(): StateFlow<ReleaseChannel>
        fun autoUpdateOverride(packageName: String): Flow<AutoUpdateOverride>
        fun releaseChannelOverride(packageName: String): Flow<ReleaseChannelOverride>
        suspend fun refreshApp(packageName: String): Result<AppDetailModel>
        suspend fun refreshInstalledApp(packageName: String)
        suspend fun downloadAndInstall(packageName: String, versionCode: Int): DownloadResult
        fun cancelDownload(packageName: String)
        fun canInstallPackages(): Boolean
        fun openInstallPermissionSettings()
        suspend fun setAutoUpdateOverride(packageName: String, override: AutoUpdateOverride)
        suspend fun setReleaseChannelOverride(packageName: String, override: ReleaseChannelOverride)
    }

    private data class PreferenceInputs(
        val autoUpdateDefault: Boolean,
        val releaseChannelDefault: ReleaseChannel,
        val autoUpdateOverride: AutoUpdateOverride,
        val releaseChannelOverride: ReleaseChannelOverride,
    )
}
