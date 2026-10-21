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
import com.lelloman.store.installation.SelfUpdateTracker
import com.lelloman.store.notification.NotificationHelper
import com.lelloman.store.recovery.RecoveryCompanionClient
import com.lelloman.store.recovery.SelfUpdateProtection
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
    private val notificationHelper: NotificationHelper,
    private val selfUpdateTracker: SelfUpdateTracker,
    @ApplicationScope private val scope: CoroutineScope,
    private val session: com.lelloman.store.domain.config.StoreSession = com.lelloman.store.domain.config.StoreSession(),
) : DownloadManager {

    private val apkProvider = VerifiedApkProvider(remoteApiClient)
    private val tag = "DownloadManager"
    private val mutableActiveDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val activeDownloads: StateFlow<Map<String, DownloadProgress>> = mutableActiveDownloads.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Deferred<DownloadResult>>()

    /**
     * The operation that currently owns each package's [activeDownloads] entry. A finished
     * operation's delayed UI cleanup only removes the entry while it still owns it, so a retry
     * started within the cleanup delay keeps its state.
     */
    private val entryOwners = mutableMapOf<String, String>()
    private val apksDir: File by lazy {
        File(context.cacheDir, "apks").also { it.mkdirs() }
    }

    override suspend fun downloadAndInstall(
        packageName: String,
        versionCode: Int,
        installationMode: InstallationMode,
        purpose: com.lelloman.store.domain.model.AcquisitionPurpose,
    ): DownloadResult {
        val epoch = session.epoch
        val task = synchronized(downloadJobs) {
            if (downloadJobs.containsKey(packageName)) {
                return DownloadResult.Failed("Download already in progress", DownloadFailureKind.ALREADY_IN_PROGRESS)
            }
            scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    session.use(epoch) { performDownloadAndInstall(packageName, versionCode, installationMode, purpose) }
                } finally {
                    synchronized(downloadJobs) { downloadJobs.remove(packageName) }
                }
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
        } catch (error: IllegalStateException) {
            DownloadResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "Server changed. Start this operation again.")
        }
    }

    private suspend fun performDownloadAndInstall(
        packageName: String,
        versionCode: Int,
        installationMode: InstallationMode,
        purpose: com.lelloman.store.domain.model.AcquisitionPurpose,
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
        synchronized(entryOwners) { entryOwners[packageName] = operationId }
        fun publish(
            state: DownloadState,
            progress: Float,
            bytesDownloaded: Long,
            totalBytes: Long,
            reason: String? = null,
        ) = updateProgress(packageName, state, progress, bytesDownloaded, totalBytes, installationMode, reason)
        var destination: File? = null
        var finalState = DownloadState.FAILED
        var retainVerifiedApk = false
        var failure: DownloadResult.Failed? = null

        try {
            publish(DownloadState.PENDING, 0f, 0, 0)
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

            var expectedSize = versionInfo.size
            destination = File(apksDir, "$packageName-$versionCode.apk")
            publish(DownloadState.DOWNLOADING, 0f, 0, expectedSize)
            audit("download.started", mapOf("expected_bytes" to expectedSize))
            var lastSample = System.nanoTime()
            val throttle = ProgressThrottle()
            apkProvider.prepare(packageName, versionInfo, destination,
                onMetadata = { expectedSize = it },
                purpose = purpose,
                onProgress = { bytes ->
                    if (throttle.shouldPublish(bytes, expectedSize)) {
                        publish(DownloadState.DOWNLOADING, bytes.toFloat() / expectedSize, bytes, expectedSize)
                    }
                    if (System.nanoTime() - lastSample >= 5_000_000_000L) {
                        audit("download.progress", mapOf("bytes" to bytes, "expected_bytes" to expectedSize))
                        lastSample = System.nanoTime()
                    }
                },
                onVerifying = {
                    audit("download.completed", mapOf("bytes" to destination.length()))
                    publish(DownloadState.VERIFYING, 1f, destination.length(), destination.length())
                },
            )
            audit("verification.completed")
            // Install APK
            publish(DownloadState.INSTALLING, 1f, destination.length(), destination.length())
            audit("installation.started")
            val recovery = RecoveryCompanionClient(context)
            val isSelfUpdate = packageName == context.packageName
            val protection = if (isSelfUpdate) {
                recovery.protectSelfUpdate(versionCode)
            } else null
            val recoveryAttemptId = (protection as? SelfUpdateProtection.Protected)?.attemptId
            if (protection is SelfUpdateProtection.Unprotected) {
                // Without a rollback point the update still proceeds; the user is warned
                // before the process is replaced, since nothing can report afterwards.
                audit("self_update.unprotected", mapOf("reason" to protection.reason))
                notificationHelper.showUnprotectedSelfUpdateWarning(protection.reason)
            }
            // Installing ourselves kills this process; the next start reports the outcome.
            if (isSelfUpdate) selfUpdateTracker.installing(versionCode)
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
                    val installedBytes = destination.length()
                    publish(DownloadState.COMPLETED, 1f, installedBytes, installedBytes)
                    // Installed silently: nothing reads the verified APK anymore.
                    destination.delete()
                }
                is InstallationResult.UserActionStarted -> {
                    // The package-change receiver refreshes persistent installed state after the
                    // user confirms Android's installer. Until then this only means it was opened.
                    // The APK is kept: the installer reads it through the FileProvider URI.
                    finalState = DownloadState.AWAITING_CONFIRMATION
                    publish(DownloadState.AWAITING_CONFIRMATION, 1f, destination.length(), destination.length())
                }
                is InstallationResult.PermissionRequired -> {
                    recoveryAttemptId?.let {
                        recovery.cancelUnreplacedAttempt(it, installResult.reason)
                    }
                    // Permission needed - keep the APK for retry after permission is granted
                    finalState = DownloadState.PERMISSION_REQUIRED
                    publish(DownloadState.PERMISSION_REQUIRED, 1f, destination.length(), destination.length())
                }
                is InstallationResult.UserActionRequired -> {
                    recoveryAttemptId?.let {
                        recovery.cancelUnreplacedAttempt(it, installResult.reasons.joinToString("; "))
                    }
                    retainVerifiedApk = true
                    finalState = DownloadState.PERMISSION_REQUIRED
                    publish(DownloadState.PERMISSION_REQUIRED, 1f, destination.length(), destination.length())
                }
                is InstallationResult.Failed -> {
                    val allReasons = installResult.reasons.joinToString("; ")
                    audit("installation.failed", mapOf("reasons" to allReasons))
                    // Earlier entries are channels that were merely unavailable; the last one is
                    // the channel that actually attempted and failed the installation.
                    val reason = installResult.reasons.lastOrNull()?.takeIf { it.isNotBlank() }
                        ?: "Installation failed"
                    recoveryAttemptId?.let { recovery.cancelUnreplacedAttempt(it, allReasons.ifEmpty { reason }) }
                    failure = DownloadResult.Failed(
                        reason = reason,
                        kind = if (allReasons.contains(INCOMPATIBLE_UPDATE_ERROR, ignoreCase = true)) {
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
            publish(DownloadState.CANCELLED, 0f, 0, 0)
            if (!retainVerifiedApk) destination?.delete()
            throw e
        } catch (e: Exception) {
            finalState = DownloadState.FAILED
            audit("operation.error", mapOf("error_type" to e.javaClass.simpleName))
            logger.e(tag, "Download failed for $packageName: ${e.message}", e)
            val failed = failure
                ?: DownloadResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Download failed")
            failure = failed
            publish(DownloadState.FAILED, 0f, 0, 0, reason = failed.reason)
            destination?.delete()
        } finally {
            // Kept while Android may still replace the app: after a reported installation (the
            // kill can follow it) or while the interactive installer awaits the user.
            if (packageName == context.packageName &&
                finalState != DownloadState.COMPLETED &&
                finalState != DownloadState.AWAITING_CONFIRMATION
            ) {
                selfUpdateTracker.abandoned()
            }
            audit("operation.finished", mapOf("state" to finalState.name))
            // Clear progress after delay to allow UI to show final state
            scope.launch {
                delay(UI_CLEAR_DELAY_MS)
                val owned = synchronized(entryOwners) {
                    if (entryOwners[packageName] == operationId) {
                        entryOwners.remove(packageName)
                        // Removed under the same lock so a new operation cannot claim the
                        // package between the ownership check and the removal.
                        mutableActiveDownloads.update { it - packageName }
                        true
                    } else {
                        false
                    }
                }
                if (owned) audit("operation.ui_cleared")
            }
        }

        return when (finalState) {
            DownloadState.COMPLETED -> DownloadResult.Success
            DownloadState.CANCELLED -> DownloadResult.Cancelled
            DownloadState.AWAITING_CONFIRMATION -> DownloadResult.AwaitingConfirmation
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
            logger.audit("ipc.install_permission_settings", mapOf("action" to intent.action))
            context.startActivity(intent)
        }
    }

    private fun updateProgress(
        packageName: String,
        state: DownloadState,
        progress: Float,
        bytesDownloaded: Long,
        totalBytes: Long,
        mode: InstallationMode,
        reason: String?,
    ) {
        mutableActiveDownloads.update { current ->
            current + (packageName to DownloadProgress(
                packageName = packageName,
                progress = progress,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                state = state,
                mode = mode,
                reason = reason,
            ))
        }
    }

    private class InstallationFailedException(message: String) : IllegalStateException(message)

    private companion object {
        const val INCOMPATIBLE_UPDATE_ERROR = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
        const val UI_CLEAR_DELAY_MS = 3000L
    }
}

/**
 * Limits download progress publications: the APK stream reports every 8 KiB chunk, while
 * observers (and the notification that renders them) only need visible changes. Publishes when
 * the integer percent changes, when [intervalNanos] passed since the last publication, and
 * always for the final byte count.
 */
internal class ProgressThrottle(
    private val intervalNanos: Long = 250_000_000L,
    private val clock: () -> Long = System::nanoTime,
) {
    private var lastPercent = -1L
    private var lastPublishedAt = 0L
    private var published = false

    fun shouldPublish(bytes: Long, totalBytes: Long): Boolean {
        val now = clock()
        val percent = if (totalBytes > 0) bytes * 100 / totalBytes else -1L
        val publish = !published ||
            (totalBytes > 0 && bytes >= totalBytes) ||
            percent != lastPercent ||
            now - lastPublishedAt >= intervalNanos
        if (publish) {
            published = true
            lastPercent = percent
            lastPublishedAt = now
        }
        return publish
    }
}
