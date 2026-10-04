package com.lelloman.store.notification

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import com.lelloman.store.R

@AndroidEntryPoint
class PushRegistrationsActivity : ComponentActivity() {
    @Inject lateinit var runtime: NotificationBrokerRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showRegistrations() }
    private fun showRegistrations() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        column.addView(TextView(this).apply { text = getString(R.string.push_registration_status, runtime.status.value); textSize = 20f })
        column.addView(Button(this).apply { setText(R.string.audit_refresh); setOnClickListener { showRegistrations() } })
        val registrations = runtime.registeredApps()
        if (registrations.isEmpty()) column.addView(TextView(this).apply { setText(R.string.push_registration_empty) })
        registrations.forEach { r ->
            column.addView(TextView(this).apply { text = getString(R.string.push_registration_details, r.getString("package"), r.optString("description")) })
            column.addView(Button(this).apply {
                setText(R.string.push_registration_remove)
                setOnClickListener { scope.launch { runtime.removeAppRegistration(r.getString("token")); showRegistrations() } }
            })
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
