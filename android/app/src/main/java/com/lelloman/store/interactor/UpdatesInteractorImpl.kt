package com.lelloman.store.interactor

import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.model.AvailableUpdate
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.ui.screen.updates.UpdateUiModel
import com.lelloman.store.ui.screen.updates.UpdatesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UpdatesInteractorImpl @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val downloadManager: DownloadManager,
) : UpdatesViewModel.Interactor {

    override fun watchUpdates(): Flow<List<UpdateUiModel>> {
        return updateChecker.availableUpdates.map { updates ->
            updates.map { it.toUiModel() }
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

    private fun AvailableUpdate.toUiModel(): UpdateUiModel {
        return UpdateUiModel(
            packageName = app.packageName,
            appName = app.name,
            iconUrl = app.iconUrl,
            installedVersion = installedVersionName,
            availableVersion = app.latestVersion.versionName,
            updateSize = formatSize(app.latestVersion.size),
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
