package com.lelloman.store.notification

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.lelloman.store.notifications.client.SigningIdentity
import com.lelloman.store.notifications.protocol.INotificationBroker
import com.lelloman.store.notifications.protocol.INotificationCallback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class BrokerIpcService : Service() {
    @Inject lateinit var runtime: NotificationBrokerRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = object : INotificationBroker.Stub() {
        override fun request(json: String, callback: INotificationCallback) {
            // Capture OS identity synchronously, before moving to a coroutine.
            val uid = Binder.getCallingUid()
            val packages = packageManager.getPackagesForUid(uid).orEmpty()
            if (packages.size != 1 || json.toByteArray().size > 65536) { callback.onResult("{\"error\":\"invalid_caller\"}"); return }
            val pkg = packages.single()
            val certificate = runCatching { SigningIdentity.certificates(this@BrokerIpcService, pkg).single() }.getOrNull()
            if (certificate == null) { callback.onResult("{\"error\":\"invalid_signer\"}"); return }
            scope.launch {
                val response = try { runtime.ipc(pkg, certificate, JSONObject(json)) } catch (_: Exception) { JSONObject().put("error", "Notification enrollment unavailable or unauthorized") }
                runCatching { callback.onResult(response.toString()) }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
