package com.lelloman.store.ui.screen.settings

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
import androidx.compose.foundation.layout.heightIn
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
        onServerUrlInputChanged = viewModel::onServerUrlInputChanged,
        onServerUrlSave = viewModel::onServerUrlSave,
        onLogoutClick = viewModel::onLogoutClick,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsContent(
    state: SettingsScreenState,
    onThemeModeChanged: (ThemeModeOption) -> Unit,
    onUpdateCheckIntervalChanged: (UpdateCheckIntervalOption) -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onAutoUpdateDefaultChanged: (Boolean) -> Unit,
    onReleaseChannelDefaultChanged: (ReleaseChannelOption) -> Unit,
    onInstallationChannelEnabledChanged: (String, Boolean) -> Unit,
    onMoveInstallationChannel: (String, Int) -> Unit,
    onTestLegacyAdb: () -> Unit,
    onTestWirelessDebugging: () -> Unit,
    onPairWirelessDebugging: (String) -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onServerUrlInputChanged: (String) -> Unit,
    onServerUrlSave: () -> Unit,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var showReleaseChannelDialog by rememberSaveable { mutableStateOf(false) }
    var showLegacyAdbSetupDialog by rememberSaveable { mutableStateOf(false) }
    var showWirelessSetupDialog by rememberSaveable { mutableStateOf(false) }
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
                SettingsDivider()
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
            }

            SettingsSection(title = stringResource(R.string.settings_installation)) {
                Text(
                    text = stringResource(R.string.installation_channels_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                SettingsDivider()
                state.installationChannels.forEachIndexed { index, channel ->
                    InstallationChannelItem(
                        channel = channel,
                        canDisable = !channel.enabled ||
                            state.installationChannels.count { it.enabled } > 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.installationChannels.lastIndex,
                        onEnabledChanged = { enabled ->
                            onInstallationChannelEnabledChanged(channel.id, enabled)
                        },
                        onMoveUp = { onMoveInstallationChannel(channel.id, -1) },
                        onMoveDown = { onMoveInstallationChannel(channel.id, 1) },
                    )
                    SettingsDivider()
                }
                SettingsClickableItem(
                    title = stringResource(R.string.legacy_adb_setup),
                    subtitle = stringResource(R.string.legacy_adb_setup_subtitle),
                    onClick = { showLegacyAdbSetupDialog = true },
                )
                SettingsDivider()
                SettingsClickableItem(
                    title = stringResource(R.string.legacy_adb_test),
                    subtitle = adbConnectionStatus(state.legacyAdb),
                    onClick = onTestLegacyAdb,
                )
                SettingsDivider()
                SettingsClickableItem(
                    title = stringResource(R.string.wireless_debugging_setup),
                    subtitle = stringResource(R.string.wireless_debugging_setup_subtitle),
                    onClick = { showWirelessSetupDialog = true },
                )
                SettingsDivider()
                SettingsClickableItem(
                    title = stringResource(R.string.settings_wireless_debugging),
                    subtitle = when (val wireless = state.wirelessDebugging) {
                        AdbConnectionState.NotTested ->
                            stringResource(R.string.wireless_debugging_not_tested)
                        AdbConnectionState.Testing ->
                            stringResource(R.string.wireless_debugging_testing)
                        AdbConnectionState.Pairing ->
                            stringResource(R.string.wireless_debugging_pairing)
                        is AdbConnectionState.Ready ->
                            stringResource(R.string.wireless_debugging_ready, wireless.device)
                        is AdbConnectionState.Unavailable ->
                            stringResource(R.string.wireless_debugging_unavailable, wireless.reason)
                    },
                    onClick = onTestWirelessDebugging,
                )
                SettingsDivider()
                val setupContext = LocalContext.current
                SettingsClickableItem(
                    title = stringResource(R.string.settings_recovery_setup),
                    subtitle = stringResource(R.string.settings_recovery_setup_subtitle),
                    onClick = {
                        setupContext.startActivity(Intent().setClassName(
                            setupContext.packageName,
                            "com.lelloman.store.recovery.RecoverySetupActivity",
                        ))
                    },
                )
            }

            SettingsSection(title = stringResource(R.string.settings_server)) {
                ServerUrlInput(
                    serverUrlInput = state.serverUrlInput,
                    serverUrlError = state.serverUrlError,
                    isSaved = state.serverUrlInput == state.serverUrl,
                    onValueChange = onServerUrlInputChanged,
                    onSave = onServerUrlSave,
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

            SettingsSection(title = stringResource(R.string.settings_about)) {
                SettingsInfoItem(
                    title = stringResource(R.string.settings_app_version),
                    value = state.appVersion,
                )
            }

            Spacer(modifier = Modifier.height(LelloStoreSpacing.xxLarge))
        }
    }

    // Theme Selection Dialog
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

    // Update Interval Selection Dialog
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

    if (showWirelessSetupDialog) {
        WirelessDebuggingSetupDialog(
            state = state.wirelessDebugging,
            onOpenDeveloperSettings = onOpenDeveloperSettings,
            onPair = onPairWirelessDebugging,
            onDismiss = { showWirelessSetupDialog = false },
        )
    }

    if (showLegacyAdbSetupDialog) {
        LegacyAdbSetupDialog(onDismiss = { showLegacyAdbSetupDialog = false })
    }

    // Logout Confirmation Dialog
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

@Composable
private fun InstallationChannelItem(
    channel: InstallationChannelOption,
    canDisable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val name = when (channel.id) {
        "legacy-adb" -> stringResource(R.string.installation_channel_legacy_adb)
        "wireless-tls-adb" -> stringResource(R.string.installation_channel_wireless_adb)
        "package-installer" -> stringResource(R.string.installation_channel_package_installer)
        else -> channel.displayName
    }
    val type = stringResource(
        if (channel.requiresUserInteraction) {
            R.string.installation_channel_interactive
        } else {
            R.string.installation_channel_background
        }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = channel.enabled,
                enabled = canDisable,
                role = Role.Switch,
                onValueChange = onEnabledChanged,
            )
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = type,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.installation_channel_move_up, name),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.installation_channel_move_down, name),
            )
        }
        Switch(
            checked = channel.enabled,
            onCheckedChange = null,
            enabled = canDisable,
            colors = lelloStoreSwitchColors(),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun adbConnectionStatus(state: AdbConnectionState): String = when (state) {
    AdbConnectionState.NotTested -> stringResource(R.string.adb_connection_not_tested)
    AdbConnectionState.Testing,
    AdbConnectionState.Pairing -> stringResource(R.string.adb_connection_testing)
    is AdbConnectionState.Ready -> stringResource(R.string.adb_connection_ready, state.device)
    is AdbConnectionState.Unavailable ->
        stringResource(R.string.adb_connection_unavailable, state.reason)
}

@Composable
private fun LegacyAdbSetupDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.legacy_adb_setup)) },
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
private fun WirelessDebuggingSetupDialog(
    state: AdbConnectionState,
    onOpenDeveloperSettings: () -> Unit,
    onPair: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var showInstructions by rememberSaveable { mutableStateOf(false) }
    val isPairing = state == AdbConnectionState.Pairing

    AlertDialog(
        onDismissRequest = { if (!isPairing) onDismiss() },
        title = { Text(stringResource(R.string.wireless_debugging_setup)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { input ->
                        pairingCode = input.filter(Char::isDigit).take(PAIRING_CODE_LENGTH)
                    },
                    label = { Text(stringResource(R.string.pairing_code)) },
                    supportingText = {
                        when (state) {
                            AdbConnectionState.Pairing ->
                                Text(stringResource(R.string.wireless_debugging_pairing))
                            is AdbConnectionState.Ready ->
                                Text(stringResource(R.string.wireless_debugging_ready, state.device))
                            is AdbConnectionState.Unavailable ->
                                Text(stringResource(R.string.wireless_debugging_unavailable, state.reason))
                            else -> Text(stringResource(R.string.pairing_code_hint))
                        }
                    },
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
                enabled = pairingCode.length == PAIRING_CODE_LENGTH && !isPairing,
                colors = lelloStoreButtonColors(),
            ) {
                Text(stringResource(R.string.pair_and_test))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isPairing) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private const val PAIRING_CODE_LENGTH = 6

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
private fun SettingsClickableItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
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
private fun ServerUrlInput(
    serverUrlInput: String,
    serverUrlError: String?,
    isSaved: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(LelloStoreSpacing.large),
    ) {
        OutlinedTextField(
            value = serverUrlInput,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.login_server_url)) },
            isError = serverUrlError != null,
            supportingText = serverUrlError?.let {
                { Text(stringResource(R.string.settings_invalid_url)) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(LelloStoreSpacing.small))
        Button(
            onClick = onSave,
            enabled = !isSaved,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = lelloStoreButtonColors(),
        ) {
            Text(stringResource(if (isSaved) R.string.saved else R.string.save))
        }
    }
}
