package com.lelloman.store.interactor

import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.domain.model.AvailableUpdate
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.ui.screen.updates.UpdateUiModel
import com.lelloman.store.ui.screen.updates.UpdatesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class UpdatesInteractorImpl @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val downloadManager: DownloadManager,
    private val installedAppsRepository: InstalledAppsRepository,
) : UpdatesViewModel.Interactor {

    override fun watchUpdates(): Flow<List<UpdateUiModel>> {
        return combine(
            updateChecker.availableUpdates,
            installedAppsRepository.watchInstalledApps(),
            downloadManager.activeDownloads,
        ) { updates, installedApps, activeDownloads ->
            val installedVersions = installedApps.associate { it.packageName to it.versionCode }
            updates.mapNotNull { update ->
                val installedVersion = installedVersions[update.app.packageName]
                    ?: return@mapNotNull null
                if (installedVersion >= update.app.latestVersion.versionCode) {
                    null
                } else {
                    update.toUiModel(activeDownloads[update.app.packageName])
                }
            }
        }
    }

    override suspend fun checkForUpdates(): Result<Unit> {
        return updateChecker.checkForUpdates().map { }
    }

    override suspend fun downloadAndInstall(packageName: String) {
        val update = updateChecker.availableUpdates.value.find { it.app.packageName == packageName }
        if (update != null) {
            downloadManager.downloadAndInstall(
                packageName = packageName,
                versionCode = update.app.latestVersion.versionCode,
            )
        }
    }

    private fun AvailableUpdate.toUiModel(progress: DownloadProgress?): UpdateUiModel {
        return UpdateUiModel(
            packageName = app.packageName,
            appName = app.name,
            iconUrl = app.iconUrl,
            installedVersion = installedVersionName,
            availableVersion = app.latestVersion.versionName,
            updateSize = formatSize(app.latestVersion.size),
            releaseChannel = effectiveReleaseChannel,
            downloadState = progress?.state,
            downloadProgress = progress?.progress ?: 0f,
        )
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
