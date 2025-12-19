package com.lelloman.store.ui.screen.catalog

import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.InstalledApp
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class CatalogInteractor @Inject constructor(
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : CatalogViewModel.Interactor {

    override fun watchApps(): Flow<List<App>> {
        return appsRepository.watchApps()
    }

    override fun watchInstalledApps(): Flow<List<InstalledApp>> {
        return installedAppsRepository.watchInstalledApps()
    }

    override suspend fun refreshApps(): Result<Unit> {
        return appsRepository.refreshApps()
    }

    override suspend fun refreshInstalledApps() {
        installedAppsRepository.refreshInstalledApps()
    }
}
