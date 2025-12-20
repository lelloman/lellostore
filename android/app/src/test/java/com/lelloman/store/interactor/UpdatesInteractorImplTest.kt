package com.lelloman.store.interactor

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.model.AvailableUpdate
import com.lelloman.store.domain.updates.UpdateChecker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesInteractorImplTest {

    private lateinit var updateChecker: UpdateChecker
    private lateinit var downloadManager: DownloadManager
    private lateinit var interactor: UpdatesInteractorImpl

    private val updatesFlow = MutableStateFlow<List<AvailableUpdate>>(emptyList())

    @Before
    fun setup() {
        updateChecker = mockk()
        downloadManager = mockk()

        every { updateChecker.availableUpdates } returns updatesFlow

        interactor = UpdatesInteractorImpl(
            updateChecker = updateChecker,
            downloadManager = downloadManager,
        )
    }

    @Test
    fun `watchUpdates maps AvailableUpdate to UpdateUiModel`() = runTest {
        val app = createApp("com.test.app")
        val update = AvailableUpdate(
            app = app,
            installedVersionCode = 1,
            installedVersionName = "1.0",
        )
        updatesFlow.value = listOf(update)

        val uiModels = interactor.watchUpdates().first()

        assertThat(uiModels).hasSize(1)
        val uiModel = uiModels.first()
        assertThat(uiModel.packageName).isEqualTo("com.test.app")
        assertThat(uiModel.appName).isEqualTo("Test App")
        assertThat(uiModel.installedVersion).isEqualTo("1.0")
        assertThat(uiModel.availableVersion).isEqualTo("2.0")
    }

    @Test
    fun `checkForUpdates delegates to updateChecker`() = runTest {
        coEvery { updateChecker.checkForUpdates() } returns Result.success(emptyList())

        val result = interactor.checkForUpdates()

        assertThat(result.isSuccess).isTrue()
        coVerify { updateChecker.checkForUpdates() }
    }

    @Test
    fun `downloadAndInstall calls downloadManager with correct version`() = runTest {
        val app = createApp("com.test.app")
        val update = AvailableUpdate(
            app = app,
            installedVersionCode = 1,
            installedVersionName = "1.0",
        )
        updatesFlow.value = listOf(update)

        coEvery { downloadManager.downloadAndInstall(any(), any()) } returns mockk()

        interactor.downloadAndInstall("com.test.app")

        coVerify { downloadManager.downloadAndInstall("com.test.app", 2) }
    }

    @Test
    fun `downloadAndInstall does nothing when package not found`() = runTest {
        updatesFlow.value = emptyList()

        interactor.downloadAndInstall("com.nonexistent.app")

        coVerify(exactly = 0) { downloadManager.downloadAndInstall(any(), any()) }
    }

    @Test
    fun `formatSize formats bytes correctly`() = runTest {
        val app1kb = createApp("com.test.1kb", size = 500)
        val app1mb = createApp("com.test.1mb", size = 500 * 1024)
        val app10mb = createApp("com.test.10mb", size = 10 * 1024 * 1024L)

        updatesFlow.value = listOf(
            AvailableUpdate(app1kb, 1, "1.0"),
            AvailableUpdate(app1mb, 1, "1.0"),
            AvailableUpdate(app10mb, 1, "1.0"),
        )

        val uiModels = interactor.watchUpdates().first()

        assertThat(uiModels[0].updateSize).isEqualTo("500 B")
        assertThat(uiModels[1].updateSize).isEqualTo("500 KB")
        assertThat(uiModels[2].updateSize).isEqualTo("10.0 MB")
    }

    private fun createApp(packageName: String, size: Long = 1000): App {
        return App(
            packageName = packageName,
            name = "Test App",
            description = "Description",
            iconUrl = "https://example.com/icon.png",
            latestVersion = AppVersion(
                versionCode = 2,
                versionName = "2.0",
                size = size,
                sha256 = "abc123",
                minSdk = 21,
                uploadedAt = Instant.parse("2024-01-01T00:00:00Z"),
            ),
        )
    }
}
