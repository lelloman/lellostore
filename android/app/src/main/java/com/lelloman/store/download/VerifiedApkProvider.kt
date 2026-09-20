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
    ): File {
        val expected = version.sha256
        require(expected != null && expected.matches(Regex("[a-fA-F0-9]{64}"))) { "Missing APK verification metadata" }
        require(version.size > 0) { "Invalid APK size" }
        val reusable = destination.isFile && destination.length() == version.size && sha256(destination).equals(expected, true)
        if (!reusable) {
            destination.parentFile?.mkdirs()
            destination.delete()
            try {
                api.downloadApk(packageName, version.versionCode).getOrThrow().use { input ->
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
                                check(total <= version.size) { "APK exceeds its declared size" }
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
        if (destination.length() != version.size || !sha256(destination).equals(expected, true)) {
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
