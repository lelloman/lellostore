package com.lelloman.store.domain.remote

import com.lelloman.store.domain.model.AppVersion
import kotlinx.coroutines.flow.StateFlow

data class UsbReceiver(val id: String, val name: String)
data class ReceiverInfo(
    val identity: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val userId: Int,
    val addresses: List<String>,
)

enum class RemoteConnectionPhase {
    UNSUPPORTED, DISCONNECTED, PERMISSION, CONNECTING, AUTHORIZING, READY, RESTARTING, ERROR,
}
enum class RemoteOperationPhase { IDLE, DOWNLOADING, VERIFYING, TRANSFERRING, INSTALLING, CONFIGURING }
enum class RemoteInstallOutcome { INSTALLED, ALREADY_INSTALLED, FAILED, UNCERTAIN, CANCELLED }
data class RemoteInstallResult(val packageName: String, val name: String, val outcome: RemoteInstallOutcome, val detail: String = "")
data class RemoteAppChoice(val packageName: String, val name: String, val version: AppVersion)

data class RemoteDeviceState(
    val phase: RemoteConnectionPhase = RemoteConnectionPhase.DISCONNECTED,
    val devices: List<UsbReceiver> = emptyList(),
    val receiver: ReceiverInfo? = null,
    val operation: RemoteOperationPhase = RemoteOperationPhase.IDLE,
    val activeApp: String? = null,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val results: List<RemoteInstallResult> = emptyList(),
    val pendingCount: Int = 0,
    val message: String? = null,
    val canLaunchStore: Boolean = false,
    val tcpPort: Int? = null,
) {
    val busy: Boolean get() = operation != RemoteOperationPhase.IDLE || phase in setOf(
        RemoteConnectionPhase.PERMISSION, RemoteConnectionPhase.CONNECTING,
        RemoteConnectionPhase.AUTHORIZING, RemoteConnectionPhase.RESTARTING,
    )
}

interface RemoteDeviceSession {
    val state: StateFlow<RemoteDeviceState>
    fun refreshDevices()
    fun connect(deviceId: String)
    fun disconnect()
}

interface RemoteDeviceOperations {
    suspend fun loadCatalog(): Result<List<RemoteAppChoice>>
    fun copyStore()
    fun launchStore()
    fun enableTcpIp()
    fun disableTcpIp()
    fun testTcpIp()
    fun installApps(apps: List<RemoteAppChoice>)
    fun resumeBatch()
    fun cancel()
    fun clearResults()
}
