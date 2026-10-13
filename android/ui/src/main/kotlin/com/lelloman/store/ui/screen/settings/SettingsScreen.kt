package com.lelloman.store.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.lelloStoreButtonColors
import com.lelloman.store.ui.components.lelloStoreSwitchColors
import com.lelloman.store.ui.theme.LelloStoreSpacing

@Composable
fun ThemeModeOption.getDisplayName(): String = when (this) {
    ThemeModeOption.System -> stringResource(R.string.theme_system)
    ThemeModeOption.Light -> stringResource(R.string.theme_light)
    ThemeModeOption.Dark -> stringResource(R.string.theme_dark)
}

@Composable
fun UpdateCheckIntervalOption.getDisplayName(): String = when (this) {
    UpdateCheckIntervalOption.Hours6 -> stringResource(R.string.interval_6_hours)
    UpdateCheckIntervalOption.Hours12 -> stringResource(R.string.interval_12_hours)
    UpdateCheckIntervalOption.Hours24 -> stringResource(R.string.interval_24_hours)
    UpdateCheckIntervalOption.Manual -> stringResource(R.string.interval_manual)
}

@Composable
fun ReleaseChannelOption.getDisplayName(): String = when (this) {
    ReleaseChannelOption.Stable -> stringResource(R.string.release_channel_stable)
    ReleaseChannelOption.Beta -> stringResource(R.string.release_channel_beta)
}

@Composable
fun SettingsScreen(
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsScreenEvent.NavigateToLogin -> onNavigateToLogin()
            }
        }
    }

    SettingsContent(
        state = state,
        onThemeModeChanged = viewModel::onThemeModeChanged,
        onUpdateCheckIntervalChanged = viewModel::onUpdateCheckIntervalChanged,
        onWifiOnlyDownloadsChanged = viewModel::onWifiOnlyDownloadsChanged,
        onKeepUpdateConnectionChanged = viewModel::onKeepUpdateConnectionChanged,
        onAutoUpdateDefaultChanged = viewModel::onAutoUpdateDefaultChanged,
        onReleaseChannelDefaultChanged = viewModel::onReleaseChannelDefaultChanged,
        onInstallationChannelEnabledChanged = viewModel::onInstallationChannelEnabledChanged,
        onMoveInstallationChannel = viewModel::onMoveInstallationChannel,
        onTestLegacyAdb = viewModel::onTestLegacyAdb,
        onTestWirelessDebugging = viewModel::onTestWirelessDebugging,
        onPairWirelessDebugging = viewModel::onPairWirelessDebugging,
        onOpenDeveloperSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        },
        onOpenPushRegistrations = {
            context.startAppActivity("com.lelloman.store.notification.PushRegistrationsActivity")
        },
        onOpenRecoverySetup = {
            context.startAppActivity("com.lelloman.store.recovery.RecoverySetupActivity")
        },
        onOpenLocalLogs = {
            context.startAppActivity("com.lelloman.store.diagnostics.AuditActivity")
        },
        onServerUrlInputChanged = viewModel::onServerUrlInputChanged,
        onServerUrlSave = viewModel::onServerUrlSave,
        onLogoutClick = viewModel::onLogoutClick,
        modifier = modifier,
    )
}

/** These activities live in the app module, which this module cannot reference directly. */
private fun Context.startAppActivity(className: String) {
    startActivity(Intent().setClassName(packageName, className))
}

@Composable
internal fun SettingsContent(
    state: SettingsScreenState,
    onThemeModeChanged: (ThemeModeOption) -> Unit,
    onUpdateCheckIntervalChanged: (UpdateCheckIntervalOption) -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onKeepUpdateConnectionChanged: (Boolean) -> Unit,
    onAutoUpdateDefaultChanged: (Boolean) -> Unit,
    onReleaseChannelDefaultChanged: (ReleaseChannelOption) -> Unit,
    onInstallationChannelEnabledChanged: (String, Boolean) -> Unit,
    onMoveInstallationChannel: (String, Int) -> Unit,
    onTestLegacyAdb: () -> Unit,
    onTestWirelessDebugging: () -> Unit,
    onPairWirelessDebugging: (String) -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onOpenPushRegistrations: () -> Unit,
    onOpenRecoverySetup: () -> Unit,
    onOpenLocalLogs: () -> Unit,
    onServerUrlInputChanged: (String) -> Unit,
    onServerUrlSave: () -> Unit,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var showReleaseChannelDialog by rememberSaveable { mutableStateOf(false) }
    var showChannelOrderDialog by rememberSaveable { mutableStateOf(false) }
    var showLegacyAdbDialog by rememberSaveable { mutableStateOf(false) }
    var showWirelessDialog by rememberSaveable { mutableStateOf(false) }
    var showServerDialog by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirmation by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LelloStoreSpacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
            SettingsSection(title = stringResource(R.string.settings_appearance)) {
                SettingsClickableItem(
                    title = stringResource(R.string.settings_theme),
                    subtitle = state.themeMode.getDisplayName(),
                    onClick = { showThemeDialog = true },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_updates)) {
                SettingsClickableItem(
                    title = stringResource(R.string.settings_check_for_updates),
                    subtitle = state.updateCheckInterval.getDisplayName(),
                    onClick = { showIntervalDialog = true },
                )
                SettingsDivider()
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_wifi_only),
                    subtitle = stringResource(R.string.settings_wifi_only_subtitle),
                    checked = state.wifiOnlyDownloads,
                    onCheckedChange = onWifiOnlyDownloadsChanged,
                )
                SettingsDivider()
                SettingsHint(stringResource(R.string.settings_update_defaults_hint))
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_auto_update_default),
                    subtitle = stringResource(R.string.settings_auto_update_default_subtitle),
                    checked = state.autoUpdateDefault,
                    onCheckedChange = onAutoUpdateDefaultChanged,
                )
                SettingsDivider()
                SettingsClickableItem(
                    title = stringResource(R.string.settings_release_channel_default),
                    subtitle = state.releaseChannelDefault.getDisplayName(),
                    onClick = { showReleaseChannelDialog = true },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_notifications)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_keep_update_connection),
                    subtitle = stringResource(R.string.settings_keep_update_connection_subtitle),
                    checked = state.keepUpdateConnection,
                    onCheckedChange = onKeepUpdateConnectionChanged,
                )
                if (state.keepUpdateConnection) PushBatteryWarning()
                SettingsDivider()
                SettingsClickableItem(
                    title = stringResource(R.string.settings_push_registrations),
                    subtitle = stringResource(R.string.settings_push_registrations_subtitle),
                    onClick = onOpenPushRegistrations,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_installation)) {
                state.installationChannels.forEach { channel ->
                    InstallationChannelItem(
                        name = channel.localizedName(),
                        status = when (channel.id) {
                            LEGACY_ADB_CHANNEL -> adbConnectionStatus(state.legacyAdb)
                            WIRELESS_ADB_CHANNEL -> adbConnectionStatus(state.wirelessDebugging)
                            else -> stringResource(
                                if (channel.requiresUserInteraction) {
                                    R.string.installation_channel_interactive
                                } else {
                                    R.string.installation_channel_background
                                }
                            )
                        },
                        enabled = channel.enabled,
                        canDisable = !channel.enabled ||
                            state.installationChannels.count { it.enabled } > 1,
                        onEnabledChanged = { enabled ->
                            onInstallationChannelEnabledChanged(channel.id, enabled)
                        },
                        onClick = when (channel.id) {
                            LEGACY_ADB_CHANNEL -> { { showLegacyAdbDialog = true } }
                            WIRELESS_ADB_CHANNEL -> { { showWirelessDialog = true } }
                            else -> null
                        },
                    )
                    SettingsDivider()
                }
                SettingsClickableItem(
                    title = stringResource(R.string.installation_channel_order),
                    subtitle = stringResource(R.string.installation_channel_order_subtitle),
                    onClick = { showChannelOrderDialog = true },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_account)) {
                state.userEmail?.let { email ->
                    SettingsInfoItem(
                        title = stringResource(R.string.settings_logged_in_as),
                        value = email,
                    )
                    SettingsDivider()
                }
                SettingsClickableItem(
                    title = stringResource(R.string.settings_server),
                    subtitle = state.serverUrl,
                    onClick = {
                        onServerUrlInputChanged(state.serverUrl)
                        showServerDialog = true
                    },
                )
                SettingsDivider()
                SettingsClickableItem(
                    title = stringResource(R.string.logout),
                    subtitle = stringResource(R.string.logout_subtitle),
                    onClick = { showLogoutConfirmation = true },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    textColor = MaterialTheme.colorScheme.error,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_system)) {
                SettingsClickableItem(
                    title = stringResource(R.string.settings_recovery_setup),
                    subtitle = stringResource(R.string.settings_recovery_setup_subtitle),
                    onClick = onOpenRecoverySetup,
                )
                SettingsDivider()
                SettingsClickableItem(
                    title = stringResource(R.string.settings_audit_title),
                    subtitle = stringResource(R.string.settings_audit_subtitle),
                    onClick = onOpenLocalLogs,
                )
                SettingsDivider()
                SettingsInfoItem(
                    title = stringResource(R.string.settings_app_version),
                    value = state.appVersion,
                )
            }

            Spacer(modifier = Modifier.height(LelloStoreSpacing.xxLarge))
        }
    }

    if (showThemeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeModeOption.entries,
            selectedOption = state.themeMode,
            optionLabel = { it.getDisplayName() },
            onOptionSelected = {
                onThemeModeChanged(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showIntervalDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_check_for_updates),
            options = UpdateCheckIntervalOption.entries,
            selectedOption = state.updateCheckInterval,
            optionLabel = { it.getDisplayName() },
            onOptionSelected = {
                onUpdateCheckIntervalChanged(it)
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false },
        )
    }

    if (showReleaseChannelDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_release_channel_default),
            options = ReleaseChannelOption.entries,
            selectedOption = state.releaseChannelDefault,
            optionLabel = { it.getDisplayName() },
            onOptionSelected = {
                onReleaseChannelDefaultChanged(it)
                showReleaseChannelDialog = false
            },
            onDismiss = { showReleaseChannelDialog = false },
        )
    }

    if (showChannelOrderDialog) {
        ChannelOrderDialog(
            channels = state.installationChannels,
            onMove = onMoveInstallationChannel,
            onDismiss = { showChannelOrderDialog = false },
        )
    }

    if (showWirelessDialog) {
        WirelessDebuggingDialog(
            state = state.wirelessDebugging,
            onTest = onTestWirelessDebugging,
            onOpenDeveloperSettings = onOpenDeveloperSettings,
            onPair = onPairWirelessDebugging,
            onDismiss = { showWirelessDialog = false },
        )
    }

    if (showLegacyAdbDialog) {
        LegacyAdbDialog(
            state = state.legacyAdb,
            onTest = onTestLegacyAdb,
            onDismiss = { showLegacyAdbDialog = false },
        )
    }

    if (showServerDialog) {
        ServerUrlDialog(
            serverUrl = state.serverUrl,
            serverUrlInput = state.serverUrlInput,
            serverUrlError = state.serverUrlError,
            saving = state.serverUrlSaving,
            onValueChange = onServerUrlInputChanged,
            onSave = onServerUrlSave,
            onDismiss = { showServerDialog = false },
        )
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmation = false
                        onLogoutClick()
                    }
                ) {
                    Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private const val LEGACY_ADB_CHANNEL = "legacy-adb"
private const val WIRELESS_ADB_CHANNEL = "wireless-tls-adb"
private const val PACKAGE_INSTALLER_CHANNEL = "package-installer"

@Composable
private fun InstallationChannelOption.localizedName(): String = when (id) {
    LEGACY_ADB_CHANNEL -> stringResource(R.string.installation_channel_legacy_adb)
    WIRELESS_ADB_CHANNEL -> stringResource(R.string.installation_channel_wireless_adb)
    PACKAGE_INSTALLER_CHANNEL -> stringResource(R.string.installation_channel_package_installer)
    else -> displayName
}

/**
 * Channels with a setup flow open it on row tap and expose the switch as a separate target;
 * the others toggle from anywhere on the row.
 */
@Composable
private fun InstallationChannelItem(
    name: String,
    status: String,
    enabled: Boolean,
    canDisable: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onClick: (() -> Unit)?,
) {
    val rowAction = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.toggleable(
            value = enabled,
            enabled = canDisable,
            role = Role.Switch,
            onValueChange = onEnabledChanged,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowAction)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = enabled,
            onCheckedChange = if (onClick != null) onEnabledChanged else null,
            enabled = canDisable,
            colors = lelloStoreSwitchColors(),
        )
    }
}

@Composable
private fun adbConnectionStatus(state: AdbConnectionState): String = when (state) {
    AdbConnectionState.NotTested -> stringResource(R.string.adb_connection_not_tested)
    AdbConnectionState.Testing -> stringResource(R.string.adb_connection_testing)
    AdbConnectionState.Pairing -> stringResource(R.string.wireless_debugging_pairing)
    is AdbConnectionState.Ready -> stringResource(R.string.adb_connection_ready, state.device)
    is AdbConnectionState.Unavailable ->
        stringResource(R.string.adb_connection_unavailable, state.reason)
}

@Composable
private fun ChannelOrderDialog(
    channels: List<InstallationChannelOption>,
    onMove: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.installation_channel_order)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.installation_channels_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                channels.forEachIndexed { index, channel ->
                    val name = channel.localizedName()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (channel.enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onMove(channel.id, -1) }, enabled = index > 0) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.installation_channel_move_up, name),
                            )
                        }
                        IconButton(
                            onClick = { onMove(channel.id, 1) },
                            enabled = index < channels.lastIndex,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.installation_channel_move_down, name),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

@Composable
private fun ConnectionStatusText(state: AdbConnectionState) {
    Text(
        text = adbConnectionStatus(state),
        style = MaterialTheme.typography.bodyMedium,
        color = if (state is AdbConnectionState.Unavailable) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun LegacyAdbDialog(
    state: AdbConnectionState,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.installation_channel_legacy_adb)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.legacy_adb_intro))
                Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))
                Text(stringResource(R.string.legacy_adb_step_one))
                Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                Text(stringResource(R.string.legacy_adb_step_two))
                Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.legacy_adb_command),
                        modifier = Modifier.padding(LelloStoreSpacing.medium),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                Text(stringResource(R.string.legacy_adb_step_three))
                Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))
                Text(
                    text = stringResource(R.string.legacy_adb_security_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))
                ConnectionStatusText(state)
            }
        },
        confirmButton = {
            Button(
                onClick = onTest,
                enabled = !state.isBusy,
                colors = lelloStoreButtonColors(),
            ) {
                Text(stringResource(R.string.test_connection))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

@Composable
private fun WirelessDebuggingDialog(
    state: AdbConnectionState,
    onTest: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onPair: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var showInstructions by rememberSaveable { mutableStateOf(false) }
    val isPairing = state == AdbConnectionState.Pairing

    AlertDialog(
        onDismissRequest = { if (!isPairing) onDismiss() },
        title = { Text(stringResource(R.string.installation_channel_wireless_adb)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.wireless_debugging_setup_intro))
                Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))
                ConnectionStatusText(state)
                Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                OutlinedButton(
                    onClick = onTest,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.test_connection))
                }
                Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { input ->
                        pairingCode = input.filter(Char::isDigit).take(PAIRING_CODE_LENGTH)
                    },
                    label = { Text(stringResource(R.string.pairing_code)) },
                    supportingText = { Text(stringResource(R.string.pairing_code_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !isPairing,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                Text(
                    stringResource(R.string.pairing_split_screen_reminder),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { showInstructions = !showInstructions }) {
                    Text(stringResource(
                        if (showInstructions) R.string.pairing_hide_help else R.string.pairing_show_help
                    ))
                    Icon(
                        if (showInstructions) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
                if (showInstructions) {
                    Text(stringResource(R.string.wireless_debugging_step_one))
                    Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                    Text(stringResource(R.string.wireless_debugging_step_two))
                    Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                    Text(stringResource(R.string.wireless_debugging_step_three))
                    Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                    Text(stringResource(R.string.wireless_debugging_step_four))
                    Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
                    OutlinedButton(
                        onClick = onOpenDeveloperSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_developer_settings))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPair(pairingCode) },
                enabled = pairingCode.length == PAIRING_CODE_LENGTH && !state.isBusy,
                colors = lelloStoreButtonColors(),
            ) {
                Text(stringResource(R.string.pair_and_test))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isPairing) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

private const val PAIRING_CODE_LENGTH = 6

@Composable
private fun ServerUrlDialog(
    serverUrl: String,
    serverUrlInput: String,
    serverUrlError: ServerUrlError?,
    saving: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.settings_server_change)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_server_change_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.login_server_url)) },
                    isError = serverUrlError != null,
                    supportingText = serverUrlError?.let { error ->
                        {
                            Text(
                                when (error) {
                                    ServerUrlError.Invalid ->
                                        stringResource(R.string.settings_server_invalid_url)
                                    is ServerUrlError.Unreachable ->
                                        stringResource(R.string.settings_server_unreachable, error.message)
                                }
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !saving && serverUrlInput.isNotBlank() && serverUrlInput != serverUrl,
                colors = lelloStoreButtonColors(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.padding(top = LelloStoreSpacing.large)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(
                start = LelloStoreSpacing.xSmall,
                bottom = LelloStoreSpacing.small,
            ),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = LelloStoreSpacing.large))
}

@Composable
private fun SettingsHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
}

@Composable
private fun SettingsClickableItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = lelloStoreSwitchColors(),
        )
    }
}

@Composable
private fun SettingsInfoItem(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun <T> SelectionDialog(
    title: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = optionLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (option == selectedOption) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.content_description_selected),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun PushBatteryWarning() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var exempt by remember { mutableStateOf(context.hasPushBatteryExemption()) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = context.hasPushBatteryExemption()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!exempt) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.push_battery_warning_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.push_battery_warning_text))
                TextButton(onClick = { context.openPushBatterySettings() }) {
                    Text(stringResource(R.string.push_battery_open_settings))
                }
            }
        }
    }
}
