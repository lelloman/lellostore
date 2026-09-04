package com.lelloman.store.installation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

internal val selfAdbConnectionMutex = Mutex()

@Singleton
class LegacyAdbInstallationChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstallationChannel {
    override val metadata = InstallationChannelMetadata(
        id = "legacy-adb",
        displayName = "ADB on port 5555",
        requiresUserInteraction = false,
        priority = 10,
    )

    override suspend fun install(request: InstallationRequest): ChannelInstallationResult =
        withContext(Dispatchers.IO) {
            selfAdbConnectionMutex.withLock {
                val adb = try {
                    SelfAdbConnectionManager.getInstance(context)
                } catch (error: Exception) {
                    return@withLock ChannelInstallationResult.Unavailable(
                        "Could not initialize the ADB identity: ${error.message.orUnknown()}"
                    )
                }

                val connected = try {
                    if (adb.isConnected) adb.disconnect()
                    adb.connect(LOOPBACK_HOST, ADB_PORT)
                } catch (error: Exception) {
                    return@withLock ChannelInstallationResult.Unavailable(
                        "Cannot connect to $LOOPBACK_HOST:$ADB_PORT: ${error.message.orUnknown()}"
                    )
                }
                if (!connected) {
                    return@withLock ChannelInstallationResult.Unavailable(
                        "No authorized ADB service at $LOOPBACK_HOST:$ADB_PORT"
                    )
                }

                installApkOverAdb(context, adb, request)
            }
        }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        selfAdbConnectionMutex.withLock {
            runCatching {
                val adb = SelfAdbConnectionManager.getInstance(context)
                if (adb.isConnected) adb.disconnect()
                check(adb.connect(LOOPBACK_HOST, ADB_PORT)) {
                    "No authorized ADB service at $LOOPBACK_HOST:$ADB_PORT"
                }
                verifyAdbShell(adb)
            }
        }
    }

    private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "unknown error"

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val ADB_PORT = 5555
    }
}

internal fun installApkOverAdb(
    context: Context,
    adb: SelfAdbConnectionManager,
    request: InstallationRequest,
): ChannelInstallationResult {
    val response = try {
        adb.openStream(adbInstallCommand(request.apk.length())).use { stream ->
            request.apk.inputStream().use { input ->
                // AdbOutputStream.close() performs another flush. The package service can close
                // immediately after receiving the declared byte count, making that redundant
                // flush throw even though installation already succeeded.
                input.copyTo(stream.openOutputStream())
            }
            readAdbText(stream.openInputStream()).trim()
        }
    } catch (error: IOException) {
        if (installedVersionCode(context, request.packageName) >= request.versionCode.toLong()) {
            return ChannelInstallationResult.Installed
        }
        // Once streaming begins, the package-manager state is ambiguous. Do not submit the same
        // APK through another channel as that could trigger a duplicate installation.
        return ChannelInstallationResult.Failed(
            reason = "ADB install stream failed: ${error.message.orUnknown()}",
            canTryNextChannel = false,
        )
    }

    return parseAdbInstallResponse(response)
}

internal fun adbInstallCommand(apkSize: Long): String {
    require(apkSize > 0) { "APK must not be empty" }
    return "exec:cmd package install -r -S $apkSize"
}

internal fun parseAdbInstallResponse(response: String): ChannelInstallationResult =
    if (response.startsWith("Success")) {
        ChannelInstallationResult.Installed
    } else {
        ChannelInstallationResult.Failed(
            reason = response.ifEmpty { "ADB package manager returned no result" },
            canTryNextChannel = false,
        )
    }

/**
 * libadb 3.1.1 can report its remote CLSE packet as an IOException instead of EOF after the
 * final payload has been consumed. Preserve a complete response in that case, while still
 * surfacing a close that happens before the daemon returns any data.
 */
internal fun readAdbText(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    try {
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    } catch (error: IOException) {
        if (output.size() == 0) throw error
    }
    return output.toString(Charsets.UTF_8.name())
}

internal fun verifyAdbShell(adb: SelfAdbConnectionManager): String =
    adb.openStream("shell:id; getprop ro.product.model").use { stream ->
        readAdbText(stream.openInputStream()).trim()
    }.also { output ->
        check(output.contains("uid=2000(shell)")) {
            "ADB did not provide a shell"
        }
    }

private fun installedVersionCode(context: Context, packageName: String): Long = try {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
} catch (_: PackageManager.NameNotFoundException) {
    -1
}

private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "unknown error"
