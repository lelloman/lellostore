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
    private lateinit var filter: android.widget.EditText
    private var events: List<JSONObject> = emptyList()
    private var summary = ""

    @android.annotation.SuppressLint("SetTextI18n") // Structured diagnostic records, not translatable prose.
    private fun render() {
        val query = filter.text.toString().trim()
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        details.text = summary + "\n\n" + events.asSequence()
            .filter { query.isEmpty() || it.toString().contains(query, ignoreCase = true) }
            .toList().takeLast(100).asReversed().joinToString("\n\n") {
                time.format(java.util.Date(it.optLong("timestamp_ms"))) + "  " + it.optString("event") +
                    "\n" + (it.optJSONObject("fields")?.toString(2) ?: it.toString(2))
            }
    }
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
        layout.addView(Button(this).apply {
            setText(R.string.audit_clear)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@AuditActivity)
                    .setMessage(R.string.audit_clear_confirm)
                    .setNegativeButton(R.string.audit_cancel, null)
                    .setPositiveButton(R.string.audit_clear) { _, _ ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { auditLog.clear() } }
                            if (result.isSuccess) refresh() else details.setText(R.string.audit_error)
                        }
                    }.show()
            }
        })
        filter = android.widget.EditText(this).apply {
            setHint(R.string.audit_filter)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        layout.addView(filter)
        details = TextView(this).apply { setTextIsSelectable(true) }
        layout.addView(ScrollView(this).apply { addView(details) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = auditLog.snapshot()
                    val parsed = snapshot.lineSequence().filter { it.isNotBlank() }
                        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList()
                    val completed = parsed.filter { it.optString("event") == "operation.finished" }
                    val success = completed.count { it.optJSONObject("fields")?.optString("state") == "COMPLETED" }
                    val durations = completed.map { it.optJSONObject("fields")?.optLong("duration_ms") ?: 0L }
                    val average = if (durations.isEmpty()) 0L else durations.average().toLong()
                    getString(R.string.audit_summary, snapshot.toByteArray().size, parsed.size,
                        completed.size, success, average) to parsed
                }
            }
            result.onSuccess { (text, parsed) -> summary = text; events = parsed; render() }
                .onFailure { details.setText(R.string.audit_error) }
        }
    }
}
