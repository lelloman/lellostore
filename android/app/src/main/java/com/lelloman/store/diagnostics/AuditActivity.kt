package com.lelloman.store.diagnostics

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.lelloman.store.R
import com.lelloman.store.logger.AuditLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class AuditActivity : ComponentActivity() {
    @Inject lateinit var auditLog: AuditLog
    private lateinit var details: TextView
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = auditLog.snapshot()
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(snapshot) }
                }
            }
            android.widget.Toast.makeText(this@AuditActivity,
                if (result.isSuccess) R.string.audit_exported else R.string.audit_error,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.audit_title)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            fitsSystemWindows = true
        }
        layout.addView(Button(this).apply {
            setText(R.string.audit_refresh)
            setOnClickListener { refresh() }
        })
        layout.addView(Button(this).apply {
            setText(R.string.audit_export)
            setOnClickListener { export.launch("lellostore-audit.jsonl") }
        })
        details = TextView(this).apply { setTextIsSelectable(true) }
        layout.addView(ScrollView(this).apply { addView(details) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            details.text = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = auditLog.snapshot()
                    val events = snapshot.lineSequence().filter { it.isNotBlank() }
                        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList()
                    val completed = events.filter { it.optString("event") == "operation.finished" }
                    val success = completed.count { it.optJSONObject("fields")?.optString("state") == "COMPLETED" }
                    val durations = completed.map { it.optJSONObject("fields")?.optLong("duration_ms") ?: 0L }
                    val average = if (durations.isEmpty()) 0L else durations.average().toLong()
                    getString(R.string.audit_summary, snapshot.toByteArray().size, events.size,
                        completed.size, success, average) + "\n\n" +
                        events.takeLast(100).asReversed().joinToString("\n\n") { it.toString(2) }
                }.getOrElse { getString(R.string.audit_error) }
            }
        }
    }
}
