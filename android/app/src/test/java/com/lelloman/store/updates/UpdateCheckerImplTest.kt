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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var updateCheckerScope: CoroutineScope
    private lateinit var updateCheckerJob: Job

    private lateinit var appsRepository: AppsRepository
    private lateinit var installedAppsRepository: InstalledAppsRepository
    private lateinit var updateChecker: UpdateCheckerImpl

    private val appsFlow = MutableStateFlow<List<App>>(emptyList())
    private val installedAppsFlow = MutableStateFlow<List<InstalledApp>>(emptyList())

    @Before
    fun setup() {
        appsRepository = mockk()
        installedAppsRepository = mockk()

        every { appsRepository.watchApps() } returns appsFlow
        every { installedAppsRepository.watchInstalledApps() } returns installedAppsFlow

        updateCheckerJob = Job()
        updateCheckerScope = CoroutineScope(testDispatcher + updateCheckerJob)

        updateChecker = UpdateCheckerImpl(
            appsRepository = appsRepository,
            installedAppsRepository = installedAppsRepository,
            scope = updateCheckerScope,
        )
    }

    @After
    fun tearDown() {
        updateCheckerJob.cancel()
    }

    @Test
    fun `availableUpdates initially empty`() = testScope.runTest {
        assertThat(updateChecker.availableUpdates.value).isEmpty()
    }

    @Test
    fun `finds update when installed version is older`() = testScope.runTest {
        val app = createApp("com.test.app", versionCode = 2)
        val installed = InstalledApp("com.test.app", versionCode = 1, versionName = "1.0")

        appsFlow.value = listOf(app)
        installedAppsFlow.value = listOf(installed)

        advanceUntilIdle()

        assertThat(updateChecker.availableUpdates.value).hasSize(1)
        val update = updateChecker.availableUpdates.value.first()
        assertThat(update.app.packageName).isEqualTo("com.test.app")
        assertThat(update.installedVersionCode).isEqualTo(1)
    }

    @Test
    fun `no update when installed version is current`() = testScope.runTest {
        val app = createApp("com.test.app", versionCode = 1)
        val installed = InstalledApp("com.test.app", versionCode = 1, versionName = "1.0")

        appsFlow.value = listOf(app)
        installedAppsFlow.value = listOf(installed)

        advanceUntilIdle()

        assertThat(updateChecker.availableUpdates.value).isEmpty()
    }

    @Test
    fun `no update when app not installed`() = testScope.runTest {
        val app = createApp("com.test.app", versionCode = 2)

        appsFlow.value = listOf(app)
        installedAppsFlow.value = emptyList()

        advanceUntilIdle()

        assertThat(updateChecker.availableUpdates.value).isEmpty()
    }

    @Test
    fun `checkForUpdates refreshes and returns updates`() = testScope.runTest {
        val app = createApp("com.test.app", versionCode = 2)
        val installed = InstalledApp("com.test.app", versionCode = 1, versionName = "1.0")

        appsFlow.value = listOf(app)
        installedAppsFlow.value = listOf(installed)

        coEvery { appsRepository.refreshApps() } returns Result.success(Unit)
        coEvery { installedAppsRepository.refreshInstalledApps() } returns Unit

        advanceUntilIdle()

        val result = updateChecker.checkForUpdates()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).hasSize(1)
    }

    @Test
    fun `checkForUpdates fails when refresh fails`() = testScope.runTest {
        coEvery { appsRepository.refreshApps() } returns Result.failure(Exception("Network error"))

        val result = updateChecker.checkForUpdates()

        assertThat(result.isFailure).isTrue()
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
