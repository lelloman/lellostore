package com.lelloman.store.download

import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.logger.Logger
import com.lelloman.store.installation.InstallationCoordinator
import com.lelloman.store.installation.InstallationResult
import com.lelloman.store.installation.InstallationChannelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var remoteApiClient: RemoteApiClient
    private lateinit var appsRepository: AppsRepository
    private lateinit var logger: Logger
    private lateinit var installationCoordinator: InstallationCoordinator
    private lateinit var downloadManager: DownloadManagerImpl

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        remoteApiClient = mockk()
        appsRepository = mockk()
        logger = mockk(relaxed = true)
        installationCoordinator = mockk(relaxed = true)

        val cacheDir = tempFolder.newFolder("cache")
        every { context.cacheDir } returns cacheDir
        every { context.packageName } returns "com.lelloman.store"
        every { context.packageManager } returns mockk<PackageManager> {
            every { canRequestPackageInstalls() } returns true
        }

        downloadManager = DownloadManagerImpl(
            context = context,
            remoteApiClient = remoteApiClient,
            appsRepository = appsRepository,
            logger = logger,
            installationCoordinator = installationCoordinator,
        )
    }

    @Test
    fun `activeDownloads initially empty`() = runTest {
        assertThat(downloadManager.activeDownloads.value).isEmpty()
    }

    @Test
    fun `downloadAndInstall returns Failed when already in progress`() = runTest {
        // Setup a long-running download
        val appDetail = createAppDetail()
        coEvery { appsRepository.refreshApp(any()) } returns Result.success(appDetail)

        // Simulate a download that never completes by making the API return a stream that blocks
        coEvery { remoteApiClient.downloadApk(any(), any()) } coAnswers {
            delay(10000) // Long delay
            Result.success(ByteArrayInputStream(ByteArray(0)))
        }

        // Start first download in background
        launch {
            downloadManager.downloadAndInstall("com.test.app", 1)
        }

        // Give it time to start
        delay(100)

        // Try to start another download for the same package
        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(DownloadResult.Failed("Download already in progress"))
    }

    @Test
    fun `downloadAndInstall fails gracefully on API error`() = runTest {
        val appDetail = createAppDetail()

        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(appDetail)
        coEvery { remoteApiClient.downloadApk("com.test.app", 1) } returns
            Result.failure(Exception("Network error"))

        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(DownloadResult.Failed("Download failed"))
        verify { logger.e(any(), any(), any()) }
    }

    @Test
    fun `cancelDownload is callable without crashing`() = runTest {
        // Cancelling a non-existent download should not crash
        downloadManager.cancelDownload("com.nonexistent.app")
        // No exception means success
    }

    @Test
    fun `cancelDownload cancels the coroutine performing the download`() = runTest {
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(createAppDetail())
        coEvery { remoteApiClient.downloadApk("com.test.app", 1) } coAnswers {
            awaitCancellation()
        }

        val download = launch {
            downloadManager.downloadAndInstall("com.test.app", 1)
        }
        runCurrent()

        downloadManager.cancelDownload("com.test.app")
        runCurrent()

        try {
            assertThat(download.isCancelled).isTrue()
        } finally {
            download.cancel()
        }
    }

    @Test
    fun `background fallback retains verified apk for foreground retry`() = runTest {
        val bytes = "apk".toByteArray()
        val detail = createAppDetail(
            sha256 = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04",
            size = bytes.size.toLong(),
        )
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(detail)
        coEvery { remoteApiClient.downloadApk("com.test.app", 1) } returns
            Result.success(ByteArrayInputStream(bytes))
        coEvery {
            installationCoordinator.install(match { it.mode == InstallationMode.BACKGROUND })
        } returns InstallationResult.UserActionRequired(listOf("No silent channel"))
        coEvery {
            installationCoordinator.install(match { it.mode == InstallationMode.FOREGROUND })
        } returns InstallationResult.UserActionStarted(
            InstallationChannelMetadata("package-installer", "Package installer", true, 100)
        )

        val backgroundResult = downloadManager.downloadAndInstall(
            "com.test.app",
            1,
            InstallationMode.BACKGROUND,
        )
        val foregroundResult = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(backgroundResult).isEqualTo(DownloadResult.UserActionRequired)
        assertThat(foregroundResult).isEqualTo(DownloadResult.Success)
        coVerify(exactly = 1) { remoteApiClient.downloadApk("com.test.app", 1) }
    }

    private fun createAppDetail(
        packageName: String = "com.test.app",
        sha256: String = "abc123",
        size: Long = 1000,
    ) = AppDetail(
        packageName = packageName,
        name = "Test App",
        description = "Test description",
        iconUrl = "https://example.com/icon.png",
        versions = listOf(
            AppVersion(
                versionCode = 1,
                versionName = "1.0.0",
                size = size,
                sha256 = sha256,
                minSdk = 21,
                uploadedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        ),
    )
}
