package com.lelloman.store.notification

import androidx.activity.ComponentActivity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle

@dagger.hilt.android.AndroidEntryPoint
class UnifiedPushLinkActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var audit: com.lelloman.store.logger.AuditLog
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.data?.scheme == "unifiedpush" && intent?.data?.host == "link" && callingPackage != null) {
            val identity = PendingIntent.getBroadcast(this, 0, Intent().setPackage("org.unifiedpush.dummy_app"), PendingIntent.FLAG_IMMUTABLE)
            setResult(RESULT_OK, Intent().putExtra("pi", identity))
        } else setResult(RESULT_CANCELED)
        audit.record("ipc.push_link", mapOf("package" to callingPackage, "accepted" to (intent?.data?.scheme == "unifiedpush" && intent?.data?.host == "link" && callingPackage != null)))
        finish()
    }
}
