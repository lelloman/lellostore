package com.lelloman.store.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.logger.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val logger: Logger,
) : DownloadManager {

    private val tag = "DownloadManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableActiveDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val activeDownloads: StateFlow<Map<String, DownloadProgress>> = mutableActiveDownloads.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val apksDir: File by lazy {
        File(context.cacheDir, "apks").also { it.mkdirs() }
    }

    override suspend fun downloadAndInstall(
        packageName: String,
        versionCode: Int,
    ): DownloadResult {
        synchronized(downloadJobs) {
            if (downloadJobs.containsKey(packageName)) {
                return DownloadResult.Failed("Download already in progress")
            }
            // Mark as started immediately to prevent race conditions
            downloadJobs[packageName] = Job()
        }

        var destination: File? = null
        var finalState = DownloadState.FAILED

        try {
            updateProgress(packageName, DownloadState.PENDING, 0f, 0, 0)

            // Get expected SHA256 from app details
            val appDetail = appsRepository.refreshApp(packageName).getOrElse { error ->
                logger.e(tag, "Failed to fetch app details for $packageName: ${error.message}", error)
                throw error
            }

            val versionInfo = appDetail.versions.find { it.versionCode == versionCode }
                ?: throw IllegalArgumentException("Version $versionCode not found for $packageName")

            val expectedSha256 = versionInfo.sha256
            val expectedSize = versionInfo.size

            updateProgress(packageName, DownloadState.DOWNLOADING, 0f, 0, expectedSize)

            destination = File(apksDir, "$packageName-$versionCode.apk")

            // Download APK
            val inputStream = remoteApiClient.downloadApk(packageName, versionCode).getOrThrow()
            downloadToFile(inputStream, destination, packageName, expectedSize)

            // Verify SHA256
            updateProgress(packageName, DownloadState.VERIFYING, 1f, destination.length(), destination.length())
            val actualSha256 = calculateSha256(destination)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                throw SecurityException("SHA256 verification failed: expected $expectedSha256, got $actualSha256")
            }
            logger.i(tag, "SHA256 verification passed for $packageName")

            // Install APK
            updateProgress(packageName, DownloadState.INSTALLING, 1f, destination.length(), destination.length())
            val installed = installApk(destination)

            if (installed) {
                finalState = DownloadState.COMPLETED
                updateProgress(packageName, DownloadState.COMPLETED, 1f, destination.length(), destination.length())
            } else {
                // Permission needed - keep the APK for retry after permission is granted
                finalState = DownloadState.FAILED
                updateProgress(packageName, DownloadState.FAILED, 0f, 0, 0)
            }

        } catch (e: CancellationException) {
            finalState = DownloadState.CANCELLED
            updateProgress(packageName, DownloadState.CANCELLED, 0f, 0, 0)
            destination?.delete()
            throw e
        } catch (e: Exception) {
            finalState = DownloadState.FAILED
            logger.e(tag, "Download failed for $packageName: ${e.message}", e)
            updateProgress(packageName, DownloadState.FAILED, 0f, 0, 0)
            destination?.delete()
        } finally {
            synchronized(downloadJobs) {
                downloadJobs.remove(packageName)
            }
            // Clear progress after delay to allow UI to show final state
            delay(3000)
            mutableActiveDownloads.update { it - packageName }
        }

        return when (finalState) {
            DownloadState.COMPLETED -> DownloadResult.Success
            DownloadState.CANCELLED -> DownloadResult.Cancelled
            else -> DownloadResult.Failed("Download failed")
        }
    }

    override fun cancelDownload(packageName: String) {
        downloadJobs[packageName]?.cancel()
    }

    private suspend fun downloadToFile(
        inputStream: InputStream,
        destination: File,
        packageName: String,
        totalSize: Long,
    ) {
        inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesDownloaded = 0L
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead

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

    /**
     * Attempts to install the APK.
     * @return true if the install intent was launched, false if permission is needed
     */
    private fun installApk(file: File): Boolean {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        // On Android O and above, check if we can request package installs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                // Open settings to allow installation from unknown sources
                val settingsIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                logger.w(tag, "Install permission not granted. User needs to enable 'Install unknown apps' and retry.")
                return false
            }
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
        return true
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
}
