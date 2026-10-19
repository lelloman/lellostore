package com.lelloman.store.download

import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.DownloadFailureKind
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.logger.Logger
import com.lelloman.store.installation.InstallationCoordinator
import com.lelloman.store.notification.NotificationHelper
import com.lelloman.store.installation.InstallationResult
import com.lelloman.store.installation.InstallationChannelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
    private lateinit var installedAppsRepository: InstalledAppsRepository
    private lateinit var logger: Logger
    private lateinit var installationCoordinator: InstallationCoordinator
    private lateinit var foregroundServiceStarter: DownloadForegroundServiceStarter
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var downloadManager: DownloadManagerImpl

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        remoteApiClient = mockk()
        appsRepository = mockk()
        installedAppsRepository = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        installationCoordinator = mockk(relaxed = true)
        foregroundServiceStarter = mockk(relaxed = true)
        notificationHelper = mockk(relaxed = true)

        coEvery { remoteApiClient.acquireApk(any(), any(), any(), any()) } coAnswers {
            val packageName = firstArg<String>()
            val versionCode = secondArg<Int>()
            val version = appsRepository.refreshApp(packageName).getOrThrow().versions.first { it.versionCode == versionCode }
            Result.success(com.lelloman.store.domain.model.ApkAcquisition(packageName, packageName, versionCode, version.size, version.sha256!!))
        }
        coEvery { remoteApiClient.downloadAcquisition(any()) } coAnswers {
            remoteApiClient.downloadApk(firstArg(), 1)
        }

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
            installedAppsRepository = installedAppsRepository,
            logger = logger,
            installationCoordinator = installationCoordinator,
            foregroundServiceStarter = foregroundServiceStarter,
            notificationHelper = notificationHelper,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
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
        val firstDownload = launch {
            downloadManager.downloadAndInstall("com.test.app", 1)
        }

        // Give it time to start
        delay(100)

        // Try to start another download for the same package
        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(DownloadResult.Failed("Download already in progress"))
        downloadManager.cancelDownload("com.test.app")
        firstDownload.cancelAndJoin()
    }

    @Test
    fun `downloadAndInstall fails gracefully on API error`() = runTest {
        val appDetail = createAppDetail()

        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(appDetail)
        coEvery { remoteApiClient.downloadApk("com.test.app", 1) } returns
            Result.failure(Exception("Network error"))

        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(DownloadResult.Failed("Network error"))
        val progress = downloadManager.activeDownloads.value.getValue("com.test.app")
        assertThat(progress.state).isEqualTo(DownloadState.FAILED)
        assertThat(progress.reason).isEqualTo("Network error")
        assertThat(progress.mode).isEqualTo(InstallationMode.FOREGROUND)
        verify { logger.e(any(), any(), any()) }
        // Verify the failure can be correlated with its start without relying on log messages.
        val starts = mutableListOf<Map<String, Any?>>()
        val finishes = mutableListOf<Map<String, Any?>>()
        verify(exactly = 1) { logger.audit("operation.started", capture(starts)) }
        verify(exactly = 1) { logger.audit("operation.finished", capture(finishes)) }
        assertThat(finishes.single()["operation_id"]).isEqualTo(starts.single()["operation_id"])
        assertThat(finishes.single()["state"]).isEqualTo("FAILED")
        assertThat(finishes.single()["package"]).isEqualTo("com.test.app")
    }

    @Test
    fun `signature mismatch is returned as an incompatible signature failure`() = runTest {
        val bytes = "apk".toByteArray()
        val detail = createAppDetail(
            sha256 = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04",
            size = bytes.size.toLong(),
        )
        val packageManagerReason =
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.test.app " +
                "signatures do not match newer version]"
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(detail)
        coEvery { remoteApiClient.downloadApk("com.test.app", 1) } returns
            Result.success(ByteArrayInputStream(bytes))
        coEvery { installationCoordinator.install(any()) } returns
            InstallationResult.Failed(listOf("Legacy ADB: $packageManagerReason"))

        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(
            DownloadResult.Failed(
                reason = "Legacy ADB: $packageManagerReason",
                kind = DownloadFailureKind.INCOMPATIBLE_SIGNATURE,
            )
        )
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
        download.join()

        try {
            assertThat(download.isCancelled).isTrue()
        } finally {
            download.cancel()
        }
    }

    @Test
    fun `foreground download survives cancellation of the screen coroutine`() = runTest {
        val downloadStarted = CompletableDeferred<Unit>()
        coEvery { appsRepository.refreshApp("com.test.app") } coAnswers {
            downloadStarted.complete(Unit)
            awaitCancellation()
        }

        val screenRequest = launch {
            downloadManager.downloadAndInstall("com.test.app", 1)
        }
        // The service-owned task runs on Dispatchers.IO, outside the test scheduler.
        downloadStarted.await()

        screenRequest.cancelAndJoin()

        assertThat(downloadManager.activeDownloads.value["com.test.app"]?.state)
            .isEqualTo(DownloadState.PENDING)
        verify { foregroundServiceStarter.start() }

        downloadManager.cancelDownload("com.test.app")
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
        assertThat(foregroundResult).isEqualTo(DownloadResult.AwaitingConfirmation)
        coVerify(exactly = 1) { remoteApiClient.downloadApk("com.test.app", 1) }
    }

    @Test
    fun `failure reason without message falls back to generic text`() = runTest {
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.failure(RuntimeException())

        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(DownloadResult.Failed("Download failed"))
        assertThat(downloadManager.activeDownloads.value.getValue("com.test.app").reason)
            .isEqualTo("Download failed")
    }

    @Test
    fun `missing version reason is surfaced`() = runTest {
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(createAppDetail())

        val result = downloadManager.downloadAndInstall("com.test.app", 7)

        assertThat(result).isEqualTo(DownloadResult.Failed("Version 7 not found for com.test.app"))
        assertThat(downloadManager.activeDownloads.value.getValue("com.test.app").reason)
            .isEqualTo("Version 7 not found for com.test.app")
    }

    @Test
    fun `installation failure reports the channel that failed and keeps all reasons in the audit`() = runTest {
        stubVerifiedDownload()
        coEvery { installationCoordinator.install(any()) } returns InstallationResult.Failed(
            listOf(
                "Legacy ADB: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]",
                "Package installer: aborted",
            )
        )

        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(
            DownloadResult.Failed(
                reason = "Package installer: aborted",
                kind = DownloadFailureKind.INCOMPATIBLE_SIGNATURE,
            )
        )
        val progress = downloadManager.activeDownloads.value.getValue("com.test.app")
        assertThat(progress.state).isEqualTo(DownloadState.FAILED)
        assertThat(progress.reason).isEqualTo("Package installer: aborted")
        val audits = mutableListOf<Map<String, Any?>>()
        verify(exactly = 1) { logger.audit("installation.failed", capture(audits)) }
        assertThat(audits.single()["reasons"] as String).contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertThat(audits.single()["reasons"] as String).contains("Package installer: aborted")
    }

    @Test
    fun `interactive installer awaits confirmation and keeps the apk`() = runTest {
        stubVerifiedDownload()
        coEvery { installationCoordinator.install(any()) } returns InstallationResult.UserActionStarted(
            InstallationChannelMetadata("package-installer", "Package installer", true, 100)
        )

        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(DownloadResult.AwaitingConfirmation)
        assertThat(downloadManager.activeDownloads.value.getValue("com.test.app").state)
            .isEqualTo(DownloadState.AWAITING_CONFIRMATION)
        assertThat(apkFile().exists()).isTrue()
    }

    @Test
    fun `silent installation deletes the verified apk`() = runTest {
        stubVerifiedDownload()
        coEvery { installationCoordinator.install(any()) } returns InstallationResult.Installed(
            InstallationChannelMetadata("legacy-adb", "ADB", false, 10)
        )

        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(DownloadResult.Success)
        assertThat(downloadManager.activeDownloads.value.getValue("com.test.app").state)
            .isEqualTo(DownloadState.COMPLETED)
        assertThat(apkFile().exists()).isFalse()
    }

    @Test
    fun `retained apk survives a background user action requirement`() = runTest {
        stubVerifiedDownload()
        coEvery { installationCoordinator.install(any()) } returns
            InstallationResult.UserActionRequired(listOf("No silent channel"))

        downloadManager.downloadAndInstall("com.test.app", 1, InstallationMode.BACKGROUND)

        assertThat(apkFile().exists()).isTrue()
        assertThat(downloadManager.activeDownloads.value.getValue("com.test.app").mode)
            .isEqualTo(InstallationMode.BACKGROUND)
    }

    @Test
    fun `delayed cleanup of a finished operation keeps a newer operation's state`() = runTest {
        val manager = DownloadManagerImpl(
            context = context,
            remoteApiClient = remoteApiClient,
            appsRepository = appsRepository,
            installedAppsRepository = installedAppsRepository,
            logger = logger,
            installationCoordinator = installationCoordinator,
            foregroundServiceStarter = foregroundServiceStarter,
            notificationHelper = notificationHelper,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.failure(Exception("offline"))
        assertThat(manager.downloadAndInstall("com.test.app", 1)).isEqualTo(DownloadResult.Failed("offline"))

        val retryStarted = CompletableDeferred<Unit>()
        coEvery { appsRepository.refreshApp("com.test.app") } coAnswers {
            retryStarted.complete(Unit)
            awaitCancellation()
        }
        val retry = launch { manager.downloadAndInstall("com.test.app", 1) }
        retryStarted.await()

        // The first operation's cleanup becomes due while the retry is still running.
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(manager.activeDownloads.value["com.test.app"]?.state).isEqualTo(DownloadState.PENDING)

        manager.cancelDownload("com.test.app")
        retry.join()
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(manager.activeDownloads.value).isEmpty()
    }

    private fun stubVerifiedDownload() {
        val bytes = "apk".toByteArray()
        val detail = createAppDetail(
            sha256 = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04",
            size = bytes.size.toLong(),
        )
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(detail)
        coEvery { remoteApiClient.downloadApk("com.test.app", 1) } answers {
            Result.success(ByteArrayInputStream(bytes))
        }
    }

    private fun apkFile() = java.io.File(context.cacheDir, "apks/com.test.app-1.apk")

    @Test
    fun `successful silent installation refreshes persistent installed state`() = runTest {
        val bytes = "apk".toByteArray()
        val detail = createAppDetail(
            sha256 = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04",
            size = bytes.size.toLong(),
        )
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(detail)
        coEvery { remoteApiClient.downloadApk("com.test.app", 1) } returns
            Result.success(ByteArrayInputStream(bytes))
        coEvery { installationCoordinator.install(any()) } returns InstallationResult.Installed(
            InstallationChannelMetadata("legacy-adb", "ADB", false, 10)
        )

        val result = downloadManager.downloadAndInstall("com.test.app", 1)

        assertThat(result).isEqualTo(DownloadResult.Success)
        coVerify(exactly = 1) { installedAppsRepository.refreshInstalledApp("com.test.app") }
        verify(exactly = 1) { foregroundServiceStarter.start() }
    }

    @Test
    fun `unprotected self installation proceeds with a warning`() = runTest {
        val bytes = "apk".toByteArray()
        val store = "com.lelloman.store"
        val detail = createAppDetail(packageName = store,
            sha256 = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04",
            size = bytes.size.toLong())
        coEvery { appsRepository.refreshApp(store) } returns Result.success(detail)
        coEvery { remoteApiClient.downloadApk(store, 1) } answers { Result.success(ByteArrayInputStream(bytes)) }
        coEvery { installationCoordinator.install(any()) } returns InstallationResult.Installed(
            InstallationChannelMetadata("silent", "silent", requiresUserInteraction = false, priority = 0)
        )

        val result = downloadManager.downloadAndInstall(store, 1, InstallationMode.BACKGROUND)

        assertThat(result).isEqualTo(DownloadResult.Success)
        verify(exactly = 1) { notificationHelper.showUnprotectedSelfUpdateWarning(any()) }
        coVerify(exactly = 1) { installationCoordinator.install(any()) }
    }

    @Test
    fun `other apps never show the unprotected self update warning`() = runTest {
        val bytes = "apk".toByteArray()
        val detail = createAppDetail(
            sha256 = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04",
            size = bytes.size.toLong())
        coEvery { appsRepository.refreshApp("com.test.app") } returns Result.success(detail)
        coEvery { remoteApiClient.downloadApk("com.test.app", 1) } answers { Result.success(ByteArrayInputStream(bytes)) }
        coEvery { installationCoordinator.install(any()) } returns InstallationResult.Installed(
            InstallationChannelMetadata("silent", "silent", requiresUserInteraction = false, priority = 0)
        )

        downloadManager.downloadAndInstall("com.test.app", 1, InstallationMode.BACKGROUND)

        verify(exactly = 0) { notificationHelper.showUnprotectedSelfUpdateWarning(any()) }
    }

    private fun createAppDetail(
        packageName: String = "com.test.app",
        sha256: String = "0".repeat(64),
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
