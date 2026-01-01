package com.lelloman.store.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lelloman.store.ui.R

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
fun SettingsScreen(
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

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
        onServerUrlInputChanged = viewModel::onServerUrlInputChanged,
        onServerUrlSave = viewModel::onServerUrlSave,
        onLogoutClick = viewModel::onLogoutClick,
        modifier = modifier,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsScreenState,
    onThemeModeChanged: (ThemeModeOption) -> Unit,
    onUpdateCheckIntervalChanged: (UpdateCheckIntervalOption) -> Unit,
    onWifiOnlyDownloadsChanged: (Boolean) -> Unit,
    onServerUrlInputChanged: (String) -> Unit,
    onServerUrlSave: () -> Unit,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Appearance Section
        SettingsSectionHeader(title = stringResource(R.string.settings_appearance))

        SettingsClickableItem(
            title = stringResource(R.string.settings_theme),
            subtitle = state.themeMode.getDisplayName(),
            onClick = { showThemeDialog = true },
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Updates Section
        SettingsSectionHeader(title = stringResource(R.string.settings_updates))

        SettingsClickableItem(
            title = stringResource(R.string.settings_check_for_updates),
            subtitle = state.updateCheckInterval.getDisplayName(),
            onClick = { showIntervalDialog = true },
        )

        SettingsSwitchItem(
            title = stringResource(R.string.settings_wifi_only),
            subtitle = stringResource(R.string.settings_wifi_only_subtitle),
            checked = state.wifiOnlyDownloads,
            onCheckedChange = onWifiOnlyDownloadsChanged,
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Server Section
        SettingsSectionHeader(title = stringResource(R.string.settings_server))

        ServerUrlInput(
            serverUrlInput = state.serverUrlInput,
            serverUrlError = state.serverUrlError,
            isSaved = state.serverUrlInput == state.serverUrl,
            onValueChange = onServerUrlInputChanged,
            onSave = onServerUrlSave,
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Account Section
        SettingsSectionHeader(title = stringResource(R.string.settings_account))

        state.userEmail?.let { email ->
            SettingsInfoItem(
                title = stringResource(R.string.settings_logged_in_as),
                value = email,
            )
        }

        SettingsClickableItem(
            title = stringResource(R.string.logout),
            subtitle = null,
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

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // About Section
        SettingsSectionHeader(title = stringResource(R.string.settings_about))

        SettingsInfoItem(
            title = stringResource(R.string.settings_app_version),
            value = state.appVersion,
        )

        Spacer(modifier = Modifier.height(32.dp))
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
private fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 16.dp),
    )
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
            .clickable { onCheckedChange(!checked) }
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
            onCheckedChange = onCheckedChange,
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
                                tint = MaterialTheme.colorScheme.primary,
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
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = serverUrlInput,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.login_server_url)) },
            isError = serverUrlError != null,
            supportingText = serverUrlError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onSave,
            enabled = !isSaved,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(if (isSaved) R.string.saved else R.string.save))
        }
    }
}
