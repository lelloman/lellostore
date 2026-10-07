package com.lelloman.store.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.lelloman.store.logger.AuditLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Observes the process lifetime without keeping the CPU awake or probing the network. */
@Singleton
class SystemConnectivityLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audit: AuditLog,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = network("available", network, null)
        override fun onLost(network: Network) = network("lost", network, null)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            network("capabilities", network, capabilities)
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            audit.record("system.network_blocked", mapOf("network" to network.toString(), "blocked" to blocked))
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = power(intent.action ?: "unknown")
    }

    fun start() {
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            val active = connectivity.activeNetwork
            network("initial", active, active?.let(connectivity::getNetworkCapabilities))
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            power("initial")
        }.onFailure { audit.record("system.observer_failed", mapOf("error_type" to it.javaClass.simpleName)) }
    }

    private fun power(trigger: String) {
        audit.record("system.power", mapOf("trigger" to trigger, "interactive" to power.isInteractive,
            "idle" to power.isDeviceIdleMode, "power_save" to power.isPowerSaveMode,
            "battery_exempt" to power.isIgnoringBatteryOptimizations(context.packageName)))
    }

    private fun network(trigger: String, network: Network?, caps: NetworkCapabilities?) {
        audit.record("system.network", mapOf("trigger" to trigger, "network" to network?.toString(),
            "internet" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            "validated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "captive_portal" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            "metered" to caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            "wifi" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            "cellular" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            "vpn" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))
    }
}
