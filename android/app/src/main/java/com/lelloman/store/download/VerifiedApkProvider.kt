package com.lelloman.store.download

import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.model.AppVersion
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** Callers own their destination: remote operations never share mutable local install files. */
class VerifiedApkProvider @Inject constructor(private val api: RemoteApiClient) {
    suspend fun prepare(
        packageName: String,
        version: AppVersion,
        destination: File,
        onProgress: (Long) -> Unit = {},
        onVerifying: () -> Unit = {},
        onMetadata: (Long) -> Unit = {},
    ): File {
        require(version.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "Missing APK verification metadata" }
        require(version.size > 0) { "Invalid APK size" }
        val acquisition = api.acquireApk(packageName, version.versionCode, java.util.UUID.randomUUID().toString()).getOrThrow()
        require(acquisition.packageName == packageName && acquisition.versionCode == version.versionCode) { "Acquisition identity does not match requested APK" }
        val expected = acquisition.sha256
        val expectedSize = acquisition.size
        require(expected.matches(Regex("[a-fA-F0-9]{64}")) && expectedSize > 0) { "Invalid acquisition verification metadata" }
        onMetadata(expectedSize)
        val reusable = destination.isFile && destination.length() == expectedSize && sha256(destination).equals(expected, true)
        if (!reusable) {
            destination.parentFile?.mkdirs()
            destination.delete()
            try {
                api.downloadAcquisition(acquisition.id).getOrThrow().use { input ->
                    val operationContext = currentCoroutineContext()
                    runInterruptible(Dispatchers.IO) {
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                operationContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= expectedSize) { "APK exceeds its declared size" }
                                output.write(buffer, 0, count)
                                onProgress(total)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }
        currentCoroutineContext().ensureActive()
        onVerifying()
        if (destination.length() != expectedSize || !sha256(destination).equals(expected, true)) {
            destination.delete()
            throw SecurityException("APK size or SHA256 verification failed")
        }
        return destination
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
