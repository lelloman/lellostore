package com.lelloman.store.ui.screen.enrollment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreBrandMark
import com.lelloman.store.ui.components.QrCode
import com.lelloman.store.ui.components.isTelevision
import com.lelloman.store.ui.components.lelloStoreButtonColors
import com.lelloman.store.ui.screen.settings.canRequestPushBatteryExemption
import com.lelloman.store.ui.screen.settings.getDisplayName
import com.lelloman.store.ui.screen.settings.hasPushBatteryExemption
import com.lelloman.store.ui.screen.settings.openPushBatterySettings
import com.lelloman.store.ui.theme.LelloStoreSpacing
import kotlinx.coroutines.delay

/** Device-code enrollment: works with the D-pad alone, as on a TV box. */
@Composable
fun EnrollmentScreen(
    onNavigateToMain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnrollmentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                EnrollmentScreenEvent.NavigateToMain -> onNavigateToMain()
            }
        }
    }
    // Poll only while the screen is visible.
    LifecycleStartEffect(viewModel) {
        viewModel.onVisibilityChanged(true)
        onStopOrDispose { viewModel.onVisibilityChanged(false) }
    }
    // Once enrolled there is nothing to go back to; Continue leaves the screen.
    BackHandler(enabled = state is EnrollmentScreenState.Completing || state is EnrollmentScreenState.Completed) {
        viewModel.onContinue()
    }

    EnrollmentContent(
        state = state,
        onNameChanged = viewModel::onNameChanged,
        onStart = viewModel::onStart,
        onRetry = viewModel::onRetry,
        onChangeName = viewModel::onChangeName,
        onContinue = viewModel::onContinue,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun EnrollmentContent(
    state: EnrollmentScreenState,
    onNameChanged: (String) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onChangeName: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) LelloStoreSpacing.hero else LelloStoreSpacing.xLarge, vertical = LelloStoreSpacing.xLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state) {
                is EnrollmentScreenState.Naming -> NamingContent(state, onNameChanged, onStart, onBack)
                is EnrollmentScreenState.Waiting -> WaitingContent(state, wide, onChangeName, onBack)
                is EnrollmentScreenState.Stopped -> StoppedContent(state, onRetry, onChangeName, onBack)
                EnrollmentScreenState.Completing -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(LelloStoreSpacing.large))
                    Text(stringResource(R.string.enrollment_completing), style = MaterialTheme.typography.bodyLarge)
                }
                is EnrollmentScreenState.Completed -> CompletedContent(state.completion, onContinue)
            }
        }
    }
}

/** Gives [requester] the initial focus, so the D-pad starts on the main action. */
@Composable
private fun InitialFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
}

@Composable
private fun ColumnScope.NamingContent(
    state: EnrollmentScreenState.Naming,
    onNameChanged: (String) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val start = remember { FocusRequester() }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LelloStoreBrandMark(contentDescription = null, size = 72.dp)
        Spacer(Modifier.height(LelloStoreSpacing.large))
        Text(stringResource(R.string.enrollment_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(LelloStoreSpacing.medium))
        Text(
            stringResource(R.string.enrollment_naming_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LelloStoreSpacing.xLarge))
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChanged,
            label = { Text(stringResource(R.string.enrollment_device_name)) },
            isError = state.invalidName,
            supportingText = if (state.invalidName) {
                { Text(stringResource(R.string.enrollment_device_name_invalid)) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onStart() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LelloStoreSpacing.large))
        Button(
            onClick = onStart,
            colors = lelloStoreButtonColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(start),
        ) {
            Text(stringResource(R.string.enrollment_get_code))
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.enrollment_back_to_sign_in))
        }
    }
    InitialFocus(start)
}

@Composable
private fun WaitingContent(
    state: EnrollmentScreenState.Waiting,
    wide: Boolean,
    onChangeName: () -> Unit,
    onBack: () -> Unit,
) {
    val firstAction = remember { FocusRequester() }
    val details: @Composable ColumnScope.() -> Unit = {
        Text(
            stringResource(R.string.enrollment_waiting_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = if (wide) TextAlign.Start else TextAlign.Center,
        )
        Spacer(Modifier.height(LelloStoreSpacing.medium))
        val code = state.code
        if (code == null) {
            Text(
                stringResource(if (state.offline) R.string.enrollment_offline else R.string.enrollment_getting_code, state.storeHost),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                stringResource(R.string.enrollment_instructions, code.verificationUri),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(LelloStoreSpacing.large))
            Text(
                text = code.userCode,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 56.sp,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(LelloStoreSpacing.medium))
            Text(
                text = if (state.offline) {
                    stringResource(R.string.enrollment_offline, state.storeHost)
                } else {
                    stringResource(R.string.enrollment_waiting_status, formatRemaining(state.remainingSeconds))
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.expiredCodes > 0) {
                Text(
                    stringResource(R.string.enrollment_new_code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(LelloStoreSpacing.medium))
        Text(
            stringResource(R.string.enrollment_named_as, state.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LelloStoreSpacing.large))
        Row(horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium)) {
            OutlinedButton(onClick = onChangeName, modifier = Modifier.focusRequester(firstAction)) {
                Text(stringResource(R.string.enrollment_change_name))
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
        }
    }
    val qr: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 280.dp)) {
            val code = state.code
            if (code == null) {
                CircularProgressIndicator(Modifier.size(48.dp))
            } else {
                QrCode(
                    content = code.verificationUriComplete,
                    contentDescription = stringResource(R.string.enrollment_qr_description),
                    modifier = Modifier.size(240.dp),
                )
                Spacer(Modifier.height(LelloStoreSpacing.medium))
                Text(
                    stringResource(R.string.enrollment_qr_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (wide) {
        Row(
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.hero),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), content = details)
            qr()
        }
    } else {
        Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            qr()
            Spacer(Modifier.height(LelloStoreSpacing.xLarge))
            details()
        }
    }
    InitialFocus(firstAction)
}

private fun formatRemaining(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun StoppedContent(
    state: EnrollmentScreenState.Stopped,
    onRetry: () -> Unit,
    onChangeName: () -> Unit,
    onBack: () -> Unit,
) {
    val primary = remember { FocusRequester() }
    val (title, message) = when (state.reason) {
        StopReason.EXPIRED -> R.string.enrollment_expired_title to R.string.enrollment_expired_message
        StopReason.DENIED -> R.string.enrollment_denied_title to R.string.enrollment_denied_message
        StopReason.BLOCKED -> R.string.enrollment_blocked_title to R.string.enrollment_blocked_message
        StopReason.RATE_LIMITED -> R.string.enrollment_rate_limited_title to R.string.enrollment_rate_limited_message
        StopReason.REFUSED -> R.string.enrollment_refused_title to R.string.enrollment_refused_message
        StopReason.UNAVAILABLE -> R.string.enrollment_unavailable_title to R.string.enrollment_unavailable_message
        StopReason.NOT_STORED -> R.string.enrollment_not_stored_title to R.string.enrollment_not_stored_message
    }
    val canRetry = state.reason != StopReason.UNAVAILABLE
    Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(LelloStoreSpacing.large))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(LelloStoreSpacing.medium))
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(LelloStoreSpacing.xLarge))
        if (canRetry) {
            Button(
                onClick = onRetry,
                colors = lelloStoreButtonColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(primary),
            ) { Text(stringResource(R.string.enrollment_try_again)) }
            TextButton(onClick = onChangeName, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.enrollment_change_name))
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.enrollment_back_to_sign_in))
            }
        } else {
            Button(
                onClick = onBack,
                colors = lelloStoreButtonColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(primary),
            ) { Text(stringResource(R.string.enrollment_back_to_sign_in)) }
        }
    }
    InitialFocus(primary, state.reason)
}

@Composable
private fun CompletedContent(completion: EnrollmentCompletion, onContinue: () -> Unit) {
    val context = LocalContext.current
    val television = isTelevision()
    val proceed = remember { FocusRequester() }
    // Re-read on every resume: the user may come back from a system prompt or settings screen.
    var batteryExempt by remember { mutableStateOf(context.hasPushBatteryExemption()) }
    var notificationsAllowed by remember { mutableStateOf(context.notificationsAllowed()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryExempt = context.hasPushBatteryExemption()
        notificationsAllowed = context.notificationsAllowed()
    }
    val canAskBattery = remember { context.canRequestPushBatteryExemption() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        // Asked again after a refusal, Android shows nothing: offer its notification settings.
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
    }

    // The update connection needs unrestricted battery use to stay open in Doze. Ask once, after
    // any prompt already on screen (LelloStore asks for notifications on sign-in) is answered.
    var batteryAsked by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(BATTERY_PROMPT_DELAY_MILLIS)
            if (!batteryAsked && completion.updateConnection && canAskBattery && !context.hasPushBatteryExemption()) {
                batteryAsked = true
                context.openPushBatterySettings()
            }
        }
    }

    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(LelloStoreSpacing.large))
        Text(stringResource(R.string.enrollment_done_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(LelloStoreSpacing.small))
        Text(
            stringResource(R.string.enrollment_done_message, completion.deviceName),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LelloStoreSpacing.xLarge))
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LelloStoreSpacing.large), verticalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium)) {
                Text(stringResource(R.string.enrollment_turned_on), style = MaterialTheme.typography.titleMedium)
                if (completion.automaticUpdates) {
                    EnabledItem(
                        title = stringResource(R.string.enrollment_auto_updates_on),
                        detail = stringResource(R.string.enrollment_auto_updates_detail, completion.updateCheckInterval.getDisplayName()),
                    )
                }
                if (completion.updateConnection) {
                    EnabledItem(
                        title = stringResource(R.string.enrollment_connection_on),
                        detail = stringResource(R.string.enrollment_connection_detail),
                    )
                }
                if (completion.updateConnection && (canAskBattery || !television)) {
                    PermissionItem(
                        title = stringResource(R.string.enrollment_battery),
                        granted = batteryExempt,
                        onAllow = { context.openPushBatterySettings() },
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionItem(
                        title = stringResource(R.string.enrollment_notifications),
                        granted = notificationsAllowed,
                        onAllow = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
                Text(
                    stringResource(R.string.enrollment_change_in_settings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(LelloStoreSpacing.xLarge))
        Button(
            onClick = onContinue,
            colors = lelloStoreButtonColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).focusRequester(proceed),
        ) { Text(stringResource(R.string.enrollment_continue)) }
    }
    InitialFocus(proceed)
}

@Composable
private fun EnabledItem(title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = LelloStoreSpacing.medium)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionItem(title: String, granted: Boolean, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (granted) R.string.enrollment_allowed else R.string.enrollment_not_allowed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.enrollment_allow)) }
        }
    }
}

private fun android.content.Context.notificationsAllowed(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private const val BATTERY_PROMPT_DELAY_MILLIS = 700L
