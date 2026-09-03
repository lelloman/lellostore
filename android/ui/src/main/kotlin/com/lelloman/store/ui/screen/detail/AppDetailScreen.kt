package com.lelloman.store.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.preferences.AutoUpdateOverride
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.preferences.ReleaseChannelOverride
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreStateContent
import com.lelloman.store.ui.components.LelloStoreStatusBadge
import com.lelloman.store.ui.components.lelloStoreButtonColors
import com.lelloman.store.ui.theme.LelloGreen
import com.lelloman.store.ui.theme.LelloStoreSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResume()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AppDetailScreenEvent.OpenApp -> {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(event.packageName)
                    launchIntent?.let { context.startActivity(it) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.app?.name ?: stringResource(R.string.app_details)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading && state.app == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = LelloGreen,
                    )
                }
                state.error != null && state.app == null -> {
                    LelloStoreStateContent(
                        icon = Icons.Default.Refresh,
                        title = stringResource(R.string.app_detail_load_error_title),
                        message = state.error ?: stringResource(R.string.unknown_error),
                        actionLabel = stringResource(R.string.retry),
                        onAction = viewModel::onRetry,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.app != null -> {
                    AppDetailContent(
                        app = state.app!!,
                        downloadState = state.downloadState,
                        downloadProgress = state.downloadProgress,
                        onInstallClick = viewModel::onInstallClick,
                        onUpdateClick = viewModel::onUpdateClick,
                        onOpenClick = viewModel::onOpenClick,
                        onCancelDownload = viewModel::onCancelDownload,
                        onGrantPermissionClick = viewModel::onGrantPermissionClick,
                        onAutoUpdateOverrideChanged = viewModel::onAutoUpdateOverrideChanged,
                        onReleaseChannelOverrideChanged = viewModel::onReleaseChannelOverrideChanged,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppDetailContent(
    app: AppDetailUiModel,
    downloadState: DownloadState?,
    downloadProgress: Float,
    onInstallClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onOpenClick: () -> Unit,
    onCancelDownload: () -> Unit,
    onGrantPermissionClick: () -> Unit,
    onAutoUpdateOverrideChanged: (AutoUpdateOverride) -> Unit,
    onReleaseChannelOverrideChanged: (ReleaseChannelOverride) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAutoUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var showReleaseChannelDialog by rememberSaveable { mutableStateOf(false) }
    val isDownloading = downloadState != null &&
            downloadState != DownloadState.COMPLETED &&
            downloadState != DownloadState.FAILED &&
            downloadState != DownloadState.CANCELLED &&
            downloadState != DownloadState.PERMISSION_REQUIRED
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(LelloStoreSpacing.large),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            AppIdentityHeader(app = app)

            Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))
            VersionSummary(app = app)

            if (downloadState == DownloadState.COMPLETED ||
                downloadState == DownloadState.FAILED ||
                downloadState == DownloadState.CANCELLED
            ) {
                Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))
                InstallationResultSection(downloadState = downloadState)
            }

            Spacer(modifier = Modifier.height(LelloStoreSpacing.medium))

            if (isDownloading) {
                DownloadProgressSection(
                    downloadState = downloadState!!,
                    progress = downloadProgress,
                    onCancel = onCancelDownload,
                )
            } else if (downloadState == DownloadState.PERMISSION_REQUIRED) {
                PermissionRequiredSection(
                    onGrantPermissionClick = onGrantPermissionClick,
                    onRetryClick = onInstallClick,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.small),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (app.canInstall) {
                        Button(
                            onClick = onInstallClick,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            colors = lelloStoreButtonColors(),
                        ) {
                            Text(stringResource(R.string.install))
                        }
                    }
                    if (app.canUpdate) {
                        Button(
                            onClick = onUpdateClick,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            colors = lelloStoreButtonColors(),
                        ) {
                            Text(stringResource(R.string.update))
                        }
                    }
                    if (app.canOpen) {
                        OutlinedButton(
                            onClick = onOpenClick,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) {
                            Text(stringResource(R.string.open))
                        }
                    }
                }
            }

            if (app.isPolicyConfigurable) {
                Spacer(modifier = Modifier.height(LelloStoreSpacing.xLarge))
                SectionTitle(text = stringResource(R.string.app_update_preferences))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Column {
                        PolicyItem(
                            title = stringResource(R.string.app_auto_update),
                            value = app.autoUpdateOverride.displayName(app.effectiveAutoUpdate),
                            onClick = { showAutoUpdateDialog = true },
                        )
                        PolicyItem(
                            title = stringResource(R.string.app_release_channel),
                            value = app.releaseChannelOverride.displayName(app.effectiveReleaseChannel),
                            onClick = { showReleaseChannelDialog = true },
                        )
                    }
                }
            }

        // Description
        app.description?.let { description ->
                Spacer(modifier = Modifier.height(LelloStoreSpacing.xLarge))
                SectionTitle(text = stringResource(R.string.about))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(LelloStoreSpacing.large),
                    )
                }
        }

        // Version info
            Spacer(modifier = Modifier.height(LelloStoreSpacing.xLarge))
            SectionTitle(text = stringResource(R.string.latest_version))
        app.latestVersion?.let { latestVersion ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.version_label))
                        Text(latestVersion.versionName)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.size_label))
                        Text(latestVersion.size)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.released_label))
                        Text(latestVersion.uploadedAt)
                    }
                }
            }
        } ?: Text(
            text = stringResource(
                R.string.no_release_for_channel,
                app.effectiveReleaseChannel.displayName(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Version history
        if (app.versions.size > 1) {
                Spacer(modifier = Modifier.height(LelloStoreSpacing.xLarge))
                SectionTitle(text = stringResource(R.string.version_history))
            app.versions.drop(1).forEach { version ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("v${version.versionName}")
                        Text(version.uploadedAt)
                    }
                }
            }
        }
        }
    }


    if (showAutoUpdateDialog) {
        PolicySelectionDialog(
            title = stringResource(R.string.app_auto_update),
            options = AutoUpdateOverride.entries,
            label = { it.displayName(app.effectiveAutoUpdate) },
            onSelected = {
                onAutoUpdateOverrideChanged(it)
                showAutoUpdateDialog = false
            },
            onDismiss = { showAutoUpdateDialog = false },
        )
    }
    if (showReleaseChannelDialog) {
        val options = if (app.hasBetaAccess) {
            ReleaseChannelOverride.entries
        } else {
            ReleaseChannelOverride.entries.filterNot { it == ReleaseChannelOverride.Beta }
        }
        PolicySelectionDialog(
            title = stringResource(R.string.app_release_channel),
            options = options,
            label = { it.displayName(app.effectiveReleaseChannel) },
            onSelected = {
                onReleaseChannelOverrideChanged(it)
                showReleaseChannelDialog = false
            },
            onDismiss = { showReleaseChannelDialog = false },
        )
    }
}

@Composable
private fun AppIdentityHeader(
    app: AppDetailUiModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(LelloStoreSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = app.iconUrl,
                contentDescription = stringResource(R.string.content_description_app_icon, app.name),
                modifier = Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Spacer(Modifier.width(LelloStoreSpacing.large))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(LelloStoreSpacing.xSmall))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
                Spacer(Modifier.height(LelloStoreSpacing.small))
                LelloStoreStatusBadge(
                    label = app.effectiveReleaseChannel.displayName(),
                    emphasized = app.effectiveReleaseChannel == ReleaseChannel.Beta,
                )
            }
        }
    }
}

@Composable
private fun VersionSummary(
    app: AppDetailUiModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(LelloStoreSpacing.large)) {
            SummaryLine(
                label = stringResource(R.string.current_version),
                value = app.installedVersion?.let {
                    stringResource(R.string.version_name_and_code, it.versionName, it.versionCode)
                } ?: stringResource(R.string.not_installed),
            )
            Spacer(Modifier.height(LelloStoreSpacing.medium))
            SummaryLine(
                label = stringResource(R.string.available_version),
                value = app.latestVersion?.let {
                    stringResource(R.string.version_name_and_code, it.versionName, it.versionCode)
                } ?: stringResource(R.string.none_available),
            )
            Spacer(Modifier.height(LelloStoreSpacing.medium))
            SummaryLine(
                label = stringResource(R.string.effective_release_channel),
                value = app.effectiveReleaseChannel.displayName(),
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(LelloStoreSpacing.large))
        Text(text = value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            start = LelloStoreSpacing.xSmall,
            bottom = LelloStoreSpacing.small,
        ),
    )
}

@Composable
private fun InstallationResultSection(
    downloadState: DownloadState,
    modifier: Modifier = Modifier,
) {
    val isError = downloadState == DownloadState.FAILED
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Column(modifier = Modifier.padding(LelloStoreSpacing.large)) {
            Text(
                text = when (downloadState) {
                    DownloadState.COMPLETED -> stringResource(R.string.download_completed)
                    DownloadState.FAILED -> stringResource(R.string.download_failed)
                    DownloadState.CANCELLED -> stringResource(R.string.download_cancelled)
                    else -> return@Surface
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(LelloStoreSpacing.xSmall))
            Text(
                text = when (downloadState) {
                    DownloadState.COMPLETED -> stringResource(R.string.download_completed_hint)
                    DownloadState.FAILED -> stringResource(R.string.download_failed_hint)
                    DownloadState.CANCELLED -> stringResource(R.string.download_cancelled_hint)
                    else -> return@Surface
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AutoUpdateOverride.displayName(effective: Boolean): String = when (this) {
    AutoUpdateOverride.Inherit -> stringResource(
        R.string.use_default_with_value,
        stringResource(if (effective) R.string.option_on else R.string.option_off),
    )
    AutoUpdateOverride.Enabled -> stringResource(R.string.option_on)
    AutoUpdateOverride.Disabled -> stringResource(R.string.option_off)
}

@Composable
private fun ReleaseChannelOverride.displayName(effective: ReleaseChannel): String = when (this) {
    ReleaseChannelOverride.Inherit -> stringResource(
        R.string.use_default_with_value,
        effective.displayName(),
    )
    ReleaseChannelOverride.Stable -> stringResource(R.string.release_channel_stable)
    ReleaseChannelOverride.Beta -> stringResource(R.string.release_channel_beta)
}

@Composable
private fun ReleaseChannel.displayName(): String = when (this) {
    ReleaseChannel.Stable -> stringResource(R.string.release_channel_stable)
    ReleaseChannel.Beta -> stringResource(R.string.release_channel_beta)
}

@Composable
private fun PolicyItem(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LelloStoreSpacing.large, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> PolicySelectionDialog(
    title: String,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    TextButton(
                        onClick = { onSelected(option) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label(option)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DownloadProgressSection(
    downloadState: DownloadState,
    progress: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (downloadState) {
                        DownloadState.PENDING -> stringResource(R.string.download_preparing)
                        DownloadState.DOWNLOADING -> stringResource(R.string.download_downloading, (progress * 100).toInt())
                        DownloadState.VERIFYING -> stringResource(R.string.download_verifying)
                        DownloadState.INSTALLING -> stringResource(R.string.download_installing)
                        DownloadState.COMPLETED -> stringResource(R.string.download_completed)
                        DownloadState.FAILED -> stringResource(R.string.download_failed)
                        DownloadState.CANCELLED -> stringResource(R.string.download_cancelled)
                        DownloadState.PERMISSION_REQUIRED -> stringResource(R.string.download_permission_required)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (downloadState == DownloadState.DOWNLOADING || downloadState == DownloadState.PENDING) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.content_description_cancel_download),
                        )
                    }
                }
            }

            if (downloadState == DownloadState.DOWNLOADING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = LelloGreen,
                )
            } else if (downloadState == DownloadState.PENDING ||
                downloadState == DownloadState.VERIFYING ||
                downloadState == DownloadState.INSTALLING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = LelloGreen,
                )
            }
        }
    }
}

@Composable
private fun PermissionRequiredSection(
    onGrantPermissionClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.permission_required_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_required_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onGrantPermissionClick) {
                    Text(stringResource(R.string.grant_permission))
                }
                Button(
                    onClick = onRetryClick,
                    colors = lelloStoreButtonColors(),
                ) {
                    Text(stringResource(R.string.retry_install))
                }
            }
        }
    }
}
