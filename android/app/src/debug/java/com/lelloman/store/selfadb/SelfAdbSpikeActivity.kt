package com.lelloman.store.selfadb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lelloman.store.ui.theme.LellostoreTheme
import com.lelloman.store.installation.InstallationCoordinator
import com.lelloman.store.installation.InstallationRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SelfAdbSpikeActivity : ComponentActivity() {
    @Inject
    lateinit var installationCoordinator: InstallationCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LellostoreTheme {
                SelfAdbSpikeScreen(
                    installationCoordinator = installationCoordinator,
                    openWirelessDebugging = {
                        startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
                    }
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_RUN_TLS_PROBE, false)) {
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        Log.i(TAG, "TLS_PROBE_START")
                        val adb = SelfAdbConnectionManager.getInstance(this@SelfAdbSpikeActivity)
                        if (adb.isConnected) adb.disconnect()
                        val connected = adb.connectTls(this@SelfAdbSpikeActivity, 15_000L)
                        Log.i(TAG, "TLS_PROBE_CONNECT_RETURNED connected=$connected host=${adb.hostAddress}")
                        check(connected) {
                            "No paired Wireless debugging endpoint was discovered"
                        }
                        Log.i(TAG, "TLS_PROBE_OPENING_SHELL")
                        adb.openStream("shell:id; getprop ro.product.model").use { stream ->
                            stream.openInputStream().bufferedReader().use { it.readText() }.also {
                                Log.i(TAG, "TLS_PROBE_SHELL_RETURNED")
                            }
                        }
                    }
                }
                result.fold(
                    onSuccess = { Log.i(TAG, "TLS_PROBE_SUCCESS $it") },
                    onFailure = { Log.e(TAG, "TLS_PROBE_FAILURE", it) },
                )
            }
        }
    }

    private companion object {
        const val EXTRA_RUN_TLS_PROBE = "run_tls_probe"
        const val TAG = "SelfAdbSpike"
    }
}

@Composable
private fun SelfAdbSpikeScreen(
    installationCoordinator: InstallationCoordinator,
    openWirelessDebugging: () -> Unit,
) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var connectPort by remember { mutableStateOf("5555") }
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Ready. Connect runs only: id; getprop ro.product.model") }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun run(label: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        output = "$label…"
        scope.launch {
            output = runCatching { withContext(Dispatchers.IO) { block() } }
                .fold(
                    onSuccess = { "$label succeeded\n\n$it" },
                    onFailure = { "$label failed\n\n${it.stackTraceToString()}" }
                )
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Self-ADB spike", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Debug build only. This creates a dedicated ADB key in LelloStore's private storage."
        )

        Button(onClick = openWirelessDebugging, enabled = !busy) {
            Text("Open wireless settings")
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Host") },
            singleLine = true
        )
        OutlinedTextField(
            value = connectPort,
            onValueChange = { connectPort = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Connect port") },
            singleLine = true
        )

        Button(
            onClick = {
                run("Connection") {
                    val adb = SelfAdbConnectionManager.getInstance(context)
                    if (adb.isConnected) adb.disconnect()
                    check(adb.connect(host.trim(), connectPort.toInt())) { "ADB did not connect" }
                    adb.openStream("shell:id; getprop ro.product.model").use { stream ->
                        stream.openInputStream().bufferedReader().use { it.readText() }
                    }
                }
            },
            enabled = !busy && connectPort.toIntOrNull() != null
        ) {
            Text("Connect and run identity check")
        }

        Button(
            onClick = {
                run("TLS connection") {
                    val adb = SelfAdbConnectionManager.getInstance(context)
                    if (adb.isConnected) adb.disconnect()
                    check(adb.connectTls(context, 15_000L)) {
                        "No paired Wireless debugging endpoint was discovered"
                    }
                    adb.openStream("shell:id; getprop ro.product.model").use { stream ->
                        stream.openInputStream().bufferedReader().use { it.readText() }
                    }
                }
            },
            enabled = !busy
        ) {
            Text("Connect through Wireless debugging TLS")
        }

        Button(
            onClick = {
                run("ADB install") {
                    val apk = File(context.filesDir, "selfadb-test.apk")
                    check(apk.isFile) { "Missing ${apk.absolutePath}" }

                    installationCoordinator.install(
                        InstallationRequest(
                            apk = apk,
                            packageName = "com.lelloman.store.selfadbtest",
                        )
                    ).toString()
                }
            },
            enabled = !busy && connectPort.toIntOrNull() != null
        ) {
            Text("Install harmless test APK through ADB")
        }

        Text("Android 11+ TLS pairing", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = pairingPort,
            onValueChange = { pairingPort = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pair port") },
            singleLine = true
        )
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("6-digit code") },
            singleLine = true
        )
        Button(
            onClick = {
                run("Pairing") {
                    val adb = SelfAdbConnectionManager.getInstance(context)
                    check(adb.pair(host.trim(), pairingPort.toInt(), pairingCode)) { "Pairing failed" }
                    "Paired. Enter the separate connect port shown on the Wireless debugging page."
                }
            },
            enabled = !busy && pairingPort.toIntOrNull() != null && pairingCode.length == 6
        ) {
            Text("Pair")
        }

        Text(output, style = MaterialTheme.typography.bodySmall)
    }
}
