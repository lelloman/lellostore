package com.lelloman.store.recovery

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.lelloman.store.R
import com.lelloman.store.recovery.protocol.RecoveryContract
import com.lelloman.store.ui.theme.LellostoreTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Provisioning lives in Store; the companion is an implementation detail of setup. */
class RecoverySetupActivity : ComponentActivity() {
    private val recovery by lazy { RecoveryCompanionClient(this) }
    private var resumeRevision by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeRevision++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LellostoreTheme {
                val scope = rememberCoroutineScope()
                var code by rememberSaveable { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var message by rememberSaveable { mutableStateOf("") }
                var enabled by remember(resumeRevision) { mutableStateOf(recovery.selfUpdatesEnabled()) }
                val installed = remember(resumeRevision) { recovery.trustedCompanionInstalled() }
                fun runAction(action: suspend () -> String) {
                    if (busy) return
                    busy = true
                    scope.launch {
                        message = runCatching { action() }.getOrElse { it.message ?: getString(R.string.recovery_setup_failed) }
                        enabled = recovery.selfUpdatesEnabled()
                        busy = false
                    }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.recovery_setup_title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.recovery_setup_intro))
                        TextButton(onClick = { finish() }, enabled = !busy) { Text(stringResource(R.string.recovery_setup_back)) }
                        Text(stringResource(if (installed) R.string.recovery_setup_installed else R.string.recovery_setup_install_step), style = MaterialTheme.typography.titleMedium)
                        if (!installed) {
                        Button(enabled = !busy, onClick = {
                            runAction {
                                val apk = withContext(Dispatchers.IO) { verifiedCompanionApk() }
                                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
                                    getString(R.string.recovery_setup_allow_install)
                                } else {
                                    val uri = FileProvider.getUriForFile(this@RecoverySetupActivity, "$packageName.fileprovider", apk)
                                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    getString(R.string.recovery_setup_return)
                                }
                            }
                        }) { Text(stringResource(R.string.recovery_setup_install)) }
                        }
                        Text(stringResource(R.string.recovery_setup_pair_step), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(R.string.recovery_setup_code)) },
                            enabled = !busy,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.recovery_setup_pair_help))
                        Button(enabled = !busy && code.length == 6, onClick = {
                            runAction { recovery.pairRecovery(code).getOrThrow(); code = ""; getString(R.string.recovery_setup_paired) }
                        }) { Text(stringResource(R.string.recovery_setup_pair)) }
                        TextButton(enabled = !busy, onClick = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }) { Text(stringResource(R.string.recovery_setup_android_settings)) }
                        Text(stringResource(R.string.recovery_setup_enable_step), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (enabled) R.string.recovery_setup_enabled else R.string.recovery_setup_disabled))
                        Button(enabled = !busy, onClick = {
                            runAction {
                                recovery.provision().getOrThrow()
                                recovery.setSelfUpdatesEnabled(true)
                                getString(R.string.recovery_setup_ready)
                            }
                        }) { Text(stringResource(R.string.recovery_setup_verify_enable)) }
                        if (enabled) TextButton(enabled = !busy, onClick = {
                            recovery.setSelfUpdatesEnabled(false)
                            enabled = false
                        }) { Text(stringResource(R.string.recovery_setup_disable)) }
                        if (installed) TextButton(enabled = !busy, onClick = {
                            startActivity(Intent().setClassName(RecoveryContract.RECOVERY_PACKAGE,
                                "com.lelloman.store.recovery.MainActivity"))
                        }) { Text(stringResource(R.string.recovery_setup_details)) }
                        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (message.isNotEmpty()) Text(message)
                        Text(stringResource(R.string.recovery_setup_limitations), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    private fun verifiedCompanionApk(): File {
        val destination = cacheDir.resolve("apks/lellostore-companion.apk")
        destination.parentFile?.mkdirs()
        assets.open("lellostore-companion.apk").use { input -> destination.outputStream().use(input::copyTo) }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = packageManager.getPackageArchiveInfo(destination.path, flags) ?: error("Invalid companion APK")
        val own = packageManager.getPackageInfo(packageName, flags)
        @Suppress("DEPRECATION")
        fun signers(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty())
                .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toList() }.toSet()
        check(archive.packageName == RecoveryContract.RECOVERY_PACKAGE &&
            PackageInfoCompat.getLongVersionCode(archive) >= RecoveryContract.MIN_COMPANION_VERSION &&
            signers(archive).isNotEmpty() && signers(archive) == signers(own)) { "Companion APK is not trusted" }
        return destination
    }
}
