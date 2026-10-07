package com.lelloman.store.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lelloman.store.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** AND_3.1.0 entrypoint. Capture authenticated identity before leaving onReceive. */
@AndroidEntryPoint
class UnifiedPushReceiver : BroadcastReceiver() {
    @Inject lateinit var audit: com.lelloman.store.logger.AuditLog
    @Inject lateinit var runtime: NotificationBrokerRuntime
    @Inject @ApplicationScope lateinit var scope: CoroutineScope
    override fun onReceive(context: Context, intent: Intent) {
        audit.record("ipc.push_received", mapOf("action" to intent.action?.substringAfterLast('.'), "valid_request" to validRequest(intent)))
        val packageName = if (intent.action == "org.unifiedpush.android.distributor.MESSAGE_ACK" && validRequest(intent)) "" else runCatching {
            require(validRequest(intent))
            val uid: Int
            val name: String
            if (Build.VERSION.SDK_INT >= 34 && sentFromUid >= 0 && sentFromPackage != null) {
                uid = sentFromUid; name = sentFromPackage!!
            } else {
                @Suppress("DEPRECATION")
                val pi = intent.getParcelableExtra<PendingIntent>("pi") ?: return
                if (Build.VERSION.SDK_INT >= 31) require(pi.isImmutable)
                uid = pi.creatorUid; name = pi.creatorPackage ?: return
                if (Build.VERSION.SDK_INT >= 34) require(context.packageManager.getApplicationInfo(name, 0).targetSdkVersion < 34)
            }
            val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
            require(packages.size == 1 && packages.single() == name)
            name
        }.getOrNull() ?: return
        val pending = goAsync()
        scope.launch {
            try { withTimeout(9000) { runtime.process(packageName, intent) } }
            catch (error: Exception) {
                audit.record("ipc.push_request_failed", mapOf("package" to packageName, "error_type" to error.javaClass.simpleName))
                if (intent.action?.endsWith(".REGISTER") == true && packageName.isNotEmpty()) {
                    audit.record("ipc.broadcast_attempt", mapOf("package" to packageName, "action" to "REGISTRATION_FAILED"))
                    context.sendBroadcast(Intent("org.unifiedpush.android.connector.REGISTRATION_FAILED").setPackage(packageName)
                        .putExtra("token", intent.getStringExtra("token")).putExtra("reason", "NETWORK"))
                }
            }
            finally { pending.finish() }
        }
    }
    companion object {
        internal fun validRequest(intent: Intent): Boolean = runCatching {
            if (intent.action !in listOf("REGISTER", "UNREGISTER", "MESSAGE_ACK").map { "org.unifiedpush.android.distributor.$it" }) return false
            fun text(name: String, max: Int, required: Boolean): Boolean {
                if (!intent.hasExtra(name)) return !required
                val value = intent.getStringExtra(name) ?: return false
                return value.toByteArray(Charsets.UTF_8).size <= max && (!required || value.isNotEmpty())
            }
            text("token", 100, true) && text("message", 100, false) && text("id", 100, intent.action?.endsWith("MESSAGE_ACK") == true) &&
                (!intent.hasExtra("vapid") || intent.getStringExtra("vapid")?.matches(Regex("[A-Za-z0-9_-]{87}")) == true)
        }.getOrDefault(false)
    }
}
