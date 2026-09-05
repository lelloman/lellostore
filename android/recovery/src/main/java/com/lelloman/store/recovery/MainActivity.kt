package com.lelloman.store.recovery

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var repair: Button
    private val store by lazy { RecoveryAttemptStore(this) }
    private val engine by lazy { RecoveryEngine(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f }
        repair = Button(this).apply {
            text = getString(R.string.repair)
            setOnClickListener { confirmRepair() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding)
            addView(TextView(context).apply {
                text = getString(R.string.recovery_title)
                textSize = 28f
            }, matchWrap())
            addView(TextView(context).apply {
                text = getString(R.string.recovery_intro)
                textSize = 16f
            }, matchWrap(top = padding / 2))
            addView(status, matchWrap(top = padding))
            addView(Button(context).apply {
                text = getString(R.string.refresh)
                setOnClickListener { refresh() }
            }, matchWrap(top = padding))
            addView(Button(context).apply {
                text = getString(R.string.provision_test_adb)
                setOnClickListener { testAdb() }
            }, matchWrap(top = padding / 2))
            addView(repair, matchWrap(top = padding / 2))
            addView(Button(context).apply {
                text = getString(R.string.resolve_attempt)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(R.string.resolve_warning)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.resolve_attempt) { _, _ ->
                            store.update { current ->
                                if (current?.status == RecoveryStatus.REPAIRING) current
                                else current?.copy(status = RecoveryStatus.HEALTHY,
                                    lastReason = "User confirmed Store is working and resolved the attempt",
                                    finishedAtMillis = System.currentTimeMillis())
                            }
                            refresh()
                        }.show()
                }
            }, matchWrap(top = padding / 2))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val evaluated = store.update { current ->
            if (current?.status == RecoveryStatus.REPAIRING) current
            else RecoveryPolicy.evaluate(current, System.currentTimeMillis())
        }
        status.text = evaluated?.let {
            buildString {
                append("Status: ").append(it.status.name.replace('_', ' '))
                append("\nUpdate: ").append(it.currentVersion).append(" → ").append(it.targetVersion)
                append("\nAttempt: ").append(it.id)
                it.lastReason?.let { reason -> append("\nReason: ").append(reason) }
                append("\nDestructive attempts: ").append(it.destructiveAttempts).append(" / 1")
            }
        } ?: getString(R.string.no_attempt)
        repair.visibility = if (evaluated?.status == RecoveryStatus.NEEDS_ATTENTION) View.VISIBLE else View.GONE
    }

    private fun testAdb() {
        status.text = getString(R.string.testing_recovery_adb)
        scope.launch {
            val result = withContext(Dispatchers.IO) { engine.testConnection() }
            status.text = result.fold(
                onSuccess = { getString(R.string.recovery_adb_authorized, it) },
                onFailure = { getString(R.string.recovery_adb_not_ready, it.message) },
            )
        }
    }

    private fun confirmRepair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.repair)
            .setMessage(R.string.repair_warning)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_repair) { _, _ -> runRepair() }
            .show()
    }

    private fun runRepair() {
        val repairing = store.update(RecoveryPolicy::beginExplicitRepair) ?: return
        RecoveryDeadlineScheduler.cancel(this)
        refresh()
        val work = RecoveryRepairRunner.start(applicationContext, repairing)
        scope.launch {
            work.join()
            refresh()
        }
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }
}
