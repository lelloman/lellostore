package com.lelloman.store.interactor

import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.ui.model.AppModel
import com.lelloman.store.ui.model.InstalledAppModel
import com.lelloman.store.ui.screen.catalog.CatalogViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class CatalogInteractorImpl @Inject constructor(
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val downloadManager: DownloadManager,
) : CatalogViewModel.Interactor {

    override fun watchApps(): Flow<List<AppModel>> {
        return appsRepository.watchApps().map { apps ->
            apps.map { app ->
                AppModel(
                    packageName = app.packageName,
                    name = app.name,
                    description = app.description,
                    iconUrl = app.iconUrl,
                    latestVersionCode = app.latestVersion.versionCode,
                    latestVersionName = app.latestVersion.versionName,
                    latestVersionUploadedAtMillis = app.latestVersion.uploadedAt.toEpochMilliseconds(),
                )
            }
        }
    }

    override fun watchInstalledApps(): Flow<List<InstalledAppModel>> {
        return installedAppsRepository.watchInstalledApps().map { apps ->
            apps.map { app ->
                InstalledAppModel(
                    packageName = app.packageName,
                    versionCode = app.versionCode,
                    versionName = app.versionName,
                )
            }
        }
    }

    override fun watchUpdatingPackages(): Flow<Set<String>> {
        return downloadManager.activeDownloads.map { downloads ->
            downloads.values
                .filter { it.state.isInProgress }
                .mapTo(mutableSetOf()) { it.packageName }
        }
    }

    override suspend fun refreshApps(): Result<Unit> {
        return appsRepository.refreshApps()
    }

    override suspend fun refreshInstalledApps() {
        installedAppsRepository.refreshInstalledApps()
    }
}

private val DownloadState.isInProgress: Boolean
    get() = this != DownloadState.COMPLETED &&
        this != DownloadState.FAILED &&
        this != DownloadState.CANCELLED &&
        this != DownloadState.PERMISSION_REQUIRED
