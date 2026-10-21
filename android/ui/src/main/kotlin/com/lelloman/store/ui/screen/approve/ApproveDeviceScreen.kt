package com.lelloman.store.ui.screen.approve

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreBrandMark
import com.lelloman.store.ui.components.lelloStoreButtonColors
import com.lelloman.store.ui.screen.credential.CallerHeader
import com.lelloman.store.ui.screen.credential.CredentialCaller
import com.lelloman.store.ui.theme.LelloStoreSpacing
import kotlinx.coroutines.delay

/**
 * The administrator's approval of another device's enrollment. The confirming buttons sit in the
 * activity's hardened window (touches are dropped while it is obscured).
 */
@Composable
fun ApproveDeviceScreen(
    state: ApproveDeviceScreenState,
    caller: CredentialCaller?,
    onCodeChanged: (String) -> Unit,
    onScanned: (String) -> Unit,
    onLookup: () -> Unit,
    onModeChanged: (ApprovalMode) -> Unit,
    onReplaceTokenSelected: (String) -> Unit,
    onGroupToggled: (Long) -> Unit,
    onNameChanged: (String) -> Unit,
    onApprove: () -> Unit,
    onDeny: (blockSource: Boolean) -> Unit,
    onApproveAnother: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val scanPrompt = stringResource(R.string.approve_scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onScanned)
    }
    val scan = {
        scanner.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(scanPrompt).setBeepEnabled(false),
        )
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(LelloStoreSpacing.xLarge),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    ApproveDeviceScreenState.Loading, is ApproveDeviceScreenState.Finished -> CircularProgressIndicator()
                    ApproveDeviceScreenState.NeedsLogin -> Message(
                        caller = caller,
                        title = stringResource(R.string.approve_login_title),
                        message = stringResource(R.string.approve_login_message),
                        primary = stringResource(R.string.credential_open_store) to onOpenStore,
                        secondary = stringResource(R.string.cancel) to onClose,
                    )
                    ApproveDeviceScreenState.NotAdmin -> Message(
                        caller = caller,
                        title = stringResource(R.string.approve_not_admin_title),
                        message = stringResource(R.string.approve_not_admin_message),
                        primary = stringResource(R.string.approve_close) to onClose,
                    )
                    is ApproveDeviceScreenState.EnterCode -> EnterCodeContent(state, caller, onCodeChanged, onLookup, scan, onClose)
                    is ApproveDeviceScreenState.Review -> ReviewContent(
                        state, caller, onModeChanged, onReplaceTokenSelected, onGroupToggled, onNameChanged, onApprove, onDeny,
                    )
                    is ApproveDeviceScreenState.Done -> DoneContent(state.result, canApproveAnother = caller == null, onApproveAnother, onClose)
                }
            }
        }
    }
}

@Composable
private fun Header(caller: CredentialCaller?) {
    if (caller != null) {
        CallerHeader(caller)
        Spacer(Modifier.height(LelloStoreSpacing.small))
        Text(
            stringResource(R.string.approve_requested_by, caller.label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    } else {
        LelloStoreBrandMark(contentDescription = null, size = 64.dp)
    }
    Spacer(Modifier.height(LelloStoreSpacing.large))
}

@Composable
private fun Message(
    caller: CredentialCaller?,
    title: String,
    message: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Header(caller)
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(LelloStoreSpacing.xLarge))
    Row(horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium)) {
        secondary?.let { (label, action) -> OutlinedButton(onClick = action) { Text(label) } }
        Button(onClick = primary.second, colors = lelloStoreButtonColors()) { Text(primary.first) }
    }
}

@Composable
private fun EnterCodeContent(
    state: ApproveDeviceScreenState.EnterCode,
    caller: CredentialCaller?,
    onCodeChanged: (String) -> Unit,
    onLookup: () -> Unit,
    onScan: () -> Unit,
    onClose: () -> Unit,
) {
    Header(caller)
    Text(stringResource(R.string.approve_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Text(stringResource(R.string.approve_enter_code_message), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(LelloStoreSpacing.xLarge))
    OutlinedTextField(
        value = state.code,
        onValueChange = onCodeChanged,
        enabled = !state.isLookingUp,
        label = { Text(stringResource(R.string.approve_code_label)) },
        placeholder = { Text("WDJB-MJHT") },
        isError = state.error != null,
        supportingText = state.error?.let { error -> { Text(codeErrorText(error)) } },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onLookup() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Row(horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onScan, enabled = !state.isLookingUp, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Text(stringResource(R.string.approve_scan_qr))
        }
        Button(
            onClick = onLookup,
            enabled = !state.isLookingUp && state.code.isNotBlank(),
            colors = lelloStoreButtonColors(),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) {
            if (state.isLookingUp) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.approve_look_up))
            }
        }
    }
    TextButton(onClick = onClose, enabled = !state.isLookingUp) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun codeErrorText(error: CodeError): String = stringResource(
    when (error) {
        CodeError.INVALID -> R.string.approve_error_invalid_code
        CodeError.OTHER_STORE -> R.string.approve_error_other_store
        CodeError.NOT_FOUND -> R.string.approve_error_not_found
        CodeError.NOT_PENDING -> R.string.approve_error_not_pending
        CodeError.TOO_MANY -> R.string.approve_error_too_many
        CodeError.NOT_AUTHORIZED -> R.string.approve_error_not_authorized
        CodeError.NETWORK -> R.string.approve_error_network
    },
)

@Composable
private fun reviewErrorText(error: ReviewError): String = stringResource(
    when (error) {
        ReviewError.INVALID_NAME_OR_GROUPS -> R.string.approve_error_invalid_request
        ReviewError.NOT_PENDING -> R.string.approve_error_not_pending
        ReviewError.NOT_FOUND -> R.string.approve_error_gone
        ReviewError.TOKEN_NOT_REKEYABLE -> R.string.approve_error_not_rekeyable
        ReviewError.NOT_AUTHORIZED -> R.string.approve_error_not_authorized
        ReviewError.NETWORK -> R.string.approve_error_network
    },
)

@Composable
private fun ReviewContent(
    state: ApproveDeviceScreenState.Review,
    caller: CredentialCaller?,
    onModeChanged: (ApprovalMode) -> Unit,
    onReplaceTokenSelected: (String) -> Unit,
    onGroupToggled: (Long) -> Unit,
    onNameChanged: (String) -> Unit,
    onApprove: () -> Unit,
    onDeny: (Boolean) -> Unit,
) {
    var showDeny by rememberSaveable { mutableStateOf(false) }
    val pending = state.pending
    // Ages the request from what the server said at lookup.
    val elapsedSeconds by produceState(0L, state.lookedUpAtMillis) {
        while (true) {
            value = (SystemClock.elapsedRealtime() - state.lookedUpAtMillis).coerceAtLeast(0) / 1000
            delay(1_000)
        }
    }
    Header(caller)
    Text(stringResource(R.string.approve_review_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Text(
        text = pending.userCode,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        letterSpacing = 3.sp,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        stringResource(R.string.approve_compare_code),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(LelloStoreSpacing.large))
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LelloStoreSpacing.large), verticalArrangement = Arrangement.spacedBy(LelloStoreSpacing.small)) {
            Detail(stringResource(R.string.approve_detail_name), pending.requestedName)
            pending.model?.let { Detail(stringResource(R.string.approve_detail_model), it) }
            pending.androidSdk?.let { Detail(stringResource(R.string.approve_detail_android), stringResource(R.string.approve_detail_android_value, it)) }
            pending.clientVersion?.let { Detail(stringResource(R.string.approve_detail_version), it) }
            Detail(stringResource(R.string.approve_detail_address), pending.sourceIp)
            Detail(stringResource(R.string.approve_detail_requested), formatAge(pending.ageSeconds + elapsedSeconds))
            Detail(
                stringResource(R.string.approve_detail_expires),
                (pending.expiresInSeconds - elapsedSeconds).takeIf { it > 0 }?.let(::formatDuration)
                    ?: stringResource(R.string.approve_detail_expired),
            )
        }
    }
    Spacer(Modifier.height(LelloStoreSpacing.small))
    Text(
        stringResource(R.string.approve_phishing_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(LelloStoreSpacing.large))

    ModeOption(
        selected = state.mode == ApprovalMode.NEW_DEVICE,
        title = stringResource(R.string.approve_mode_new),
        subtitle = stringResource(R.string.approve_mode_new_subtitle),
        enabled = !state.isSubmitting,
        onClick = { onModeChanged(ApprovalMode.NEW_DEVICE) },
    )
    ModeOption(
        selected = state.mode == ApprovalMode.REPLACE,
        title = stringResource(R.string.approve_mode_replace),
        subtitle = stringResource(R.string.approve_mode_replace_subtitle),
        enabled = !state.isSubmitting,
        onClick = { onModeChanged(ApprovalMode.REPLACE) },
    )
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    OutlinedTextField(
        value = state.name,
        onValueChange = onNameChanged,
        enabled = !state.isSubmitting,
        label = { Text(stringResource(R.string.approve_name_label)) },
        isError = state.invalidName,
        supportingText = if (state.invalidName) {
            { Text(stringResource(R.string.approve_name_invalid)) }
        } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(LelloStoreSpacing.small))
    when (state.mode) {
        ApprovalMode.NEW_DEVICE -> GroupsSection(state, onGroupToggled)
        ApprovalMode.REPLACE -> TokensSection(state, onReplaceTokenSelected)
    }
    state.error?.let { error ->
        Spacer(Modifier.height(LelloStoreSpacing.medium))
        Text(reviewErrorText(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(LelloStoreSpacing.xLarge))
    Row(horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { showDeny = true },
            enabled = !state.isSubmitting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.approve_deny)) }
        Button(
            onClick = onApprove,
            enabled = state.canApprove,
            colors = lelloStoreButtonColors(),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(LelloStoreSpacing.small))
            }
            Text(stringResource(R.string.approve_approve))
        }
    }

    if (showDeny) DenyDialog(pending.sourceIp, onDismiss = { showDeny = false }, onDeny = { block -> showDeny = false; onDeny(block) })
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun formatAge(seconds: Long): String =
    if (seconds < 60) stringResource(R.string.approve_seconds_ago, seconds) else stringResource(R.string.approve_minutes_ago, seconds / 60)

private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun ModeOption(selected: Boolean, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = LelloStoreSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = LelloStoreSpacing.medium)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupsSection(state: ApproveDeviceScreenState.Review, onGroupToggled: (Long) -> Unit) {
    Text(stringResource(R.string.approve_groups), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
    val groups = state.groups
    when {
        state.groupsUnavailable -> Text(
            stringResource(R.string.approve_groups_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        groups == null -> CircularProgressIndicator(Modifier.size(24.dp))
        else -> groups.forEach { group ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = group.selected, enabled = !state.isSubmitting, role = Role.Checkbox) { onGroupToggled(group.id) }
                    .padding(vertical = LelloStoreSpacing.xSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = group.selected, onCheckedChange = null, enabled = !state.isSubmitting)
                Text(
                    if (group.isAll) stringResource(R.string.approve_group_all, group.name) else group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = LelloStoreSpacing.medium),
                )
            }
        }
    }
}

@Composable
private fun TokensSection(state: ApproveDeviceScreenState.Review, onSelected: (String) -> Unit) {
    Text(stringResource(R.string.approve_replace_which), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
    val tokens = state.tokens
    when {
        state.tokensUnavailable -> Text(
            stringResource(R.string.approve_tokens_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
        tokens == null -> CircularProgressIndicator(Modifier.size(24.dp))
        tokens.isEmpty() -> Text(
            stringResource(R.string.approve_no_tokens),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        else -> tokens.forEachIndexed { index, token ->
            if (index > 0) HorizontalDivider()
            TokenRow(token, selected = token.id == state.replaceTokenId, enabled = !state.isSubmitting) { onSelected(token.id) }
        }
    }
}

@Composable
private fun TokenRow(token: DeviceTokenSummary, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = LelloStoreSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = LelloStoreSpacing.medium)) {
            Text(token.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (token.revokedAt != null) {
                    stringResource(R.string.approve_token_revoked, token.createdAt)
                } else {
                    stringResource(R.string.approve_token_created, token.createdAt)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DenyDialog(sourceIp: String, onDismiss: () -> Unit, onDeny: (Boolean) -> Unit) {
    // Off by default: the phone and the device usually share the home network's address.
    var block by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.approve_deny_title)) },
        text = {
            Column {
                Text(stringResource(R.string.approve_deny_message))
                Spacer(Modifier.height(LelloStoreSpacing.medium))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = block, role = Role.Checkbox) { block = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = block, onCheckedChange = null)
                    Text(
                        stringResource(R.string.approve_deny_block, sourceIp),
                        modifier = Modifier.padding(start = LelloStoreSpacing.medium),
                    )
                }
                Text(
                    stringResource(R.string.approve_deny_block_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDeny(block) }) {
                Text(stringResource(R.string.approve_deny), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DoneContent(result: DoneResult, canApproveAnother: Boolean, onApproveAnother: () -> Unit, onClose: () -> Unit) {
    val approved = result is DoneResult.Approved
    Icon(
        if (approved) Icons.Filled.CheckCircle else Icons.Filled.Info,
        contentDescription = null,
        tint = if (approved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.size(56.dp),
    )
    Spacer(Modifier.height(LelloStoreSpacing.large))
    val (title, message) = when (result) {
        is DoneResult.Approved -> stringResource(R.string.approve_done_title) to stringResource(
            if (result.rekeyed) R.string.approve_done_rekeyed else R.string.approve_done_new,
            result.name,
        )
        is DoneResult.Denied -> stringResource(R.string.approve_denied_title) to stringResource(
            if (result.blocked) R.string.approve_denied_blocked else R.string.approve_denied_message,
        )
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(LelloStoreSpacing.medium))
    Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(LelloStoreSpacing.xLarge))
    Row(horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium)) {
        if (canApproveAnother) OutlinedButton(onClick = onApproveAnother) { Text(stringResource(R.string.approve_another)) }
        Button(onClick = onClose, colors = lelloStoreButtonColors()) { Text(stringResource(R.string.approve_close)) }
    }
}
