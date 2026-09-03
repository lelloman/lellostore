package com.lelloman.store.updates

import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AvailableUpdate
import com.lelloman.store.domain.model.InstalledApp
import com.lelloman.store.domain.updates.AppReleaseSelector
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.domain.updates.ProtectedStorePackages
import com.lelloman.store.domain.preferences.AppUpdatePolicyResolver
import com.lelloman.store.domain.preferences.UserPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateCheckerImpl @Inject constructor(
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val userPreferencesStore: UserPreferencesStore,
) : UpdateChecker {

    private val mutableUpdates = MutableStateFlow<List<AvailableUpdate>>(emptyList())
    override val availableUpdates: StateFlow<List<AvailableUpdate>> = mutableUpdates.asStateFlow()

    override suspend fun checkForUpdates(): Result<List<AvailableUpdate>> {
        return runCatching {
            appsRepository.refreshApps().getOrThrow()
            installedAppsRepository.refreshInstalledApps()
            val policyAwareUpdates = findPolicyAwareUpdates(
                appsRepository.watchApps().first(),
                installedAppsRepository.watchInstalledApps().first(),
            )
            mutableUpdates.value = policyAwareUpdates
            policyAwareUpdates
        }
    }

    private suspend fun findPolicyAwareUpdates(
        apps: List<App>,
        installed: List<InstalledApp>,
    ): List<AvailableUpdate> {
        val installedMap = installed.associateBy { it.packageName }
        return apps.mapNotNull { catalogApp ->
            if (ProtectedStorePackages.contains(catalogApp.packageName)) return@mapNotNull null
            val installedApp = installedMap[catalogApp.packageName] ?: return@mapNotNull null
            val app = appsRepository.refreshApp(catalogApp.packageName).getOrThrow()
            val autoOverride = userPreferencesStore.autoUpdateOverride(app.packageName).first()
            val channelOverride = userPreferencesStore.releaseChannelOverride(app.packageName).first()
            val policy = AppUpdatePolicyResolver.resolve(
                autoUpdateDefault = userPreferencesStore.autoUpdateDefault.value,
                releaseChannelDefault = userPreferencesStore.releaseChannelDefault.value,
                autoUpdateOverride = autoOverride,
                releaseChannelOverride = channelOverride,
                accessLevel = app.accessLevel,
                hasBetaRelease = app.versions.any { it.isBeta },
            )
            val version = AppReleaseSelector.newestUpgrade(
                versions = app.versions,
                installedVersionCode = installedApp.versionCode,
                channel = policy.effectiveChannel,
            ) ?: return@mapNotNull null

            AvailableUpdate(
                app = catalogApp.copy(latestVersion = version, accessLevel = app.accessLevel),
                installedVersionCode = installedApp.versionCode,
                installedVersionName = installedApp.versionName,
                autoUpdateEnabled = policy.autoUpdateEnabled,
                effectiveReleaseChannel = policy.effectiveChannel,
            )
        }
    }
}
