package com.lelloman.store.ui.screen.detail

import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.InstalledApp
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class AppDetailInteractor @Inject constructor(
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : AppDetailViewModel.Interactor {

    override fun watchApp(packageName: String): Flow<AppDetail?> {
        return appsRepository.watchApp(packageName)
    }

    override fun watchInstalledVersion(packageName: String): Flow<InstalledApp?> {
        return installedAppsRepository.getInstalledVersion(packageName)
    }

    override suspend fun refreshApp(packageName: String): Result<AppDetail> {
        return appsRepository.refreshApp(packageName)
    }
}
