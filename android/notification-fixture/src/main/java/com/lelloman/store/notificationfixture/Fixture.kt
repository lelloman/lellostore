package com.lelloman.store.notificationfixture

import android.app.*
import android.os.Bundle
import android.widget.*
import com.lelloman.store.notifications.client.*
import kotlinx.coroutines.*

class FixtureApplication : Application(), NotificationHost {
    override val notificationClient by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("fixture", "Fixture", NotificationManager.IMPORTANCE_DEFAULT))
        NotificationClient(this, "com.lelloman.store.debug", setOf(BuildConfig.STORE_CERTIFICATE), FixtureReceiver::class.java.name) { envelope ->
            val payload = envelope.getJSONObject("message").getJSONObject("payload")
            if (payload.optBoolean("clear")) null else {
                val builder = if (android.os.Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "fixture") else @Suppress("DEPRECATION") Notification.Builder(this)
                builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(payload.optString("title", "Fixture delivery")).setContentText(payload.optString("text")).build()
            }
        }
    }
}
class FixtureReceiver : NotificationReceiverService()
/** Explicit manual enrollment fixture; never embeds a sender credential or recipient network loop. */
class FixtureActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = (application as FixtureApplication).notificationClient
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val issuer = EditText(this).apply { hint = "Canonical issuer" }
        val subject = EditText(this).apply { hint = "Recipient subject" }
        val subscription = EditText(this).apply { hint = "Subscription ID returned by fixture backend" }
        val output = TextView(this).apply { setTextIsSelectable(true) }
        listOf(issuer, subject, subscription).forEach(layout::addView)
        fun action(label: String, block: suspend () -> String) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { scope.launch { output.text = try { block() } catch (e: Exception) { e.message ?: "Failed" } } } })
        }
        action("Create enrollment proof") { client.beginSession(issuer.text.toString(), subject.text.toString()); client.enrollment().toString(2) }
        action("Confirm subscription") { client.confirm(subscription.text.toString()); "Confirmed" }
        action("End recipient session") { client.unregister(); "Disabled" }
        layout.addView(output); setContentView(layout)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
