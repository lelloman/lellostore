package com.lelloman.store.installation

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

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

    override suspend fun install(request: InstallationRequest): ChannelInstallationResult =
        withContext(Dispatchers.IO) {
            request.audit("adb.lock_waiting", emptyMap())
            selfAdbConnectionMutex.withLock {
                request.audit("adb.lock_acquired", emptyMap())
                if (isWirelessDebuggingOff()) {
                    request.audit("adb.wireless_disabled", emptyMap())
                    return@withLock ChannelInstallationResult.Unavailable(WIRELESS_DEBUGGING_OFF)
                }
                request.audit("adb.connect_started", emptyMap())
                val adb = try {
                    connect()
                } catch (error: Exception) {
                    return@withLock ChannelInstallationResult.Unavailable(
                        "Cannot connect to Wireless debugging: ${error.message ?: "unknown error"}"
                    )
                } ?: return@withLock ChannelInstallationResult.Unavailable(
                    "No authorized Wireless debugging endpoint was discovered"
                )
                request.audit("adb.connected", emptyMap())
                installApkOverAdb(context, adb, request)
            }
        }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        selfAdbConnectionMutex.withLock {
            runCatching {
                check(!isWirelessDebuggingOff()) {
                    "$WIRELESS_DEBUGGING_OFF. Enable it in Developer options, then try again"
                }
                val adb = connect()
                    ?: error("No authorized Wireless debugging endpoint was discovered")
                verifyAdbShell(adb)
            }
        }
    }

    suspend fun pairAndTest(pairingCode: String): Result<String> = withContext(Dispatchers.IO) {
        selfAdbConnectionMutex.withLock {
            runCatching {
                require(pairingCode.matches(Regex("\\d{6}"))) {
                    "Enter the six-digit pairing code shown by Android"
                }
                val adb = SelfAdbConnectionManager.getInstance(context)
                if (adb.isConnected) adb.disconnect()
                val endpoint = discoverPairingEndpoint()
                check(adb.pair(endpoint.host, endpoint.port, pairingCode)) {
                    "Android rejected the pairing code"
                }
                check(adb.connectTls(context, DISCOVERY_TIMEOUT_MILLIS)) {
                    "Paired, but could not connect to Wireless debugging"
                }
                verifyAdbShell(adb)
            }
        }
    }

    private fun discoverPairingEndpoint(): PairingEndpoint {
        val endpoint = AtomicReference<PairingEndpoint?>()
        val discovered = CountDownLatch(1)
        val mdns = AdbMdns(
            context,
            AdbMdns.SERVICE_TYPE_TLS_PAIRING,
        ) { address: InetAddress?, port: Int ->
            val host = address?.hostAddress
            if (host != null && port > 0) {
                endpoint.set(PairingEndpoint(host, port))
                discovered.countDown()
            }
        }
        mdns.start()
        try {
            check(discovered.await(DISCOVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Open ‘Pair device with pairing code’ in Android, then try again"
            }
        } finally {
            mdns.stop()
        }
        return checkNotNull(endpoint.get()) {
            "No Wireless debugging pairing endpoint was discovered"
        }
    }

    private fun isWirelessDebuggingOff(): Boolean =
        isWirelessDebuggingKnownOff(context.contentResolver, Build.VERSION.SDK_INT)

    private fun connect(): SelfAdbConnectionManager? {
        val adb = SelfAdbConnectionManager.getInstance(context)
        if (adb.isConnected) adb.disconnect()
        return adb.takeIf { it.connectTls(context, DISCOVERY_TIMEOUT_MILLIS) }
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MILLIS = 15_000L
        const val WIRELESS_DEBUGGING_OFF = "Wireless debugging is off"
    }

    private data class PairingEndpoint(val host: String, val port: Int)
}

/** Settings.Global key backing the Developer options "Wireless debugging" toggle (Android 11+). */
private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

/**
 * True only when Android reports that Wireless debugging is disabled, so mDNS discovery
 * (which otherwise waits for its whole timeout) can be skipped. Before Android 11 the
 * setting does not exist and an unreadable setting is treated as unknown: both return
 * false and discovery runs as before.
 */
internal fun isWirelessDebuggingKnownOff(
    resolver: ContentResolver,
    sdkInt: Int,
    readGlobalInt: (ContentResolver, String, Int) -> Int = Settings.Global::getInt,
): Boolean {
    if (sdkInt < Build.VERSION_CODES.R) return false
    val enabled = try {
        readGlobalInt(resolver, ADB_WIFI_ENABLED, 0)
    } catch (_: Exception) {
        return false
    }
    return enabled == 0
}
