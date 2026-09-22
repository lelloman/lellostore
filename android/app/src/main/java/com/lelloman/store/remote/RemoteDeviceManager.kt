package com.lelloman.store.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.preferences.AppUpdatePolicyResolver
import com.lelloman.store.domain.preferences.AutoUpdateOverride
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.domain.remote.*
import com.lelloman.store.download.VerifiedApkProvider
import com.lelloman.store.logger.Logger
import com.lelloman.store.remoteadb.AdbConnection
import com.lelloman.store.remoteadb.AdbIdentity
import com.lelloman.store.remoteadb.TcpAdbTransport
import com.lelloman.store.remoteadb.UsbAdbTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteDeviceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apps: AppsRepository,
    private val auth: AuthStore,
    private val preferences: UserPreferencesStore,
    private val apks: VerifiedApkProvider,
    private val logger: Logger,
) : RemoteDeviceSession, RemoteDeviceOperations {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RemoteDeviceState())
    override val state = mutableState.asStateFlow()
    private val journal = RemoteInstallJournal(File(context.noBackupFilesDir, "remote-install.json"))
    private val installer = RemotePackageInstaller(journal)
    private val serviceStarts = RemoteServiceStarts()
    @Volatile private var foregroundReady = CompletableDeferred<Unit>()
    private val permissionAction = "${context.packageName}.REMOTE_USB_PERMISSION"
    private val identity by lazy { AdbIdentity.load(File(context.noBackupFilesDir, "remote-adb-private.key")) }
    @Volatile private var connection: AdbConnection? = null
    @Volatile private var probeConnection: AdbConnection? = null
    @Volatile private var job: Job? = null
    @Volatile private var retiringJob: Job? = null
    @Volatile private var generation = 0
    @Volatile private var deviceId: String? = null
    @Volatile private var pending = emptyList<RemoteAppChoice>()
    @Volatile private var pendingReceiver: String? = null
    @Volatile private var requestedDeviceId: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val id = requestedDeviceId ?: return
                    requestedDeviceId = null
                    val device = usb.deviceList[id]
                    if (device != null && usb.hasPermission(device)) beginConnection(id)
                    else mutableState.update { it.copy(phase = RemoteConnectionPhase.ERROR, message = "USB access was not granted. Tap Connect to try again.") }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (deviceId != null && !usb.deviceList.containsKey(deviceId) && state.value.phase != RemoteConnectionPhase.RESTARTING) {
                        stop(false, "USB disconnected. Reconnect the receiver to continue.")
                    }
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshDevices()
    }

    override fun refreshDevices() {
        val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val devices = if (supported) usb.deviceList.values.filter { UsbAdbTransport.adbInterface(it) != null }
            .map { UsbReceiver(it.deviceName, it.productName ?: "Android USB device") }.sortedBy { it.id } else emptyList()
        mutableState.update { it.copy(devices = devices, phase = if (!supported) RemoteConnectionPhase.UNSUPPORTED else it.phase) }
    }

    @Synchronized override fun connect(deviceId: String) {
        if (state.value.busy) return
        val device = usb.deviceList[deviceId] ?: return refreshDevices()
        stop(false, null)
        this.deviceId = deviceId
        if (usb.hasPermission(device)) beginConnection(deviceId) else {
            requestedDeviceId = deviceId
            mutableState.update { it.copy(phase = RemoteConnectionPhase.PERMISSION, message = null) }
            val permission = PendingIntent.getBroadcast(context, 0, Intent(permissionAction).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            usb.requestPermission(device, permission)
        }
    }

    private fun beginConnection(id: String) {
        deviceId = id
        val currentGeneration = generation
        val ready = CompletableDeferred<Unit>()
        foregroundReady = ready
        mutableState.update { it.copy(phase = RemoteConnectionPhase.CONNECTING, message = null) }
        try {
            serviceStarts.request {
                ContextCompat.startForegroundService(context, Intent(context, RemoteDeviceService::class.java)
                    .putExtra(RemoteDeviceService.SESSION_GENERATION, currentGeneration))
            }
        } catch (error: Exception) {
            mutableState.update { it.copy(phase = RemoteConnectionPhase.ERROR, message = error.message) }
            return
        }
        job = scope.launch {
            try {
                ready.await()
                retiringJob?.join()
                ensureActive()
                val device = usb.deviceList[id] ?: throw IOException("USB device is no longer attached")
                val adb = AdbConnection(UsbAdbTransport.open(usb, device))
                connection = adb
                adb.authenticate(identity) { ensureActive(); mutableState.update { it.copy(phase = RemoteConnectionPhase.AUTHORIZING) } }
                ensureActive()
                val info = readReceiver(adb)
                check(info.sdk >= 24) { "Android 7 or newer is required on the receiver" }
                val previous = installer.reconcile(adb, info)
                ensureActive()
                val port = readTcpPort(adb)
                val storeInstalled = (RemotePackageInstaller.installedVersion(adb, info.userId, context.packageName) ?: -1) >= 0
                ensureActive()
                mutableState.update { it.copy(phase = RemoteConnectionPhase.READY, receiver = info,
                    operation = RemoteOperationPhase.IDLE, tcpPort = port,
                    results = previous?.let { result -> it.results.filterNot { old -> old.packageName == result.packageName } + result } ?: it.results,
                    canLaunchStore = storeInstalled,
                    message = if (pending.isNotEmpty()) "Connected. Review the receiver and resume the remaining apps." else null) }
                logger.audit("remote.connected", mapOf("sdk" to info.sdk))
            } catch (error: Exception) {
                if (currentGeneration == generation) failConnection(error)
            } finally {
                if (currentGeneration == generation) job = null
            }
        }
    }

    override fun disconnect() = stop(true, null)

    @Synchronized private fun stop(clearPending: Boolean, message: String?) {
        generation++
        requestedDeviceId = null
        job?.cancel()
        retiringJob = job ?: retiringJob
        job = null
        connection?.closeQuietly()
        probeConnection?.closeQuietly()
        probeConnection = null
        connection = null
        deviceId = null
        if (clearPending) {
            pending = emptyList()
            pendingReceiver = null
        }
        val record = runCatching { journal.read() }.getOrNull()
        mutableState.update { old ->
            val interrupted = record?.let { RemoteInstallResult(it.packageName, it.name,
                if (it.committing) RemoteInstallOutcome.UNCERTAIN else RemoteInstallOutcome.CANCELLED,
                "Reconnect this receiver to check the interrupted installation") }
            old.copy(phase = RemoteConnectionPhase.DISCONNECTED, receiver = null, operation = RemoteOperationPhase.IDLE,
                activeApp = null, bytes = 0, totalBytes = 0, canLaunchStore = false, tcpPort = null,
                pendingCount = pending.size, message = message,
                results = if (interrupted == null) old.results else old.results.filterNot { it.packageName == interrupted.packageName } + interrupted)
        }
    }

    private fun failConnection(error: Exception) {
        connection?.closeQuietly()
        connection = null
        mutableState.update { it.copy(phase = RemoteConnectionPhase.ERROR, operation = RemoteOperationPhase.IDLE,
            activeApp = null, message = error.message ?: "USB operation failed", pendingCount = pending.size) }
        logger.audit("remote.connection_failed", mapOf("error_type" to error.javaClass.simpleName))
    }

    internal fun serviceStartDelivered(expectedGeneration: Int) {
        serviceStarts.delivered()
        if (generation == expectedGeneration) foregroundReady.complete(Unit)
    }

    internal fun stopServiceIfIdle(stop: () -> Unit) = serviceStarts.stopIfIdle(
        isActive = { state.value.phase in setOf(RemoteConnectionPhase.CONNECTING,
            RemoteConnectionPhase.AUTHORIZING, RemoteConnectionPhase.READY, RemoteConnectionPhase.RESTARTING) },
        stop = stop,
    )

    fun serviceStopped(expectedGeneration: Int) {
        // A previous service instance may finish stopping after the user reconnects.
        if (generation != expectedGeneration) return
        if (state.value.phase in setOf(RemoteConnectionPhase.READY, RemoteConnectionPhase.CONNECTING,
                RemoteConnectionPhase.AUTHORIZING, RemoteConnectionPhase.RESTARTING)) {
            stop(false, "USB service stopped. Reconnect to continue.")
        }
    }

    private fun operate(phase: RemoteOperationPhase = RemoteOperationPhase.CONFIGURING, block: suspend (AdbConnection, ReceiverInfo) -> Unit) {
        synchronized(this) {
            val info = state.value.receiver ?: return
            val adb = connection ?: return
            if (state.value.busy || state.value.phase != RemoteConnectionPhase.READY) return
            val currentGeneration = generation
            val operationId = UUID.randomUUID().toString()
            mutableState.update { it.copy(operation = phase, message = null, bytes = 0, totalBytes = 0) }
            job = scope.launch {
                logger.audit("remote.operation_started", mapOf("operation_id" to operationId))
                try {
                    block(adb, info)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    if (currentGeneration == generation) failConnection(error)
                } catch (error: Exception) {
                    if (currentGeneration == generation) mutableState.update { it.copy(message = error.message) }
                } finally {
                    if (currentGeneration == generation) {
                        mutableState.update { it.copy(operation = RemoteOperationPhase.IDLE, activeApp = null, pendingCount = pending.size) }
                        job = null
                    }
                    logger.audit("remote.operation_finished", mapOf("operation_id" to operationId))
                }
            }
        }
    }

    override suspend fun loadCatalog(): Result<List<RemoteAppChoice>> = withContext(Dispatchers.IO) {
        try {
            requireLogin()
            apps.refreshApps().getOrThrow()
            val choices = apps.watchApps().first().mapNotNull { app ->
                val detail = apps.refreshApp(app.packageName).getOrThrow()
                val policy = AppUpdatePolicyResolver.resolve(false, preferences.releaseChannelDefault.value,
                    AutoUpdateOverride.Disabled, preferences.releaseChannelOverride(app.packageName).first(),
                    detail.accessLevel, detail.versions.any { it.isBeta })
                detail.versions.filter { policy.effectiveChannel == ReleaseChannel.Beta || !it.isBeta }
                    .maxByOrNull { it.versionCode }?.let { RemoteAppChoice(app.packageName, app.name, it) }
            }.sortedBy { it.name.lowercase() }
            Result.success(choices)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    override fun copyStore() = operate(RemoteOperationPhase.PREPARING) { adb, info ->
        val application = context.applicationInfo
        check(application.splitSourceDirs.isNullOrEmpty()) { "Copying a split installation is not supported. Use a universal LelloStore APK." }
        @Suppress("DEPRECATION") val ownPackage = context.packageManager.getPackageInfo(context.packageName, 0)
        check(info.sdk >= application.minSdkVersion) { "This LelloStore build requires a newer Android version" }
        val snapshot = File.createTempFile("store-copy-", ".apk", remoteCache())
        try {
            File(application.sourceDir).inputStream().use { source -> snapshot.outputStream().use { source.copyTo(it) } }
            currentCoroutineContext().ensureActive()
            val result = install(adb, info, snapshot, context.packageName, "LelloStore", PackageInfoCompat.getLongVersionCode(ownPackage))
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(canLaunchStore = true, results = it.results + result) }
        } finally { snapshot.delete() }
    }

    override fun launchStore() = operate(RemoteOperationPhase.LAUNCHING) { adb, info ->
        RemotePackageInstaller.checkUser(adb, info.userId)
        val pkg = context.packageName
        RemotePackageInstaller.requirePackageName(pkg)
        val response = adb.execute("shell:am start --user ${info.userId} -n $pkg/com.lelloman.store.MainActivity")
        check(!response.contains("Error", true) && !response.contains("Exception")) { response.take(1000) }
        mutableState.update { it.copy(message = "LelloStore opened on the receiver. Complete setup there.") }
    }

    override fun installApps(apps: List<RemoteAppChoice>) {
        if (state.value.phase != RemoteConnectionPhase.READY || state.value.busy || apps.isEmpty() || pending.isNotEmpty()) return
        pending = apps.distinctBy { it.packageName }
        pendingReceiver = state.value.receiver?.identity
        mutableState.update { it.copy(pendingCount = pending.size) }
        resumeBatch()
    }

    override fun resumeBatch() = operate { adb, info ->
        requireLogin()
        check(info.identity == pendingReceiver) { "Reconnect the receiver selected for this batch" }
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            requireLogin()
            val app = pending.first()
            val file = File.createTempFile("catalog-", ".apk", remoteCache())
            try {
                RemotePackageInstaller.checkUser(adb, info.userId)
                if ((RemotePackageInstaller.installedVersion(adb, info.userId, app.packageName) ?: -1) >= app.version.versionCode) {
                    currentCoroutineContext().ensureActive()
                    mutableState.update { it.copy(results = it.results + RemoteInstallResult(app.packageName, app.name, RemoteInstallOutcome.ALREADY_INSTALLED)) }
                    pending = pending.drop(1)
                    mutableState.update { it.copy(pendingCount = pending.size) }
                    continue
                }
                check(app.version.minSdk <= info.sdk) { "${app.name} requires Android API ${app.version.minSdk}; the receiver has API ${info.sdk}" }
                mutableState.update { it.copy(activeApp = app.name, operation = RemoteOperationPhase.DOWNLOADING, bytes = 0, totalBytes = app.version.size) }
                apks.prepare(app.packageName, app.version, file,
                    onMetadata = { size -> mutableState.update { it.copy(totalBytes = size) } },
                    onProgress = { bytes -> mutableState.update { it.copy(bytes = bytes) } },
                    onVerifying = { mutableState.update { it.copy(operation = RemoteOperationPhase.VERIFYING) } })
                requireLogin()
                val result = install(adb, info, file, app.packageName, app.name, app.version.versionCode.toLong())
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(results = it.results + result) }
            } catch (error: CancellationException) { throw error }
            catch (error: InstallUncertainException) {
                mutableState.update { it.copy(results = it.results + RemoteInstallResult(app.packageName, app.name, RemoteInstallOutcome.UNCERTAIN, error.message.orEmpty())) }
                throw error
            } catch (error: IOException) { throw error }
            catch (error: Exception) {
                requireLogin()
                if (journal.read() != null) throw IOException("Reconnect the receiver to clear its interrupted installation", error)
                mutableState.update { it.copy(results = it.results + RemoteInstallResult(app.packageName, app.name, RemoteInstallOutcome.FAILED, error.message.orEmpty())) }
            } finally { file.delete() }
            pending = pending.drop(1)
            mutableState.update { it.copy(pendingCount = pending.size) }
        }
    }

    private suspend fun install(adb: AdbConnection, info: ReceiverInfo, file: File, pkg: String, name: String, version: Long): RemoteInstallResult {
        val operationContext = currentCoroutineContext()
        operationContext.ensureActive()
        mutableState.update { it.copy(operation = RemoteOperationPhase.TRANSFERRING, activeApp = name, bytes = 0, totalBytes = file.length()) }
        return installer.install(adb, info, file, pkg, name, version,
            progress = { bytes -> operationContext.ensureActive(); mutableState.update { it.copy(bytes = bytes) } },
            committing = { operationContext.ensureActive(); mutableState.update { it.copy(operation = RemoteOperationPhase.INSTALLING) } })
    }

    override fun enableTcpIp() = changeTransport(true)
    override fun disableTcpIp() = changeTransport(false)

    private fun changeTransport(tcp: Boolean) = operate(if (tcp) RemoteOperationPhase.ENABLING_TCP else RemoteOperationPhase.DISABLING_TCP) { adb, info ->
        val id = deviceId
        val serial = usb.deviceList[id]?.let { runCatching { it.serialNumber }.getOrNull() }
        mutableState.update { it.copy(phase = RemoteConnectionPhase.RESTARTING) }
        // A daemon restart can close the connection before the response is delivered.
        runCatching { adb.execute(if (tcp) "tcpip:5555" else "usb:") }
        adb.closeQuietly()
        connection = null
        var reopened: AdbConnection? = null
        val deadline = System.nanoTime() + 30_000_000_000L
        while (reopened == null && System.nanoTime() < deadline) {
            delay(750)
            val device = usb.deviceList.values.firstOrNull {
                usb.hasPermission(it) && (it.deviceName == id || (serial != null && runCatching { it.serialNumber == serial }.getOrDefault(false)))
            } ?: continue
            val candidate = runCatching { AdbConnection(UsbAdbTransport.open(usb, device)) }.getOrNull() ?: continue
            connection = candidate
            try {
                candidate.authenticate(identity, timeoutMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1))
                currentCoroutineContext().ensureActive()
                check(readReceiver(candidate).identity == info.identity) { "A different receiver is connected" }
                reopened = candidate
                deviceId = device.deviceName
            } catch (error: Exception) {
                candidate.closeQuietly(); connection = null
                currentCoroutineContext().ensureActive()
            }
        }
        val active = reopened ?: throw IOException("ADB restarted. Reconnect the USB cable and tap Connect to verify the setting.")
        val port = readTcpPort(active)
        if (!(if (tcp) port == 5555 else port == null)) throw IOException("Receiver did not confirm the requested ADB mode")
        val refreshed = readReceiver(active)
        mutableState.update { it.copy(phase = RemoteConnectionPhase.READY, receiver = refreshed, tcpPort = port,
            message = if (tcp) "ADB port 5555 is enabled. Network reachability has not been tested." else "Legacy network ADB is disabled. USB mode is active.") }
    }

    override fun testTcpIp() = operate(RemoteOperationPhase.TESTING_TCP) { _, info ->
        check(info.addresses.isNotEmpty()) { "The receiver has no network address. Connect it to a network and reconnect USB to refresh." }
        var reachable = false
        for (address in info.addresses) {
            currentCoroutineContext().ensureActive()
            try {
                AdbConnection(TcpAdbTransport(address)).use { network ->
                    probeConnection = network
                    network.authenticate(identity, timeoutMs = 5_000)
                    check(readReceiver(network).identity == info.identity) { "Network endpoint is a different receiver" }
                    reachable = true
                }
            } catch (_: IOException) { }
            finally { probeConnection = null }
            currentCoroutineContext().ensureActive()
            if (reachable) break
        }
        mutableState.update { it.copy(message = if (reachable) "Receiver authenticated over network ADB. App installs will still use USB." else "Network ADB is not reachable from this phone. USB tools remain available.") }
    }

    override fun cancel() = stop(true, "Stopped. Reconnect to check any interrupted installation.")
    override fun clearResults() { mutableState.update { it.copy(results = emptyList(), message = null) } }
    private fun remoteCache() = File(context.cacheDir, "remote-apks").apply { mkdirs() }
    private fun requireLogin() { check(auth.authState.value is AuthState.Authenticated) { "Sign in to use the catalog; USB tools still work offline" } }
    private fun readTcpPort(adb: AdbConnection): Int? = adb.execute("shell:getprop service.adb.tcp.port").toIntOrNull()?.takeIf { it > 0 }

    private fun readReceiver(adb: AdbConnection): ReceiverInfo {
        fun property(name: String) = adb.execute("shell:getprop $name")
        val serial = property("ro.serialno").takeUnless { it.isBlank() || it == "unknown" }
            ?: property("ro.boot.serialno").takeUnless { it.isBlank() || it == "unknown" }
        val id = serial?.let { "serial:$it" } ?: adb.execute("shell:cat /proc/sys/kernel/random/boot_id")
            .takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) }?.let { "boot:$it" }
            ?: throw IOException("Cannot establish a stable receiver identity")
        val model = property("ro.product.model")
        val release = property("ro.build.version.release")
        val sdk = property("ro.build.version.sdk").toIntOrNull() ?: throw IOException("Cannot read receiver Android version")
        val user = adb.execute("shell:am get-current-user").toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Cannot identify the receiver's Android user")
        val addresses = Regex("\\binet (\\d+\\.\\d+\\.\\d+\\.\\d+)/")
            .findAll(adb.execute("shell:ip -o -4 addr show scope global")).map { it.groupValues[1] }
            .filter { address -> address.split('.').all { (it.toIntOrNull() ?: -1) in 0..255 } && !address.startsWith("127.") }
            .distinct().toList()
        return ReceiverInfo(id, model, release, sdk, user, addresses)
    }

    private fun AdbConnection.closeQuietly() { runCatching { close() } }
}
