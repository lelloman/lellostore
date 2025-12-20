package com.lelloman.store.download

import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.logger.Logger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private lateinit var downloadManager: DownloadManagerImpl

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        remoteApiClient = mockk()
        appsRepository = mockk()
        logger = mockk(relaxed = true)

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

    private fun createAppDetail(
        packageName: String = "com.test.app",
        sha256: String = "abc123",
    ) = AppDetail(
        packageName = packageName,
        name = "Test App",
        description = "Test description",
        iconUrl = "https://example.com/icon.png",
        versions = listOf(
            AppVersion(
                versionCode = 1,
                versionName = "1.0.0",
                size = 1000,
                sha256 = sha256,
                minSdk = 21,
                uploadedAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        ),
    )
}
