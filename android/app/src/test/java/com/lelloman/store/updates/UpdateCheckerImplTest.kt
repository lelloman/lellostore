package com.lelloman.store.updates

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.model.InstalledApp
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerImplTest {

    @Test
    fun `availableUpdates initially empty`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testScope = CoroutineScope(testDispatcher)

        val appsFlow = MutableStateFlow<List<App>>(emptyList())
        val installedAppsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())

        val updateChecker = UpdateCheckerImpl(
            appsRepository = mockk { every { watchApps() } returns appsFlow },
            installedAppsRepository = mockk { every { watchInstalledApps() } returns installedAppsFlow },
            scope = testScope,
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

        val updateChecker = UpdateCheckerImpl(
            appsRepository = mockk { every { watchApps() } returns appsFlow },
            installedAppsRepository = mockk { every { watchInstalledApps() } returns installedAppsFlow },
            scope = testScope,
        )

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

        val updateChecker = UpdateCheckerImpl(
            appsRepository = mockk { every { watchApps() } returns appsFlow },
            installedAppsRepository = mockk { every { watchInstalledApps() } returns installedAppsFlow },
            scope = testScope,
        )

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

        val updateChecker = UpdateCheckerImpl(
            appsRepository = mockk { every { watchApps() } returns appsFlow },
            installedAppsRepository = mockk { every { watchInstalledApps() } returns installedAppsFlow },
            scope = testScope,
        )

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
        }
        val installedAppsRepository: InstalledAppsRepository = mockk {
            every { watchInstalledApps() } returns installedAppsFlow
            coEvery { refreshInstalledApps() } returns Unit
        }

        val updateChecker = UpdateCheckerImpl(
            appsRepository = appsRepository,
            installedAppsRepository = installedAppsRepository,
            scope = testScope,
        )

        val result = updateChecker.checkForUpdates()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(1)
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
            scope = testScope,
        )

        val result = updateChecker.checkForUpdates()

        assertThat(result.isFailure).isTrue()
        testScope.cancel()
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
}
