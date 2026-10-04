package com.lelloman.store.notification

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle

class UnifiedPushLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.data?.scheme == "unifiedpush" && intent?.data?.host == "link" && callingPackage != null) {
            val identity = PendingIntent.getBroadcast(this, 0, Intent().setPackage("org.unifiedpush.dummy_app"), PendingIntent.FLAG_IMMUTABLE)
            setResult(RESULT_OK, Intent().putExtra("pi", identity))
        } else setResult(RESULT_CANCELED)
        finish()
    }
}
