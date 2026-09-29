package com.lelloman.store.notifications.client

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.lelloman.store.notifications.protocol.INotificationBroker
import com.lelloman.store.notifications.protocol.INotificationCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface NotificationHost {
    val notificationClient: NotificationClient
}

class NotificationClient(
    val context: Context,
    val storePackage: String,
    val storeCertificates: Set<String>,
    val receiverClass: String,
    val render: (JSONObject) -> Notification?,
) {
    internal val db = PrivateStore(context, "notification-client")
    @Synchronized
    fun beginSession(issuer: String, subject: String): String {
        val identity = "$issuer\n$subject"
        if (db.setting("identity") != identity) { endSession(); db.setting("identity", identity) }
        if (db.setting("installation") == null) db.setting("installation", UUID.randomUUID().toString())
        if (db.setting("generation") == null) db.setting("generation", UUID.randomUUID().toString())
        return db.setting("generation")!!
    }
    /** Disable local delivery before attempting network logout. */
    @Synchronized
    fun endSession() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("lellostore:") == true }.forEach { manager.cancel(it.tag, it.id) }
        db.clear()
    }
    suspend fun enrollment(): JSONObject {
        require(db.setting("generation") != null) { "Authenticate the recipient first" }
        return request(JSONObject().put("op", "enroll").put("installation", db.setting("installation"))
            .put("generation", db.setting("generation")).put("component", ComponentName(context.packageName, receiverClass).flattenToString()))
    }
    suspend fun confirm(subscriptionId: String) {
        request(JSONObject().put("op", "confirm").put("subscription_id", subscriptionId).put("generation", db.setting("generation")))
        db.setting("subscription", subscriptionId)
    }
    suspend fun unregister() {
        val sub = db.setting("subscription")
        endSession()
        if (sub != null) request(JSONObject().put("op", "unregister").put("subscription_id", sub))
    }
    suspend fun request(body: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        val component = ComponentName(storePackage, "com.lelloman.store.notification.BrokerIpcService")
        val installed = SigningIdentity.certificates(context, storePackage)
        require(installed.isNotEmpty() && installed.all { it in storeCertificates })
        var connection: ServiceConnection? = null
        var bound = false
        try {
            withTimeout(10_000) {
                suspendCancellableCoroutine { continuation ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            try { INotificationBroker.Stub.asInterface(service).request(body.toString(), object : INotificationCallback.Stub() {
                                override fun onResult(result: String) {
                                    if (!continuation.isActive) return
                                    try { val response = JSONObject(result); check(!response.has("error")) { response.optString("error") }; continuation.resume(response) }
                                    catch (e: Exception) { continuation.resumeWithException(e) }
                                }
                            }) } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Store disconnected")) }
                        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    }
                    connection = conn
                    bound = context.bindService(Intent().setComponent(component), conn, Context.BIND_AUTO_CREATE)
                    if (!bound) continuation.resumeWithException(IllegalStateException("Store unavailable"))
                }
            }
        } finally { if (bound) connection?.let { context.unbindService(it) } }
    }
    internal fun accepts(envelope: JSONObject): Boolean = db.setting("generation") == envelope.optString("generation") && db.setting("installation") == envelope.optString("installation")
    internal fun notificationTag(envelope: JSONObject): String {
        val message = envelope.getJSONObject("message")
        val key = if (message.isNull("replacement_key")) message.getString("event_id") else message.getString("replacement_key")
        return "lellostore:${envelope.optString("sender_id")}:${message.getString("type")}:$key"
    }
}
