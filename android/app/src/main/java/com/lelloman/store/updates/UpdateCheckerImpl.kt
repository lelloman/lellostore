package com.lelloman.store.updates

import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AvailableUpdate
import com.lelloman.store.domain.model.InstalledApp
import com.lelloman.store.domain.updates.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateCheckerImpl @Inject constructor(
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : UpdateChecker {

    private val mutableUpdates = MutableStateFlow<List<AvailableUpdate>>(emptyList())
    override val availableUpdates: StateFlow<List<AvailableUpdate>> = mutableUpdates.asStateFlow()

    init {
        scope.launch {
            combine(
                appsRepository.watchApps(),
                installedAppsRepository.watchInstalledApps(),
            ) { apps, installed ->
                findUpdates(apps, installed)
            }.collect { updates ->
                mutableUpdates.value = updates
            }
        }
    }

    override suspend fun checkForUpdates(): Result<List<AvailableUpdate>> {
        return runCatching {
            appsRepository.refreshApps().getOrThrow()
            installedAppsRepository.refreshInstalledApps()
            availableUpdates.value
        }
    }

    private fun findUpdates(
        apps: List<App>,
        installed: List<InstalledApp>,
    ): List<AvailableUpdate> {
        val installedMap = installed.associateBy { it.packageName }

        return apps.mapNotNull { app ->
            val installedApp = installedMap[app.packageName] ?: return@mapNotNull null

            if (app.latestVersion.versionCode > installedApp.versionCode) {
                AvailableUpdate(
                    app = app,
                    installedVersionCode = installedApp.versionCode,
                    installedVersionName = installedApp.versionName,
                )
            } else null
        }
    }
}
