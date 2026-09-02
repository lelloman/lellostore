package com.lelloman.store.selfadb

import android.content.Context
import com.lelloman.store.installation.ChannelInstallationResult
import com.lelloman.store.installation.InstallationChannel
import com.lelloman.store.installation.InstallationChannelMetadata
import com.lelloman.store.installation.InstallationRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyAdbInstallationChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstallationChannel {
    override val metadata = InstallationChannelMetadata(
        id = "legacy-adb",
        displayName = "Legacy ADB",
        requiresUserInteraction = false,
        priority = 10,
    )

    override suspend fun install(request: InstallationRequest): ChannelInstallationResult {
        val adb = SelfAdbConnectionManager.getInstance(context)
        val connected = try {
            if (adb.isConnected) adb.disconnect()
            adb.connect(LOOPBACK_HOST, LEGACY_ADB_PORT)
        } catch (error: Exception) {
            return ChannelInstallationResult.Unavailable(
                "Cannot connect to $LOOPBACK_HOST:$LEGACY_ADB_PORT: ${error.message}"
            )
        }
        if (!connected) {
            return ChannelInstallationResult.Unavailable(
                "No authorized ADB service at $LOOPBACK_HOST:$LEGACY_ADB_PORT"
            )
        }
        return adb.streamInstall(request)
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val LEGACY_ADB_PORT = 5555
    }
}

@Singleton
class WirelessTlsAdbInstallationChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstallationChannel {
    override val metadata = InstallationChannelMetadata(
        id = "wireless-tls-adb",
        displayName = "Wireless debugging",
        requiresUserInteraction = false,
        priority = 20,
    )

    override suspend fun install(request: InstallationRequest): ChannelInstallationResult {
        val adb = SelfAdbConnectionManager.getInstance(context)
        val connected = try {
            if (adb.isConnected) adb.disconnect()
            adb.connectTls(context, TLS_DISCOVERY_TIMEOUT_MILLIS)
        } catch (error: Exception) {
            return ChannelInstallationResult.Unavailable(
                "Cannot connect to paired Wireless debugging: ${error.message}"
            )
        }
        if (!connected) {
            return ChannelInstallationResult.Unavailable(
                "No paired Wireless debugging endpoint was discovered"
            )
        }
        return adb.streamInstall(request)
    }

    private companion object {
        const val TLS_DISCOVERY_TIMEOUT_MILLIS = 15_000L
    }
}

private fun SelfAdbConnectionManager.streamInstall(
    request: InstallationRequest,
): ChannelInstallationResult {
    val response = try {
        openStream("exec:cmd package install -S ${request.apk.length()}").use { stream ->
            request.apk.inputStream().use { input ->
                stream.openOutputStream().use { output -> input.copyTo(output) }
            }
            stream.openInputStream().bufferedReader().use { it.readText() }.trim()
        }
    } catch (error: IOException) {
        // Once streaming begins, the install state is ambiguous. Do not submit it a second time.
        return ChannelInstallationResult.Failed(
            reason = "ADB install stream failed: ${error.message}",
            canTryNextChannel = false,
        )
    }

    return if (response.startsWith("Success")) {
        ChannelInstallationResult.Installed
    } else {
        ChannelInstallationResult.Failed(
            reason = response.ifEmpty { "ADB package manager returned no result" },
            canTryNextChannel = false,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SelfAdbInstallationChannelModule {
    @Binds
    @IntoSet
    abstract fun bindLegacyAdbChannel(impl: LegacyAdbInstallationChannel): InstallationChannel

    @Binds
    @IntoSet
    abstract fun bindWirelessTlsAdbChannel(
        impl: WirelessTlsAdbInstallationChannel,
    ): InstallationChannel
}
