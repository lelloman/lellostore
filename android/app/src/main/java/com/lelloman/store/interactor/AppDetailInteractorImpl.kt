package com.lelloman.store.interactor

import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.ui.model.AppDetailModel
import com.lelloman.store.ui.model.AppVersionModel
import com.lelloman.store.ui.model.InstalledAppModel
import com.lelloman.store.ui.screen.detail.AppDetailViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AppDetailInteractorImpl @Inject constructor(
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
) : AppDetailViewModel.Interactor {

    override fun watchApp(packageName: String): Flow<AppDetailModel?> {
        return appsRepository.watchApp(packageName).map { appDetail ->
            appDetail?.toUiModel()
        }
    }

    override fun watchInstalledVersion(packageName: String): Flow<InstalledAppModel?> {
        return installedAppsRepository.getInstalledVersion(packageName).map { installed ->
            installed?.let {
                InstalledAppModel(
                    packageName = it.packageName,
                    versionCode = it.versionCode,
                    versionName = it.versionName,
                )
            }
        }
    }

    override suspend fun refreshApp(packageName: String): Result<AppDetailModel> {
        return appsRepository.refreshApp(packageName).map { it.toUiModel() }
    }

    private fun AppDetail.toUiModel(): AppDetailModel {
        return AppDetailModel(
            packageName = packageName,
            name = name,
            description = description,
            iconUrl = iconUrl,
            versions = versions.map { version ->
                AppVersionModel(
                    versionCode = version.versionCode,
                    versionName = version.versionName,
                    size = version.size,
                    uploadedAtMillis = version.uploadedAt.toEpochMilliseconds(),
                )
            },
        )
    }
}
