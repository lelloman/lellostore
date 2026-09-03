package com.lelloman.store.installation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyAdbInstallationChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstallationChannel {
    private val installMutex = Mutex()

    override val metadata = InstallationChannelMetadata(
        id = "legacy-adb",
        displayName = "ADB on port 5555",
        requiresUserInteraction = false,
        priority = 10,
    )

    override suspend fun install(request: InstallationRequest): ChannelInstallationResult =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
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

                installApk(adb, request)
            }
        }

    private fun installApk(
        adb: SelfAdbConnectionManager,
        request: InstallationRequest,
    ): ChannelInstallationResult {
        val response = try {
            adb.openStream("exec:cmd package install -S ${request.apk.length()}").use { stream ->
                request.apk.inputStream().use { input ->
                    // AdbOutputStream.close() performs another flush. The package service can
                    // close its side immediately after receiving the declared byte count, making
                    // that redundant flush throw even though installation already succeeded.
                    input.copyTo(stream.openOutputStream())
                }
                stream.openInputStream().bufferedReader().use { it.readText() }.trim()
            }
        } catch (error: IOException) {
            if (installedVersionCode(request.packageName) >= request.versionCode.toLong()) {
                return ChannelInstallationResult.Installed
            }
            // Once streaming begins, the package-manager state is ambiguous. Do not submit the
            // same APK through the fallback installer as that could trigger a duplicate install.
            return ChannelInstallationResult.Failed(
                reason = "ADB install stream failed: ${error.message.orUnknown()}",
                canTryNextChannel = false,
            )
        }

        return parseInstallResponse(response)
    }

    internal fun parseInstallResponse(response: String): ChannelInstallationResult =
        if (response.startsWith("Success")) {
            ChannelInstallationResult.Installed
        } else {
            ChannelInstallationResult.Failed(
                reason = response.ifEmpty { "ADB package manager returned no result" },
                canTryNextChannel = false,
            )
        }

    private fun installedVersionCode(packageName: String): Long = try {
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

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val ADB_PORT = 5555
    }
}
