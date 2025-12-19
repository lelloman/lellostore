package com.lelloman.store.interactor

import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.ui.model.AppModel
import com.lelloman.store.ui.model.InstalledAppModel
import com.lelloman.store.ui.screen.catalog.CatalogViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class CatalogInteractorImpl @Inject constructor(
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
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

    override suspend fun refreshApps(): Result<Unit> {
        return appsRepository.refreshApps()
    }

    override suspend fun refreshInstalledApps() {
        installedAppsRepository.refreshInstalledApps()
    }
}
