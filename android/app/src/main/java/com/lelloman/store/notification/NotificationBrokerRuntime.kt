package com.lelloman.store.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.updates.LocalUpdateRelay
import com.lelloman.store.worker.WorkManagerInitializer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.random.Random

@Singleton
class NotificationBrokerRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthStore,
    private val config: ConfigStore,
    private val updates: WorkManagerInitializer,
    private val relay: LocalUpdateRelay,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).pingInterval(0, TimeUnit.SECONDS).build()
    internal val db = PrivateStore(context, "unifiedpush-broker")
    private val credentials = CredentialFile(context, "unifiedpush-device")
    private val pendingRevocations = CredentialFile(context, "unifiedpush-revocations")
    private val lifecycleEpoch = java.util.concurrent.atomic.AtomicLong()
    private val mutex = Mutex()
    private val deliverySlots = Semaphore(4)
    private val delivering = mutableSetOf<String>()
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val networkManager = context.getSystemService(ConnectivityManager::class.java)
    private val mutableStatus = MutableStateFlow("Disabled")
    val status = mutableStatus.asStateFlow()
    @Volatile private var enabled = false
    @Volatile private var socket: WebSocket? = null
    private var ready = false
    private var activeRoutes = emptySet<String>()
    private val incomingRoutes = mutableSetOf<String>()
    private val revokedRoutes = mutableSetOf<String>()
    private var device: JSONObject? = null
    private var serverUrl = ""
    private var connectionEpoch = ""
    private var authExpires = 0L
    private var connectedAt = 0L
    private var lastIncoming = 0L
    private var lastTraffic = 0L
    private var pingAt = 0L
    private var pingIdle = 0L
    private var pingLate = 0L
    private var pingNonce: String? = null
    private var scheduledAt = 0L
    private var retryAt = 0L
    private var retryDelay = 1000L
    private var network: Network? = null
    private var networkKind = "other"
    private var heartbeat = AdaptiveHeartbeat()
    private var candidateIdleFailure: Network? = null
    private var callbackRegistered = false
    private val alarmIntent get() = PendingIntent.getBroadcast(context, 8124, Intent(context, BrokerAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch { pulse() } }
        override fun onLost(network: Network) { scope.launch { pulse() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { scope.launch { pulse() } }
    }

    init {
        if (db.setting("legacy-cleared") == null) {
            val legacy = PrivateStore(context, "notification-broker")
            try { legacy.clear() } finally { legacy.close() }
            CredentialFile(context, "notification-device").clear()
            CredentialFile(context, "notification-revocations").clear()
            db.setting("legacy-cleared", "true")
        }
    }

    fun start(url: String) {
        if (!url.startsWith("https://")) { mutableStatus.value = "HTTPS is required"; return }
        val lifecycle = lifecycleEpoch.incrementAndGet()
        scope.launch {
            mutex.withLock {
                if (lifecycle != lifecycleEpoch.get()) return@launch
                if (enabled && serverUrl == url.trimEnd('/')) return@withLock
                closeSocket()
                serverUrl = url.trimEnd('/')
                enabled = true
                setAvailable(true)
                if (!callbackRegistered) { networkManager.registerDefaultNetworkCallback(networkCallback); callbackRegistered = true }
                retryAt = 0
            }
            revokeRetiredDevices()
            pulse()
        }
    }
    fun stop() {
        val lifecycle = lifecycleEpoch.incrementAndGet()
        enabled = false
        scope.launch { mutex.withLock {
            if (lifecycle != lifecycleEpoch.get()) return@withLock
            closeSocket(); alarm.cancel(alarmIntent)
            if (callbackRegistered) { networkManager.unregisterNetworkCallback(networkCallback); callbackRegistered = false }
            mutableStatus.value = "Disabled"
        } }
    }
    suspend fun signedOut() {
        lifecycleEpoch.incrementAndGet()
        enabled = false
        mutex.withLock {
            closeSocket(); alarm.cancel(alarmIntent)
            val old = device ?: credentials.read()
            if (old != null) {
                val pending = pendingRevocations.read() ?: JSONObject().put("devices", JSONArray())
                pending.getJSONArray("devices").put(JSONObject().put("url", old.getString("identity").substringBefore('\n')).put("credential", old.getString("credential")))
                pendingRevocations.write(pending)
            }
            (registrations() + db.entries("pending-registration")).distinctBy { it.second.getString("token") }.forEach { (_, r) -> broadcast(r, "UNREGISTERED") }
            db.clear(); credentials.clear(); device = null
            setAvailable(false)
            mutableStatus.value = "Signed out"
        }
        scope.launch { revokeRetiredDevices() }
    }
    private suspend fun revokeRetiredDevices() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = pendingRevocations.read() ?: return@withLock
            val remaining = JSONArray()
            val items = pending.getJSONArray("devices")
            for (i in 0 until items.length()) {
                val retired = items.getJSONObject(i)
                val done = runCatching {
                    http.newCall(Request.Builder().url(retired.getString("url") + "/api/push/v1/devices")
                        .header("Authorization", "Bearer ${retired.getString("credential")}").delete().build()).execute().use { it.isSuccessful || it.code == 401 }
                }.getOrDefault(false)
                if (!done) remaining.put(retired)
            }
            if (remaining.length() == 0) pendingRevocations.clear()
            else pendingRevocations.write(JSONObject().put("devices", remaining))
        }
    }
    fun hasBatteryExemption(): Boolean = power.isIgnoringBatteryOptimizations(context.packageName)

    suspend fun pulse() = mutex.withLock {
        if (!enabled) return@withLock
        if (!hasBatteryExemption()) { closeSocket(); mutableStatus.value = "Allow unrestricted battery use to receive notifications"; return@withLock }
        val current = networkManager.activeNetwork
        val capabilities = current?.let { networkManager.getNetworkCapabilities(it) }
        if (current == null || capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            closeSocket(); network = null; candidateIdleFailure = null; mutableStatus.value = "Waiting for network"; alarm.cancel(alarmIntent); return@withLock
        }
        if (current != network) {
            network = current; closeSocket(); candidateIdleFailure = null; retryAt = 0; retryDelay = 1000
            networkKind = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val cached = db.setting("heartbeat:$networkKind")?.let(::JSONObject)
            heartbeat = AdaptiveHeartbeat(if (cached != null && System.currentTimeMillis() - cached.optLong("at") < 7L * 86400 * 1000) cached.optLong("interval", AdaptiveHeartbeat.INITIAL) else AdaptiveHeartbeat.INITIAL)
        }
        val elapsed = SystemClock.elapsedRealtime()
        if (socket == null) {
            if (elapsed >= retryAt) connect()
            schedule()
            return@withLock
        }
        if (!ready) {
            if (elapsed - connectedAt > 30_000) disconnect("Connection timed out")
            schedule(); return@withLock
        }
        if (pingNonce != null && elapsed - pingAt >= AdaptiveHeartbeat.ACK_TIMEOUT) {
            candidateIdleFailure = if (pingLate <= 10_000 && pingIdle >= heartbeat.interval * 3 / 4) network else null
            disconnect("Heartbeat unanswered"); schedule(); return@withLock
        }
        if (System.currentTimeMillis() / 1000 >= authExpires - 60) {
            val token = auth.getAccessToken()
            if (token == null) { disconnect("Sign-in renewal unavailable"); schedule(); return@withLock }
            if (!sameIdentity(token)) { resetIdentity(); disconnect("Account changed"); schedule(); return@withLock }
            socket?.send(JSONObject().put("kind", "authenticate").put("access_token", token).toString())
            lastTraffic = elapsed
        }
        if (pingNonce == null && elapsed - lastTraffic >= heartbeat.interval) {
            pingNonce = UUID.randomUUID().toString(); pingAt = elapsed; pingIdle = elapsed - lastIncoming
            pingLate = (elapsed - scheduledAt).coerceAtLeast(0)
            socket?.send(JSONObject().put("kind", "ping").put("nonce", pingNonce).toString())
        }
        recoverReceipts()
        schedule()
    }

    private suspend fun connect() {
        mutableStatus.value = "Connecting"
        try {
            val token = auth.getAccessToken() ?: error("Sign-in required")
            val claims = tokenClaims(token)
            val identity = "$serverUrl\n${claims.getString("iss")}\n${claims.getString("sub")}"
            device = credentials.read()
            if (device?.optString("identity") != identity) {
                resetIdentity()
                device = JSONObject().put("identity", identity).put("installation", UUID.randomUUID().toString())
                    .put("credential", ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) })
                credentials.write(device!!)
            }
            val registration = JSONObject().put("installation", device!!.getString("installation"))
                .put("credential", device!!.getString("credential")).put("package", context.packageName)
            request("POST", "/devices", registration, token, false)
            connectedAt = SystemClock.elapsedRealtime()
            lastTraffic = connectedAt; lastIncoming = connectedAt
            val epoch = UUID.randomUUID().toString(); connectionEpoch = epoch
            val wsRequest = Request.Builder().url(serverUrl.replaceFirst("https://", "wss://") + "/api/push/v1/stream")
                .header("Authorization", "Bearer ${device!!.getString("credential")}")
                .header("Sec-WebSocket-Protocol", "lellostore.push.v1").build()
            socket = http.newWebSocket(wsRequest, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { ws.send(JSONObject().put("kind", "authenticate").put("access_token", token).toString()) }
                override fun onMessage(ws: WebSocket, text: String) { scope.launch { mutex.withLock {
                    if (epoch != connectionEpoch || !enabled) return@withLock
                    try { handle(JSONObject(text)); schedule() } catch (_: Exception) { disconnect("Invalid broker response"); schedule() }
                } } }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = failed()
                override fun onClosed(ws: WebSocket, code: Int, reason: String) = failed()
                override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null) }
                private fun failed() { scope.launch { mutex.withLock { if (epoch == connectionEpoch && enabled) { disconnect("Disconnected; reconnecting"); schedule() } } } }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { disconnect("Server or sign-in unavailable") }
    }
    private fun resetIdentity() {
        (registrations() + db.entries("pending-registration")).distinctBy { it.second.getString("token") }.forEach { (_, r) -> broadcast(r, "UNREGISTERED") }
        device?.let { old ->
            val pending = pendingRevocations.read() ?: JSONObject().put("devices", JSONArray())
            pending.getJSONArray("devices").put(JSONObject().put("url", old.getString("identity").substringBefore('\n')).put("credential", old.getString("credential")))
            pendingRevocations.write(pending)
        }
        closeSocket(); db.clear(); credentials.clear(); device = null
    }
    private fun sameIdentity(token: String): Boolean {
        val c = tokenClaims(token)
        return device?.optString("identity") == "$serverUrl\n${c.getString("iss")}\n${c.getString("sub")}"
    }
    private fun tokenClaims(token: String): JSONObject = JSONObject(String(android.util.Base64.decode(token.split('.')[1], android.util.Base64.URL_SAFE), Charsets.UTF_8))
    private fun closeSocket() {
        connectionEpoch = ""; socket?.cancel(); socket = null; ready = false; activeRoutes = emptySet(); pingNonce = null
    }
    private fun disconnect(reason: String) {
        if (SystemClock.elapsedRealtime() - connectedAt >= 300_000 && ready) retryDelay = 1000
        closeSocket(); mutableStatus.value = reason
        retryAt = SystemClock.elapsedRealtime() + Random.nextLong(retryDelay / 2, retryDelay + 1)
        retryDelay = (retryDelay * 2).coerceAtMost(300_000)
    }
    @android.annotation.SuppressLint("ScheduleExactAlarm")
    private fun schedule() {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val next = when {
            socket == null -> retryAt
            !ready -> connectedAt + 30_000
            pingNonce != null -> pingAt + AdaptiveHeartbeat.ACK_TIMEOUT
            else -> minOf(lastTraffic + heartbeat.interval, now + ((authExpires - 60) * 1000 - System.currentTimeMillis()).coerceAtLeast(30_000))
        }
        scheduledAt = maxOf(now + 1000, next)
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, scheduledAt, alarmIntent)
        } else { mutableStatus.value = "Battery exemption or alarm access required"; closeSocket() }
    }
    private fun saveHeartbeat() { db.setting("heartbeat:$networkKind", JSONObject().put("at", System.currentTimeMillis()).put("interval", heartbeat.interval).toString()) }
    private suspend fun handle(frame: JSONObject) {
        val elapsed = SystemClock.elapsedRealtime()
        when (frame.getString("kind")) {
            "ready" -> {
                ready = true; authExpires = frame.getLong("expires_at"); mutableStatus.value = "Connected"
                if (candidateIdleFailure != null) { heartbeat.failed(candidateIdleFailure == network, true, true); saveHeartbeat(); candidateIdleFailure = null }
                recoverReceipts()
            }
            "authenticated" -> authExpires = frame.getLong("expires_at")
            "pong" -> if (frame.optString("nonce") == pingNonce) {
                heartbeat.acknowledged(pingIdle, pingLate); saveHeartbeat(); pingNonce = null
                db.setting("diagnostics", JSONObject().put("heartbeat_ms", heartbeat.interval).put("rtt_ms", elapsed - pingAt).put("alarm_late_ms", pingLate).toString())
            }
            "routes_begin" -> { incomingRoutes.clear(); revokedRoutes.clear() }
            "routes" -> {
                val routes = frame.getJSONArray("routes")
                for (i in 0 until routes.length()) {
                    val r = routes.getJSONObject(i)
                    if (r.optBoolean("revoked")) revokedRoutes.add(r.getString("id"))
                    else if (r.optBoolean("enabled")) incomingRoutes.add(r.getString("id"))
                }
            }
            "routes_end" -> {
                activeRoutes = incomingRoutes.toSet()
                registrations().filter { (_, r) -> r.has("id") && r.getString("id") in revokedRoutes }.forEach { (key, r) ->
                    broadcast(r, "UNREGISTERED"); db.remove(key)
                }
                recoverReceipts()
            }
            "catalog_changed" -> { updates.enqueueImmediateUpdateCheck(); relay.notifyInstalledApps() }
            "delivery" -> deliver(frame.getJSONObject("message"))
            "receipt_ack" -> db.remove("message:${frame.getString("id")}")
        }
        lastIncoming = elapsed; lastTraffic = elapsed
    }
    private fun registrations() = db.entries("registration")
    private fun registration(token: String) = db.entry("registration:$token")?.first
    private fun broadcast(registration: JSONObject, action: String, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent("org.unifiedpush.android.connector.$action").setPackage(registration.getString("package"))
            .putExtra("token", registration.getString("token")).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        extras?.invoke(intent)
        context.sendBroadcast(intent)
    }
    private fun setAvailable(available: Boolean) {
        val pm = context.packageManager
        listOf(UnifiedPushReceiver::class.java, UnifiedPushLinkActivity::class.java).forEach {
            pm.setComponentEnabledSetting(ComponentName(context, it), if (available) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        }
    }
    suspend fun invalidateRegistrations() { signedOut() }
    private fun recoverReceipts() {
        if (!ready) return
        db.prune()
        db.entries("ack").forEach { (_, ack) -> socket?.send(ack.toString()) }
        scope.launch { cleanupRemovedRegistrations() }
    }
    private suspend fun cleanupRemovedRegistrations() = mutex.withLock {
        val token = auth.getAccessToken() ?: return@withLock
        db.entries("unregister").forEach { (key, value) ->
            if (runCatching { request("DELETE", "/subscriptions", JSONObject().put("token", value.getString("token")), token) }.isSuccess) db.remove(key)
        }
        registrations().forEach { (key, r) ->
            if (!validIdentity(r)) { removeRegistration(key, r) }
        }
    }
    private fun validIdentity(r: JSONObject): Boolean = runCatching {
        val known = r.getString("certificate")
        if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.hasSigningCertificate(r.getString("package"), known.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), android.content.pm.PackageManager.CERT_INPUT_SHA256)
        } else SigningIdentity.certificates(context, r.getString("package")) == setOf(known)
    }.getOrDefault(false)
    private fun removeRegistration(key: String, r: JSONObject) {
        db.remove(key)
        db.remove("pending-registration:${r.getString("token")}")
        db.put("unregister:${r.getString("token")}", r, "unregister")
        broadcast(r, "UNREGISTERED")
    }
    suspend fun process(packageName: String, intent: Intent) = mutex.withLock {
        val token = intent.getStringExtra("token") ?: return@withLock
        val active = registration(token)
        val pending = db.entry("pending-registration:$token")?.first
        val old = active ?: pending
        val response = JSONObject().put("package", packageName).put("token", token)
        val action = intent.action?.substringAfterLast('.')
        if (action != "MESSAGE_ACK" && old != null && (old.getString("package") != packageName || !validIdentity(old))) {
            if (action == "REGISTER") broadcast(response, "REGISTRATION_FAILED") { putExtra("reason", "INTERNAL_ERROR") }
            return@withLock
        }
        if (action == "MESSAGE_ACK") {
            val id = intent.getStringExtra("id") ?: return@withLock
            val entry = db.entry("message:$id") ?: return@withLock
            if (entry.first.optString("token") != token || old == null) return@withLock
            if (entry.second == "endpoint") { db.remove("message:$id"); return@withLock }
            val ack = JSONObject().put("kind", "receipt").put("id", id).put("token", token)
            db.put("message:$id", ack, "ack")
            if (ready) socket?.send(ack.toString())
            return@withLock
        }
        if (action == "UNREGISTER") {
            if (old != null) removeRegistration("registration:$token", old)
            scope.launch { cleanupRemovedRegistrations() }
            return@withLock
        }
        if (action != "REGISTER") return@withLock
        val vapid = intent.getStringExtra("vapid")
        if (vapid.isNullOrEmpty()) { broadcast(response, "REGISTRATION_FAILED") { putExtra("reason", "VAPID_REQUIRED") }; return@withLock }
        val accessToken = auth.getAccessToken()
        if (!enabled || accessToken == null) { broadcast(response, "REGISTRATION_FAILED") { putExtra("reason", "ACTION_REQUIRED") }; return@withLock }
        if (!ready) { broadcast(response, "REGISTRATION_FAILED") { putExtra("reason", "NETWORK") }; return@withLock }
        val r = pending?.takeIf { it.optString("vapid") == vapid } ?: active ?: response.put("certificate", SigningIdentity.certificates(context, packageName).single())
        if (r.optString("vapid") != vapid) { r.remove("id"); r.remove("endpoint"); r.put("endpoint_secret", newSecret()) }
        r.put("vapid", vapid).put("description", intent.getStringExtra("message") ?: "")
        // Persist the capability before making the idempotent network request.
        db.put("pending-registration:$token", r, "pending-registration")
        try {
            // Finish an earlier unregister before reusing this token.
            if (db.entry("unregister:$token") != null) {
                request("DELETE", "/subscriptions", JSONObject().put("token", token), accessToken)
                db.remove("unregister:$token")
            }
            val body = JSONObject().put("token", token).put("package", packageName).put("vapid", vapid).put("endpoint_secret", r.getString("endpoint_secret"))
            val result = request("POST", "/subscriptions", body, accessToken)
            r.put("id", result.getString("id")).put("endpoint", result.getString("endpoint"))
            db.put("registration:$token", r, "registration")
            db.remove("pending-registration:$token")
            val id = UUID.randomUUID().toString()
            db.put("message:$id", JSONObject().put("token", token), "endpoint")
            broadcast(r, "NEW_ENDPOINT") { putExtra("endpoint", r.getString("endpoint")); putExtra("id", id) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val reason = if (e is PushRequestException && e.code in listOf(401,403,429)) "ACTION_REQUIRED" else "NETWORK"
            mutableStatus.value = if (reason == "ACTION_REQUIRED") "Push registration needs an approved server key or available quota" else "Push server unavailable"
            broadcast(response, "REGISTRATION_FAILED") { putExtra("reason", reason) }
        }
    }
    private fun newSecret() = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    fun registeredApps(): List<JSONObject> = registrations().map { it.second }
    suspend fun removeAppRegistration(token: String) = mutex.withLock {
        registration(token)?.let { removeRegistration("registration:$token", it) }
        scope.launch { cleanupRemovedRegistrations() }
    }
    private fun deliver(message: JSONObject) {
        if (!ready || System.currentTimeMillis()/1000 >= authExpires || message.getString("subscription_id") !in activeRoutes) return
        val id = message.getString("id")
        val token = message.getString("token")
        val r = registration(token) ?: return
        if (!validIdentity(r)) { removeRegistration("registration:$token", r); return }
        val previous = db.entry("message:$id")
        if (previous?.second == "ack") { socket?.send(previous.first.toString()); return }
        if (!message.optBoolean("immediate") && message.getLong("expires_at") <= System.currentTimeMillis()/1000) return
        val charging = context.getSystemService(android.os.BatteryManager::class.java).isCharging
        val wifi = networkManager.getNetworkCapabilities(networkManager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val battery = context.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val minimum = if (!charging && battery in 0..15) 3 else if (charging && wifi) 0 else if (charging || wifi) 1 else 2
        val urgency = listOf("very-low", "low", "normal", "high").indexOf(message.optString("urgency", "normal"))
        if (urgency < minimum || !delivering.add(id)) return
        val bytes = android.util.Base64.decode(message.getString("payload"), android.util.Base64.DEFAULT)
        if (bytes.isEmpty() || bytes.size > 4096) { delivering.remove(id); return }
        db.put("message:$id", JSONObject().put("token", token), "offered")
        val epoch = connectionEpoch
        scope.launch {
            try { deliverySlots.withPermit {
                withContext(Dispatchers.Main.immediate) {
                    val pm = context.packageManager
                    val serviceIntent = Intent("org.unifiedpush.android.connector.RAISE_TO_FOREGROUND").setPackage(r.getString("package"))
                    val info = pm.queryIntentServices(serviceIntent, 0).firstOrNull { it.serviceInfo.exported && it.serviceInfo.enabled }?.serviceInfo ?: return@withContext
                    serviceIntent.component = ComponentName(info.packageName, info.name)
                    val connected = CompletableDeferred<Unit>()
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(Unit) }
                        override fun onServiceDisconnected(name: ComponentName) = Unit
                        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("No foreground service")) }
                    }
                    val bound = context.bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
                    if (bound) try {
                        withTimeout(5000) { connected.await() }
                        mutex.withLock {
                            if (ready && epoch == connectionEpoch && message.getString("subscription_id") in activeRoutes && System.currentTimeMillis()/1000 < authExpires && registration(token)?.optString("id") == message.getString("subscription_id") && (message.optBoolean("immediate") || message.getLong("expires_at") > System.currentTimeMillis()/1000)) {
                                broadcast(r, "MESSAGE") { putExtra("bytesMessage", bytes); putExtra("id", id) }
                            }
                        }
                        delay(5000)
                    } finally { context.unbindService(conn) }
                }
            } } catch (_: Exception) { /* The server retries until acknowledgment or expiry. */ }
            finally { mutex.withLock { delivering.remove(id) } }
        }
    }
    private class PushRequestException(val code: Int) : Exception("Push request failed ($code)")
    private suspend fun request(method: String, path: String, body: JSONObject?, token: String, includeDevice: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$serverUrl/api/push/v1$path").header("Authorization", "Bearer $token")
        if (includeDevice) request.header("X-Device-Credential", device!!.getString("credential"))
        request.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        execute(request.build()).use { response ->
            if (!response.isSuccessful) throw PushRequestException(response.code)
            val source = response.body?.source() ?: error("Missing response")
            check(!source.request(65537)) { "Oversized response" }
            JSONObject(source.readUtf8())
        }
    }
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
    suspend fun interruptedPulse() = mutex.withLock {
        if (enabled) { disconnect("Connection operation interrupted; retrying"); schedule() }
    }

}
