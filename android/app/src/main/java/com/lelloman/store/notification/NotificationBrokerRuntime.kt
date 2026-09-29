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
import com.lelloman.store.notifications.client.CredentialFile
import com.lelloman.store.notifications.client.PrivateStore
import com.lelloman.store.notifications.client.SigningIdentity
import com.lelloman.store.notifications.protocol.AdaptiveHeartbeat
import com.lelloman.store.notifications.protocol.INotificationCallback
import com.lelloman.store.notifications.protocol.INotificationReceiver
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
    internal val db = PrivateStore(context, "notification-broker")
    private val credentials = CredentialFile(context, "notification-device")
    private val pendingRevocations = CredentialFile(context, "notification-revocations")
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
            db.clear(); credentials.clear(); device = null
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
                    http.newCall(Request.Builder().url(retired.getString("url") + "/api/notifications/v1/device")
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
        recoverLocalDeliveries()
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
            val wsRequest = Request.Builder().url(serverUrl.replaceFirst("https://", "wss://") + "/api/notifications/v1/stream")
                .header("Authorization", "Bearer ${device!!.getString("credential")}")
                .header("Sec-WebSocket-Protocol", "lellostore.notifications.v1").build()
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
    private fun resetIdentity() { closeSocket(); db.clear(); credentials.clear(); device = null }
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
                recoverLocalDeliveries()
            }
            "authenticated" -> authExpires = frame.getLong("expires_at")
            "pong" -> if (frame.optString("nonce") == pingNonce) {
                heartbeat.acknowledged(pingIdle, pingLate); saveHeartbeat(); pingNonce = null
                db.setting("diagnostics", JSONObject().put("heartbeat_ms", heartbeat.interval).put("rtt_ms", elapsed - pingAt).put("alarm_late_ms", pingLate).toString())
            }
            "routes" -> {
                val routes = frame.getJSONArray("routes")
                val active = (0 until routes.length()).map { routes.getJSONObject(it).getString("subscription_id") }.toSet()
                activeRoutes = active
                db.setting("active-routes", JSONArray(active.toList()).toString())
                db.entries().forEach { (id, envelope) -> if (envelope.optString("subscription_id") !in active) db.remove(id) }
                recoverLocalDeliveries()
            }
            "snapshots" -> {
                val states = frame.getJSONArray("states")
                for (i in 0 until states.length()) {
                    val state = states.getJSONObject(i)
                    val sub = state.getString("subscription_id")
                    if (sub !in activeRoutes) continue
                    val route = db.setting("route:$sub")?.let(::JSONObject) ?: continue
                    val component = ComponentName.unflattenFromString(route.getString("component")) ?: continue
                    if (SigningIdentity.certificates(context, component.packageName) != setOf(route.getString("certificate"))) continue
                    scope.launch { deliverySlots.withPermit {
                        runCatching { dispatch(component, JSONObject().put("kind", "reconcile").put("state", state)) { } }
                    } }
                }
            }
            "delivery" -> {
                val envelope = frame.getJSONObject("envelope")
                val id = envelope.getString("delivery_id")
                if (db.entry(id) == null) db.put(id, envelope)
                deliver(id, envelope)
            }
            "receipt_ack" -> {
                val id = frame.getString("delivery_id")
                val entry = db.entry(id)
                if (entry != null && entry.second == "settled:${frame.optString("presentation")}") db.remove(id)
            }
        }
        lastIncoming = elapsed; lastTraffic = elapsed
    }
    private fun recoverLocalDeliveries() {
        if (!ready) return
        db.entries().forEach { (id, envelope) ->
            val state = db.entry(id)?.second ?: return@forEach
            if (state != "pending") receipt(id, state.removePrefix("settled:").takeIf { state.startsWith("settled:") })
            if (!state.startsWith("settled:")) deliver(id, envelope)
        }
    }
    private fun receipt(id: String, presentation: String?) {
        if (!ready) return
        socket?.send(JSONObject().put("kind", "receipt").put("delivery_id", id).put("presentation", presentation).toString())
    }
    private fun deliver(id: String, envelope: JSONObject) {
        if (!ready || envelope.optString("subscription_id") !in activeRoutes) return
        if (!delivering.add(id)) return
        scope.launch {
            try { deliverySlots.withPermit {
                if (envelope.getString("sender_id") == "lellostore" && envelope.getString("component") == "self") {
                    updates.enqueueImmediateUpdateCheck(); relay.notifyInstalledApps()
                    mutex.withLock { db.state(id, "settled:suppressed"); receipt(id, "suppressed") }
                } else {
                    val route = db.setting("route:${envelope.getString("subscription_id")}")?.let(::JSONObject) ?: return@withPermit
                    if (route.optString("generation") != envelope.optString("generation") || route.optString("installation") != envelope.optString("installation")) return@withPermit
                    val component = ComponentName.unflattenFromString(route.getString("component")) ?: return@withPermit
                    val installed = SigningIdentity.certificates(context, component.packageName)
                    if (installed != setOf(route.getString("certificate"))) return@withPermit
                    dispatch(component, envelope) { result ->
                        scope.launch { mutex.withLock {
                            if (db.entry(id) == null) return@withLock
                            when (result.optString("state")) {
                                "persisted" -> { if (db.entry(id)?.second?.startsWith("settled:") != true) { db.state(id, "persisted"); receipt(id, null) } }
                                "settled" -> { val presentation = result.getString("presentation"); db.state(id, "settled:$presentation"); receipt(id, presentation) }
                            }
                        } }
                    }
                }
            } } catch (_: Exception) { /* Durable queue retries on reconnect/heartbeat. */ }
            finally { mutex.withLock { delivering.remove(id) } }
        }
    }
    private suspend fun dispatch(component: ComponentName, envelope: JSONObject, result: (JSONObject) -> Unit) = withContext(Dispatchers.Main.immediate) {
        var conn: ServiceConnection? = null
        var bound = false
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LelloStore:notification-delivery")
        try {
            wake.acquire(10_000)
            withTimeout(10_000) { suspendCancellableCoroutine<Unit> { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        try { INotificationReceiver.Stub.asInterface(binder).deliver(envelope.toString(), object : INotificationCallback.Stub() {
                            override fun onResult(json: String) {
                                try {
                                    val response = JSONObject(json); result(response)
                                    if ((response.optString("state") == "settled" || response.has("error")) && continuation.isActive) continuation.resume(Unit)
                                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
                            }
                        }) } catch (e: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
                    }
                    override fun onServiceDisconnected(name: ComponentName) { if (continuation.isActive) continuation.resume(Unit) }
                    override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                }
                conn = connection; bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
                if (!bound) continuation.resume(Unit)
            } }
        } finally { if (bound) conn?.let(context::unbindService); if (wake.isHeld) wake.release() }
    }

    internal suspend fun ipc(packageName: String, certificate: String, body: JSONObject): JSONObject = mutex.withLock {
        check(enabled && ready) { "Store notification connection unavailable" }
        val token = auth.getAccessToken() ?: error("Sign-in required")
        check(sameIdentity(token))
        when (body.getString("op")) {
            "enroll" -> {
                val component = ComponentName.unflattenFromString(body.getString("component")) ?: error("Invalid receiver")
                check(component.packageName == packageName)
                val info = context.packageManager.getServiceInfo(component, 0)
                check(info.exported && info.enabled && info.applicationInfo.enabled)
                val registration = JSONObject(body.toString()).apply { remove("op"); put("package", packageName); put("certificate", certificate) }
                db.setting("enrollment:$packageName", registration.toString())
                request("POST", "/enrollments", registration, token)
            }
            "confirm" -> {
                val registration = JSONObject(db.setting("enrollment:$packageName") ?: error("No pending enrollment"))
                check(registration.getString("certificate") == certificate && registration.getString("generation") == body.getString("generation"))
                val id = body.getString("subscription_id")
                request("POST", "/subscriptions/$id/confirm", registration, token)
                db.setting("route:$id", registration.toString())
                JSONObject().put("ok", true)
            }
            "unregister" -> {
                val id = body.getString("subscription_id")
                val route = JSONObject(db.setting("route:$id") ?: error("Unknown subscription"))
                check(route.getString("package") == packageName && route.getString("certificate") == certificate)
                db.entries().filter { it.second.optString("subscription_id") == id }.forEach { db.remove(it.first) }
                db.setting("route:$id", "{}")
                request("DELETE", "/subscriptions/$id", null, token)
            }
            else -> error("Unsupported notification operation")
        }
    }
    private suspend fun request(method: String, path: String, body: JSONObject?, token: String, includeDevice: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$serverUrl/api/notifications/v1$path").header("Authorization", "Bearer $token")
        if (includeDevice) request.header("X-Device-Credential", device!!.getString("credential"))
        request.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        execute(request.build()).use { response ->
            check(response.isSuccessful) { "Notification request failed (${response.code})" }
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
