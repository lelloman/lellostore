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
        refreshApp()
    }

    private fun observeApp() {
        viewModelScope.launch {
            combine(
                interactor.watchApp(packageName),
                interactor.watchInstalledVersion(packageName),
            ) { appDetail, installedApp ->
                appDetail?.let { app ->
                    createUiModel(app, installedApp)
                }
            }.collect { uiModel ->
                mutableState.value = mutableState.value.copy(
                    app = uiModel,
                    isLoading = uiModel == null && mutableState.value.error == null,
                )
            }
        }
    }

    private fun createUiModel(app: AppDetailModel, installed: InstalledAppModel?): AppDetailUiModel {
        val latestVersion = app.versions.first()
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
            latestVersion = latestVersion.toUiModel(),
            versions = app.versions.map { it.toUiModel() },
            installedVersion = installedVersionUi,
            canInstall = installed == null,
            canUpdate = installed != null && installed.versionCode < latestVersion.versionCode,
            canOpen = installed != null,
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
        viewModelScope.launch {
            mutableEvents.emit(
                AppDetailScreenEvent.InstallApk(
                    packageName = app.packageName,
                    versionCode = app.latestVersion.versionCode,
                )
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

    fun onRetry() {
        refreshApp()
    }

    interface Interactor {
        fun watchApp(packageName: String): Flow<AppDetailModel?>
        fun watchInstalledVersion(packageName: String): Flow<InstalledAppModel?>
        suspend fun refreshApp(packageName: String): Result<AppDetailModel>
    }
}
