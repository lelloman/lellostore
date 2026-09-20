package com.lelloman.store.ui.screen.pesce

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.remote.*
import com.lelloman.store.ui.R

@Composable
fun PesceScreen(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PesceViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsState()
    val picker by viewModel.picker.collectAsState()
    val auth by viewModel.authState.collectAsState()
    val signedIn = auth is AuthState.Authenticated
    LaunchedEffect(signedIn) {
        if (signedIn && picker.open) viewModel.refreshCatalog()
    }
    PesceScreenContent(connection, picker, signedIn, viewModel::act, onSignIn, modifier)
}

@Composable
internal fun PesceScreenContent(
    state: RemoteDeviceState,
    picker: PescePickerState,
    signedIn: Boolean,
    onAction: (PesceAction) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmTcp by rememberSaveable { mutableStateOf(false) }
    val ready = state.phase == RemoteConnectionPhase.READY && !state.busy
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.pesce_intro), style = MaterialTheme.typography.bodyLarge)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(connectionLabel(state.phase)), style = MaterialTheme.typography.titleMedium)
                    state.receiver?.let { receiver ->
                        Text(receiver.model, style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.pesce_device_details, receiver.androidVersion, receiver.userId))
                        if (receiver.addresses.isNotEmpty()) Text(receiver.addresses.joinToString(" · "))
                    }
                    if (state.phase != RemoteConnectionPhase.READY) Text(stringResource(R.string.pesce_setup))
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (state.phase == RemoteConnectionPhase.AUTHORIZING) Text(stringResource(R.string.pesce_authorize_help))
                    if (state.busy && state.operation == RemoteOperationPhase.IDLE) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.phase != RemoteConnectionPhase.UNSUPPORTED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onAction(PesceAction.Refresh) }, enabled = !state.busy) { Text(stringResource(R.string.pesce_refresh)) }
                            if (state.receiver != null || state.busy) TextButton(onClick = { onAction(PesceAction.Disconnect) }) { Text(stringResource(R.string.pesce_disconnect)) }
                        }
                    }
                }
            }
        }
        if (state.phase != RemoteConnectionPhase.READY && !state.busy) {
            items(state.devices, key = { it.id }) { device ->
                OutlinedButton(onClick = { onAction(PesceAction.Connect(device.id)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pesce_connect_device, device.name))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAction(PesceAction.Copy) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pesce_copy)) }
                Text(stringResource(R.string.pesce_copy_help), style = MaterialTheme.typography.bodySmall)
                if (state.canLaunchStore) OutlinedButton(onClick = { onAction(PesceAction.Launch) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pesce_launch)) }
                OutlinedButton(onClick = { confirmTcp = true }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pesce_enable_tcp)) }
                state.tcpPort?.let { port ->
                    Text(stringResource(R.string.pesce_tcp_port, port))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onAction(PesceAction.TestTcp) }, enabled = ready) { Text(stringResource(R.string.pesce_test_tcp)) }
                        TextButton(onClick = { onAction(PesceAction.DisableTcp) }, enabled = ready) { Text(stringResource(R.string.pesce_disable_tcp)) }
                    }
                }
                OutlinedButton(onClick = { onAction(PesceAction.PickApps) }, enabled = ready && state.pendingCount == 0, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pesce_install_apps)) }
                if (!signedIn) Text(stringResource(R.string.pesce_login_help), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.operation != RemoteOperationPhase.IDLE) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.activeApp?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(stringResource(operationLabel(state.operation)))
                    if (state.totalBytes > 0 && state.operation in setOf(RemoteOperationPhase.DOWNLOADING, RemoteOperationPhase.TRANSFERRING)) {
                        LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { onAction(PesceAction.Cancel) }) { Text(stringResource(R.string.pesce_stop)) }
                }
            }
        }
        if (state.pendingCount > 0 && !state.busy) item {
            Text(pluralStringResource(R.plurals.pesce_pending, state.pendingCount, state.pendingCount))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (signedIn) onAction(PesceAction.Resume) else onSignIn() }, enabled = ready) {
                    Text(stringResource(if (signedIn) R.string.pesce_resume else R.string.pesce_sign_in))
                }
                TextButton(onClick = { onAction(PesceAction.Cancel) }) { Text(stringResource(R.string.pesce_stop)) }
            }
        }
        items(state.results) { result ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(result.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(outcomeLabel(result.outcome)))
                    if (result.detail.isNotBlank()) Text(result.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.results.isNotEmpty()) item {
            TextButton(onClick = { onAction(PesceAction.Clear) }, enabled = !state.busy) { Text(stringResource(R.string.pesce_clear)) }
        }
    }

    if (confirmTcp) AlertDialog(
        onDismissRequest = { confirmTcp = false },
        title = { Text(stringResource(R.string.pesce_enable_tcp)) },
        text = { Text(stringResource(R.string.pesce_tcp_warning)) },
        confirmButton = { TextButton(onClick = { confirmTcp = false; onAction(PesceAction.EnableTcp) }) { Text(stringResource(R.string.pesce_enable)) } },
        dismissButton = { TextButton(onClick = { confirmTcp = false }) { Text(stringResource(R.string.cancel)) } },
    )

    if (picker.open) AlertDialog(
        onDismissRequest = { onAction(PesceAction.ClosePicker) },
        title = { Text(stringResource(R.string.pesce_install_on, state.receiver?.model.orEmpty())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!signedIn) {
                    Text(stringResource(R.string.pesce_login_help))
                    Button(onClick = onSignIn) { Text(stringResource(R.string.pesce_sign_in)) }
                } else {
                    OutlinedTextField(picker.query, { onAction(PesceAction.Search(it)) }, label = { Text(stringResource(R.string.pesce_search)) }, singleLine = true)
                    Text(stringResource(R.string.pesce_release_help), style = MaterialTheme.typography.bodySmall)
                    if (picker.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    picker.error?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onAction(PesceAction.PickApps) }) { Text(stringResource(R.string.pesce_refresh)) }
                    }
                    if (!picker.loading && picker.error == null && picker.apps.isEmpty()) Text(stringResource(R.string.pesce_no_apps))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(picker.apps.filter { it.name.contains(picker.query, true) || it.packageName.contains(picker.query, true) }, key = { it.packageName }) { app ->
                            val compatible = state.receiver?.let { it.sdk >= app.version.minSdk } == true
                            Row(Modifier.fillMaxWidth().toggleable(app.packageName in picker.selected, enabled = compatible, role = Role.Checkbox,
                                onValueChange = { onAction(PesceAction.Select(app.packageName)) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = app.packageName in picker.selected, onCheckedChange = null, enabled = compatible)
                                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                    Text(app.name)
                                    Text("${app.version.versionName} · ${stringResource(if (app.version.isBeta) R.string.pesce_beta else R.string.pesce_stable)}", style = MaterialTheme.typography.bodySmall)
                                    if (!compatible) Text(stringResource(R.string.pesce_requires_sdk, app.version.minSdk), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAction(PesceAction.Install) }, enabled = signedIn && ready && !picker.loading && picker.selected.isNotEmpty()) { Text(stringResource(R.string.pesce_install_selected, picker.selected.size)) } },
        dismissButton = { TextButton(onClick = { onAction(PesceAction.ClosePicker) }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun connectionLabel(phase: RemoteConnectionPhase): Int = when (phase) {
    RemoteConnectionPhase.UNSUPPORTED -> R.string.pesce_unsupported
    RemoteConnectionPhase.DISCONNECTED -> R.string.pesce_disconnected
    RemoteConnectionPhase.PERMISSION -> R.string.pesce_permission
    RemoteConnectionPhase.CONNECTING -> R.string.pesce_connecting
    RemoteConnectionPhase.AUTHORIZING -> R.string.pesce_authorizing
    RemoteConnectionPhase.READY -> R.string.pesce_ready
    RemoteConnectionPhase.RESTARTING -> R.string.pesce_restarting
    RemoteConnectionPhase.ERROR -> R.string.pesce_error
}
private fun operationLabel(phase: RemoteOperationPhase): Int = when (phase) {
    RemoteOperationPhase.DOWNLOADING -> R.string.pesce_downloading
    RemoteOperationPhase.VERIFYING -> R.string.pesce_verifying
    RemoteOperationPhase.TRANSFERRING -> R.string.pesce_transferring
    RemoteOperationPhase.INSTALLING -> R.string.pesce_installing
    else -> R.string.pesce_configuring
}
private fun outcomeLabel(outcome: RemoteInstallOutcome): Int = when (outcome) {
    RemoteInstallOutcome.INSTALLED -> R.string.pesce_installed
    RemoteInstallOutcome.ALREADY_INSTALLED -> R.string.pesce_already_installed
    RemoteInstallOutcome.FAILED -> R.string.pesce_failed
    RemoteInstallOutcome.UNCERTAIN -> R.string.pesce_uncertain
    RemoteInstallOutcome.CANCELLED -> R.string.pesce_cancelled
}
