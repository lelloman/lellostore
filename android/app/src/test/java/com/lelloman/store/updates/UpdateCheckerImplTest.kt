package com.lelloman.store.updates

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.model.InstalledApp
import com.lelloman.store.domain.preferences.AutoUpdateOverride
import com.lelloman.store.domain.preferences.AppAccessLevel
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.preferences.ReleaseChannelOverride
import com.lelloman.store.domain.preferences.ThemeMode
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.domain.preferences.UserPreferencesStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerImplTest {
    private fun recoveryGate(enabled: Boolean = false): com.lelloman.store.recovery.SelfUpdateGate = mockk {
        every { packageName } returns "com.lelloman.store"
        every { enabled() } returns enabled
    }

    @Test
    fun `availableUpdates initially empty`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testScope = CoroutineScope(testDispatcher)

        val appsFlow = MutableStateFlow<List<App>>(emptyList())
        val installedAppsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())

        val updateChecker = UpdateCheckerImpl(
            appsRepository = mockk { every { watchApps() } returns appsFlow },
            installedAppsRepository = mockk { every { watchInstalledApps() } returns installedAppsFlow },
            userPreferencesStore = createPreferences(),
            selfUpdateGate = recoveryGate(),
        )

        assertThat(updateChecker.availableUpdates.value).isEmpty()
        testScope.cancel()
    }

    @Test
    fun `finds update when installed version is older`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testScope = CoroutineScope(testDispatcher)

        val app = createApp("com.test.app", versionCode = 2)
        val installed = InstalledApp("com.test.app", versionCode = 1, versionName = "1.0")

        val appsFlow = MutableStateFlow(listOf(app))
        val installedAppsFlow = MutableStateFlow(listOf(installed))

        val updateChecker = createChecker(appsFlow, installedAppsFlow)
        updateChecker.checkForUpdates().getOrThrow()

        assertThat(updateChecker.availableUpdates.value).hasSize(1)
        val update = updateChecker.availableUpdates.value.first()
        assertThat(update.app.packageName).isEqualTo("com.test.app")
        assertThat(update.installedVersionCode).isEqualTo(1)
        testScope.cancel()
    }

    @Test
    fun `no update when installed version is current`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testScope = CoroutineScope(testDispatcher)

        val app = createApp("com.test.app", versionCode = 1)
        val installed = InstalledApp("com.test.app", versionCode = 1, versionName = "1.0")

        val appsFlow = MutableStateFlow(listOf(app))
        val installedAppsFlow = MutableStateFlow(listOf(installed))

        val updateChecker = createChecker(appsFlow, installedAppsFlow)
        updateChecker.checkForUpdates().getOrThrow()

        assertThat(updateChecker.availableUpdates.value).isEmpty()
        testScope.cancel()
    }

    @Test
    fun `no update when app not installed`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testScope = CoroutineScope(testDispatcher)

        val app = createApp("com.test.app", versionCode = 2)

        val appsFlow = MutableStateFlow(listOf(app))
        val installedAppsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())

        val updateChecker = createChecker(appsFlow, installedAppsFlow)
        updateChecker.checkForUpdates().getOrThrow()

        assertThat(updateChecker.availableUpdates.value).isEmpty()
        testScope.cancel()
    }

    @Test
    fun `checkForUpdates refreshes and returns updates`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testScope = CoroutineScope(testDispatcher)

        val app = createApp("com.test.app", versionCode = 2)
        val installed = InstalledApp("com.test.app", versionCode = 1, versionName = "1.0")

        val appsFlow = MutableStateFlow(listOf(app))
        val installedAppsFlow = MutableStateFlow(listOf(installed))

        val appsRepository: AppsRepository = mockk {
            every { watchApps() } returns appsFlow
            coEvery { refreshApps() } returns Result.success(Unit)
            coEvery { refreshApp(app.packageName) } returns Result.success(app.toDetail())
        }
        val installedAppsRepository: InstalledAppsRepository = mockk {
            every { watchInstalledApps() } returns installedAppsFlow
            coEvery { refreshInstalledApps() } returns Unit
        }

        val updateChecker = UpdateCheckerImpl(
            appsRepository = appsRepository,
            installedAppsRepository = installedAppsRepository,
            userPreferencesStore = createPreferences(),
            selfUpdateGate = recoveryGate(),
        )

        val result = updateChecker.checkForUpdates()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(1)
        testScope.cancel()
    }

    @Test
    fun `checkForUpdates returns refreshed values before background collector runs`() = runTest {
        val testScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val appsFlow = MutableStateFlow<List<App>>(emptyList())
        val installedAppsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())
        val app = createApp("com.test.app", versionCode = 2)
        val installed = InstalledApp("com.test.app", versionCode = 1, versionName = "1.0")
        val appsRepository: AppsRepository = mockk {
            every { watchApps() } returns appsFlow
            coEvery { refreshApps() } coAnswers {
                appsFlow.value = listOf(app)
                Result.success(Unit)
            }
            coEvery { refreshApp(app.packageName) } returns Result.success(app.toDetail())
        }
        val installedAppsRepository: InstalledAppsRepository = mockk {
            every { watchInstalledApps() } returns installedAppsFlow
            coEvery { refreshInstalledApps() } coAnswers {
                installedAppsFlow.value = listOf(installed)
            }
        }
        val updateChecker = UpdateCheckerImpl(
            appsRepository = appsRepository,
            installedAppsRepository = installedAppsRepository,
            userPreferencesStore = createPreferences(),
            selfUpdateGate = recoveryGate(),
        )

        val result = updateChecker.checkForUpdates()

        assertThat(result.getOrThrow()).hasSize(1)
        testScope.cancel()
    }

    @Test
    fun `checkForUpdates fails when refresh fails`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testScope = CoroutineScope(testDispatcher)

        val appsFlow = MutableStateFlow<List<App>>(emptyList())
        val installedAppsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())

        val appsRepository: AppsRepository = mockk {
            every { watchApps() } returns appsFlow
            coEvery { refreshApps() } returns Result.failure(Exception("Network error"))
        }
        val installedAppsRepository: InstalledAppsRepository = mockk {
            every { watchInstalledApps() } returns installedAppsFlow
        }

        val updateChecker = UpdateCheckerImpl(
            appsRepository = appsRepository,
            installedAppsRepository = installedAppsRepository,
            userPreferencesStore = createPreferences(),
            selfUpdateGate = recoveryGate(),
        )

        val result = updateChecker.checkForUpdates()

        assertThat(result.isFailure).isTrue()
        testScope.cancel()
    }

    @Test
    fun `stable policy selects stable release and preserves disabled auto update`() = runTest {
        val catalogApp = createApp("com.test.app", versionCode = 4).copy(
            accessLevel = AppAccessLevel.Beta,
            latestVersion = createVersion(4, beta = true),
        )
        val detail = catalogApp.toDetail().copy(
            versions = listOf(createVersion(4, beta = true), createVersion(3)),
        )
        val checker = createChecker(
            apps = MutableStateFlow(listOf(catalogApp)),
            installed = MutableStateFlow(listOf(InstalledApp("com.test.app", 1, "1"))),
            preferences = createPreferences(autoUpdate = false, channel = ReleaseChannel.Stable),
            details = mapOf(catalogApp.packageName to detail),
        )

        val update = checker.checkForUpdates().getOrThrow().single()

        assertThat(update.app.latestVersion.versionCode).isEqualTo(3)
        assertThat(update.autoUpdateEnabled).isFalse()
        assertThat(update.effectiveReleaseChannel).isEqualTo(ReleaseChannel.Stable)
    }

    @Test
    fun `beta policy selects newest stable or beta release`() = runTest {
        val catalogApp = createApp("com.test.app", versionCode = 5).copy(
            accessLevel = AppAccessLevel.Beta,
            latestVersion = createVersion(5),
        )
        val detail = catalogApp.toDetail().copy(
            versions = listOf(createVersion(5), createVersion(4, beta = true)),
        )
        val checker = createChecker(
            apps = MutableStateFlow(listOf(catalogApp)),
            installed = MutableStateFlow(listOf(InstalledApp("com.test.app", 1, "1"))),
            preferences = createPreferences(channel = ReleaseChannel.Beta),
            details = mapOf(catalogApp.packageName to detail),
        )

        val update = checker.checkForUpdates().getOrThrow().single()

        assertThat(update.app.latestVersion.versionCode).isEqualTo(5)
        assertThat(update.effectiveReleaseChannel).isEqualTo(ReleaseChannel.Beta)
    }

    private fun createApp(packageName: String, versionCode: Int): App {
        return App(
            packageName = packageName,
            name = "Test App",
            description = "Description",
            iconUrl = "https://example.com/icon.png",
            latestVersion = AppVersion(
                versionCode = versionCode,
                versionName = "$versionCode.0",
                size = 1000,
                sha256 = "abc123",
                minSdk = 21,
                uploadedAt = Instant.parse("2024-01-01T00:00:00Z"),
            ),
        )
    }

    @Test
    fun `Store update is visible but cannot run automatically before provisioning`() = runTest {
        val store = createApp("com.lelloman.store", 3)
        val checker = createChecker(MutableStateFlow(listOf(store)),
            MutableStateFlow(listOf(InstalledApp(store.packageName, 2, "1.1"))))
        val update = checker.checkForUpdates().getOrThrow().single()
        assertThat(update.app.packageName).isEqualTo(store.packageName)
        assertThat(update.autoUpdateEnabled).isFalse()
    }

    @Test
    fun `provisioned Store participates in automatic updates but companion stays excluded`() = runTest {
        val store = createApp("com.lelloman.store", 3)
        val companion = createApp("com.lelloman.store.recovery", 3)
        val checker = createChecker(MutableStateFlow(listOf(store, companion)),
            MutableStateFlow(listOf(InstalledApp(store.packageName, 2, "1.1"),
                InstalledApp(companion.packageName, 2, "1.1"))), selfEnabled = true)
        val update = checker.checkForUpdates().getOrThrow().single()
        assertThat(update.app.packageName).isEqualTo(store.packageName)
        assertThat(update.autoUpdateEnabled).isTrue()
    }

    private fun createChecker(
        apps: MutableStateFlow<List<App>>,
        installed: MutableStateFlow<List<InstalledApp>>,
        preferences: UserPreferencesStore = createPreferences(),
        details: Map<String, AppDetail> = apps.value.associate { it.packageName to it.toDetail() },
        selfEnabled: Boolean = false,
    ): UpdateCheckerImpl {
        val appsRepository: AppsRepository = mockk {
            every { watchApps() } returns apps
            coEvery { refreshApps() } returns Result.success(Unit)
            details.forEach { (packageName, detail) ->
                coEvery { refreshApp(packageName) } returns Result.success(detail)
            }
        }
        val installedRepository: InstalledAppsRepository = mockk {
            every { watchInstalledApps() } returns installed
            coEvery { refreshInstalledApps() } returns Unit
        }
        return UpdateCheckerImpl(appsRepository, installedRepository, preferences, recoveryGate(selfEnabled))
    }

    private fun App.toDetail() = AppDetail(
        packageName = packageName,
        name = name,
        description = description,
        iconUrl = iconUrl,
        versions = listOf(latestVersion),
        accessLevel = accessLevel,
    )

    private fun createVersion(code: Int, beta: Boolean = false) = AppVersion(
        versionCode = code,
        versionName = code.toString(),
        size = 1000,
        sha256 = "abc123",
        minSdk = 21,
        uploadedAt = Instant.parse("2024-01-01T00:00:00Z"),
        isBeta = beta,
    )

    private fun createPreferences(
        autoUpdate: Boolean = true,
        channel: ReleaseChannel = ReleaseChannel.Stable,
    ): UserPreferencesStore = mockk {
        every { themeMode } returns MutableStateFlow(ThemeMode.System)
        every { updateCheckInterval } returns MutableStateFlow(UpdateCheckInterval.Hours24)
        every { wifiOnlyDownloads } returns MutableStateFlow(true)
        every { autoUpdateDefault } returns MutableStateFlow(autoUpdate)
        every { releaseChannelDefault } returns MutableStateFlow(channel)
        every { autoUpdateOverride(any()) } returns MutableStateFlow(AutoUpdateOverride.Inherit)
        every { releaseChannelOverride(any()) } returns MutableStateFlow(ReleaseChannelOverride.Inherit)
    }
}
