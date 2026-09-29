package com.lelloman.store.notifications.client

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.lelloman.store.notifications.protocol.INotificationCallback
import com.lelloman.store.notifications.protocol.INotificationReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Subclass this in the app manifest; render locally through NotificationHost. */
open class NotificationReceiverService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = kotlinx.coroutines.sync.Mutex()
    protected open val client get() = (application as NotificationHost).notificationClient
    private val binder = object : INotificationReceiver.Stub() {
        override fun deliver(json: String, callback: INotificationCallback) {
            try { SigningIdentity.requireCaller(this@NotificationReceiverService, Binder.getCallingUid(), client.storePackage, client.storeCertificates); require(json.toByteArray().size <= 65536) }
            catch (_: Exception) { callback.onResult("{\"error\":\"untrusted_delivery\"}"); return }
            scope.launch {
                serial.lock()
                try {
                    synchronized(client) {
                    val event = JSONObject(json)
                    if (event.optString("kind") == "reconcile") {
                        val state = event.getJSONObject("state")
                        check(client.accepts(state))
                        val tag = "lellostore:${state.getString("sender_id")}:${state.getString("type")}:${state.getString("replacement_key")}"
                        val watermarkKey = "watermark:$tag"
                        val watermark = client.db.setting(watermarkKey)?.split(':')?.map(String::toLong)
                        val newer = watermark == null || state.getLong("occurrence") > watermark[0] || (state.getLong("occurrence") == watermark[0] && state.getLong("revision") > watermark[1])
                        val equal = watermark != null && state.getLong("occurrence") == watermark[0] && state.getLong("revision") == watermark[1]
                        if (newer || (equal && state.getBoolean("expired"))) {
                            getSystemService(NotificationManager::class.java).cancel(tag, 0)
                            client.db.setting(watermarkKey, "${state.getLong("occurrence")}:${state.getLong("revision")}")
                        }
                        client.db.entries().forEach { (id, previous) ->
                            val message = previous.getJSONObject("message")
                            if (previous.optString("subscription_id") == state.getString("subscription_id") && message.optString("type") == state.getString("type") && message.optString("replacement_key") == state.getString("replacement_key")) {
                                val older = message.optLong("occurrence") < state.getLong("occurrence") || (message.optLong("occurrence") == state.getLong("occurrence") && message.optLong("revision") < state.getLong("revision"))
                                val equal = message.optLong("occurrence") == state.getLong("occurrence") && message.optLong("revision") == state.getLong("revision")
                                if (older || (equal && state.getBoolean("expired"))) {
                                    getSystemService(NotificationManager::class.java).cancel(client.notificationTag(previous), 0)
                                    client.db.state(id, if (older) "superseded" else "expired")
                                    val key = "watermark:${client.notificationTag(previous)}"
                                    val watermark = client.db.setting(key)?.split(':')?.map(String::toLong)
                                    if (watermark == null || state.getLong("occurrence") > watermark[0] || (state.getLong("occurrence") == watermark[0] && state.getLong("revision") > watermark[1])) {
                                        client.db.setting(key, "${state.getLong("occurrence")}:${state.getLong("revision")}")
                                    }
                                }
                            }
                        }
                        drain()
                        callback.onResult("{\"state\":\"settled\",\"presentation\":\"suppressed\"}")
                    } else {
                        check(client.accepts(event)) { "stale_generation" }
                        val id = event.getString("delivery_id")
                        if (client.db.entry(id) == null) client.db.put(id, event)
                        callback.onResult("{\"state\":\"persisted\"}")
                        drain()
                        callback.onResult(JSONObject().put("state", "settled").put("presentation", client.db.entry(id)?.second ?: "suppressed").toString())
                    }
                    }
                } catch (_: Exception) { runCatching { callback.onResult("{\"error\":\"delivery_failed\"}") } }
                finally { serial.unlock() }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    @android.annotation.SuppressLint("MissingPermission")
    private fun drain() {
        client.db.entries("pending").forEach { (id, envelope) ->
            if (!client.accepts(envelope)) { client.db.state(id, "suppressed"); return@forEach }
            val message = envelope.getJSONObject("message")
            val tag = client.notificationTag(envelope)
            val manager = getSystemService(NotificationManager::class.java)
            val occurrence = message.optLong("occurrence")
            val revision = message.optLong("revision")
            val key = "watermark:$tag"
            val previous = client.db.setting(key)?.split(':')?.map(String::toLong)
            val isState = !message.isNull("replacement_key")
            if (isState && previous != null && (occurrence < previous[0] || (occurrence == previous[0] && revision < previous[1]))) {
                client.db.state(id, "superseded"); return@forEach
            }
            if (isState) client.db.setting(key, "$occurrence:$revision")
            if (!envelope.isNull("expires_at") && envelope.getLong("expires_at") * 1000 <= System.currentTimeMillis()) {
                if (isState) manager.cancel(tag, 0)
                client.db.state(id, "expired"); return@forEach
            }
            val notification = client.render(envelope)
            val result = when {
                notification == null -> { manager.cancel(tag, 0); "suppressed" }
                !NotificationManagerCompat.from(this).areNotificationsEnabled() -> "permission_blocked"
                android.os.Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(notification.channelId)?.importance == NotificationManager.IMPORTANCE_NONE -> "permission_blocked"
                else -> { notification.flags = notification.flags or android.app.Notification.FLAG_ONLY_ALERT_ONCE; manager.notify(tag, 0, notification); "posted" }
            }
            client.db.state(id, result)
        }
        client.db.prune()
    }
}
