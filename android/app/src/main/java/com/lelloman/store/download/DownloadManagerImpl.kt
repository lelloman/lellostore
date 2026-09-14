package com.lelloman.store.download

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.DownloadFailureKind
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.logger.Logger
import com.lelloman.store.installation.InstallationCoordinator
import com.lelloman.store.installation.InstallationRequest
import com.lelloman.store.installation.InstallationResult
import com.lelloman.store.recovery.RecoveryCompanionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteApiClient: RemoteApiClient,
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val logger: Logger,
    private val installationCoordinator: InstallationCoordinator,
    private val foregroundServiceStarter: DownloadForegroundServiceStarter,
    @ApplicationScope private val scope: CoroutineScope,
) : DownloadManager {

    private val tag = "DownloadManager"
    private val mutableActiveDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val activeDownloads: StateFlow<Map<String, DownloadProgress>> = mutableActiveDownloads.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Deferred<DownloadResult>>()
    private val apksDir: File by lazy {
        File(context.cacheDir, "apks").also { it.mkdirs() }
    }

    override suspend fun downloadAndInstall(
        packageName: String,
        versionCode: Int,
        installationMode: InstallationMode,
    ): DownloadResult {
        val task = synchronized(downloadJobs) {
            if (downloadJobs.containsKey(packageName)) {
                return DownloadResult.Failed("Download already in progress")
            }
            scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                performDownloadAndInstall(packageName, versionCode, installationMode)
            }.also { downloadJobs[packageName] = it }
        }
        task.start()
        return try {
            task.await()
        } catch (error: CancellationException) {
            // User-started work belongs to the foreground service and survives navigation away.
            // Scheduled work remains owned by WorkManager and must stop when its worker is stopped.
            if (installationMode == InstallationMode.BACKGROUND) task.cancel()
            throw error
        }
    }

    private suspend fun performDownloadAndInstall(
        packageName: String,
        versionCode: Int,
        installationMode: InstallationMode,
    ): DownloadResult {

        val operationId = java.util.UUID.randomUUID().toString()
        val started = System.nanoTime()
        fun audit(event: String, fields: Map<String, Any?> = emptyMap()) {
            logger.audit(event, fields + mapOf(
                "operation_id" to operationId, "package" to packageName,
                "version_code" to versionCode, "mode" to installationMode.name,
                "duration_ms" to (System.nanoTime() - started) / 1_000_000,
            ))
        }
        audit("operation.started")
        var destination: File? = null
        var finalState = DownloadState.FAILED
        var retainVerifiedApk = false
        var failure: DownloadResult.Failed? = null

        try {
            updateProgress(packageName, DownloadState.PENDING, 0f, 0, 0)
            if (installationMode == InstallationMode.FOREGROUND) {
                runCatching { foregroundServiceStarter.start() }
                    .onFailure { logger.w(tag, "Could not start download foreground service: ${it.message}") }
            }

            // Get expected SHA256 from app details
            val appDetail = appsRepository.refreshApp(packageName).getOrElse { error ->
                logger.e(tag, "Failed to fetch app details for $packageName: ${error.message}", error)
                throw error
            }

            audit("metadata.completed")
            val versionInfo = appDetail.versions.find { it.versionCode == versionCode }
                ?: throw IllegalArgumentException("Version $versionCode not found for $packageName")

            val expectedSha256 = versionInfo.sha256
            val expectedSize = versionInfo.size

            updateProgress(packageName, DownloadState.DOWNLOADING, 0f, 0, expectedSize)

            destination = File(apksDir, "$packageName-$versionCode.apk")

            val cachedApkIsValid = destination.isFile &&
                destination.length() == expectedSize &&
                calculateSha256(destination).equals(expectedSha256, ignoreCase = true)
            audit("download.started", mapOf("expected_bytes" to expectedSize, "cache_hit" to cachedApkIsValid))
            if (!cachedApkIsValid) {
                destination.delete()
                val inputStream = remoteApiClient.downloadApk(packageName, versionCode).getOrThrow()
                downloadToFile(inputStream, destination, packageName, expectedSize) { bytes ->
                    audit("download.progress", mapOf("bytes" to bytes, "expected_bytes" to expectedSize))
                }
            } else {
                logger.i(tag, "Reusing verified APK for $packageName")
            }

            audit("download.completed", mapOf("bytes" to destination.length()))
            // Verify SHA256
            updateProgress(packageName, DownloadState.VERIFYING, 1f, destination.length(), destination.length())
            val actualSha256 = calculateSha256(destination)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                throw SecurityException("SHA256 verification failed: expected $expectedSha256, got $actualSha256")
            }
            logger.i(tag, "SHA256 verification passed for $packageName")

            audit("verification.completed")
            // Install APK
            updateProgress(packageName, DownloadState.INSTALLING, 1f, destination.length(), destination.length())
            audit("installation.started")
            val recovery = RecoveryCompanionClient(context)
            val recoveryAttemptId = if (packageName == context.packageName) {
                recovery.recordSelfUpdate(versionCode)
            } else null
            if (packageName == context.packageName && recoveryAttemptId == null) {
                val reason = context.getString(com.lelloman.store.R.string.self_update_not_ready)
                failure = DownloadResult.Failed(reason)
                throw InstallationFailedException(reason)
            }
            when (val installResult = installationCoordinator.install(
                InstallationRequest(
                    apk = destination,
                    packageName = packageName,
                    versionCode = versionCode,
                    mode = installationMode,
                    operationId = operationId,
                    audit = { event, fields -> audit(event, fields) },
                )
            )) {
                is InstallationResult.Installed -> {
                    // The package manager is the source of truth. Persist its new snapshot before
                    // reporting completion so every Room observer updates in the same UI frame.
                    audit("installed_snapshot.started")
                    installedAppsRepository.refreshInstalledApp(packageName)
                    audit("installed_snapshot.completed")
                    finalState = DownloadState.COMPLETED
                    updateProgress(packageName, DownloadState.COMPLETED, 1f, destination.length(), destination.length())
                }
                is InstallationResult.UserActionStarted -> {
                    // The package-change receiver refreshes persistent installed state after the
                    // user confirms Android's installer. Until then this only means it was opened.
                    finalState = DownloadState.COMPLETED
                    updateProgress(packageName, DownloadState.COMPLETED, 1f, destination.length(), destination.length())
                }
                is InstallationResult.PermissionRequired -> {
                    recoveryAttemptId?.let {
                        recovery.cancelUnreplacedAttempt(it, installResult.reason)
                    }
                    // Permission needed - keep the APK for retry after permission is granted
                    finalState = DownloadState.PERMISSION_REQUIRED
                    updateProgress(packageName, DownloadState.PERMISSION_REQUIRED, 1f, destination.length(), destination.length())
                }
                is InstallationResult.UserActionRequired -> {
                    recoveryAttemptId?.let {
                        recovery.cancelUnreplacedAttempt(it, installResult.reasons.joinToString("; "))
                    }
                    retainVerifiedApk = true
                    finalState = DownloadState.PERMISSION_REQUIRED
                    updateProgress(packageName, DownloadState.PERMISSION_REQUIRED, 1f, destination.length(), destination.length())
                }
                is InstallationResult.Failed -> {
                    val reason = installResult.reasons.joinToString("; ")
                        .ifEmpty { "Installation failed" }
                    recoveryAttemptId?.let { recovery.cancelUnreplacedAttempt(it, reason) }
                    failure = DownloadResult.Failed(
                        reason = reason,
                        kind = if (reason.contains(INCOMPATIBLE_UPDATE_ERROR, ignoreCase = true)) {
                            DownloadFailureKind.INCOMPATIBLE_SIGNATURE
                        } else {
                            DownloadFailureKind.GENERIC
                        },
                    )
                    throw InstallationFailedException(reason)
                }
            }

        } catch (e: CancellationException) {
            finalState = DownloadState.CANCELLED
            updateProgress(packageName, DownloadState.CANCELLED, 0f, 0, 0)
            if (!retainVerifiedApk) destination?.delete()
            throw e
        } catch (e: Exception) {
            finalState = DownloadState.FAILED
            audit("operation.error", mapOf("error_type" to e.javaClass.simpleName))
            logger.e(tag, "Download failed for $packageName: ${e.message}", e)
            updateProgress(packageName, DownloadState.FAILED, 0f, 0, 0)
            destination?.delete()
        } finally {
            audit("operation.finished", mapOf("state" to finalState.name))
            synchronized(downloadJobs) {
                downloadJobs.remove(packageName)
            }
            // Clear progress after delay to allow UI to show final state
            scope.launch {
                delay(3000)
                mutableActiveDownloads.update { it - packageName }
                audit("operation.ui_cleared")
            }
        }

        return when (finalState) {
            DownloadState.COMPLETED -> DownloadResult.Success
            DownloadState.CANCELLED -> DownloadResult.Cancelled
            DownloadState.PERMISSION_REQUIRED -> if (retainVerifiedApk) {
                DownloadResult.UserActionRequired
            } else {
                DownloadResult.PermissionRequired
            }
            else -> failure ?: DownloadResult.Failed("Download failed")
        }
    }

    override fun cancelDownload(packageName: String) {
        synchronized(downloadJobs) {
            downloadJobs[packageName]?.cancel()
        }
    }

    override fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private suspend fun downloadToFile(
        inputStream: InputStream,
        destination: File,
        packageName: String,
        totalSize: Long,
        onSample: (Long) -> Unit,
    ) {
        inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesDownloaded = 0L
                var bytesRead: Int
                var lastSample = System.nanoTime()

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead
                    val now = System.nanoTime()
                    if (now - lastSample >= 5_000_000_000L) {
                        onSample(bytesDownloaded)
                        lastSample = now
                    }

                    val progress = if (totalSize > 0) bytesDownloaded.toFloat() / totalSize else 0f
                    updateProgress(packageName, DownloadState.DOWNLOADING, progress, bytesDownloaded, totalSize)
                }
            }
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateProgress(
        packageName: String,
        state: DownloadState,
        progress: Float,
        bytesDownloaded: Long,
        totalBytes: Long,
    ) {
        mutableActiveDownloads.update { current ->
            current + (packageName to DownloadProgress(
                packageName = packageName,
                progress = progress,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                state = state,
            ))
        }
    }

    private class InstallationFailedException(message: String) : IllegalStateException(message)

    private companion object {
        const val INCOMPATIBLE_UPDATE_ERROR = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
    }
}
