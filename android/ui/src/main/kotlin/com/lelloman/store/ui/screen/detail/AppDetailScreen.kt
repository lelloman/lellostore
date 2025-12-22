package com.lelloman.store.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.lelloman.store.domain.download.DownloadState

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
                title = { Text(state.app?.name ?: "App Details") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                state.error != null && state.app == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.error ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = viewModel::onRetry) {
                            Text("Retry")
                        }
                    }
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
    modifier: Modifier = Modifier,
) {
    val isDownloading = downloadState != null &&
            downloadState != DownloadState.COMPLETED &&
            downloadState != DownloadState.FAILED &&
            downloadState != DownloadState.CANCELLED &&
            downloadState != DownloadState.PERMISSION_REQUIRED
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // App header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AsyncImage(
                model = app.iconUrl,
                contentDescription = "${app.name} icon",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "v${app.latestVersion.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons / Download progress
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (app.canInstall) {
                    Button(
                        onClick = onInstallClick,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Install")
                    }
                }
                if (app.canUpdate) {
                    Button(
                        onClick = onUpdateClick,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Update")
                    }
                }
                if (app.canOpen) {
                    OutlinedButton(
                        onClick = onOpenClick,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open")
                    }
                }
            }
        }

        // Installed version info
        app.installedVersion?.let { installed ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Installed Version",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "v${installed.versionName} (${installed.versionCode})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Description
        app.description?.let { description ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Version info
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Latest Version",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Version")
                    Text(app.latestVersion.versionName)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Size")
                    Text(app.latestVersion.size)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Released")
                    Text(app.latestVersion.uploadedAt)
                }
            }
        }

        // Version history
        if (app.versions.size > 1) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Version History",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            app.versions.drop(1).forEach { version ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
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
                        DownloadState.PENDING -> "Preparing..."
                        DownloadState.DOWNLOADING -> "Downloading... ${(progress * 100).toInt()}%"
                        DownloadState.VERIFYING -> "Verifying..."
                        DownloadState.INSTALLING -> "Installing..."
                        DownloadState.COMPLETED -> "Completed"
                        DownloadState.FAILED -> "Failed"
                        DownloadState.CANCELLED -> "Cancelled"
                        DownloadState.PERMISSION_REQUIRED -> "Permission required"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (downloadState == DownloadState.DOWNLOADING || downloadState == DownloadState.PENDING) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel download",
                        )
                    }
                }
            }

            if (downloadState == DownloadState.DOWNLOADING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (downloadState == DownloadState.PENDING ||
                downloadState == DownloadState.VERIFYING ||
                downloadState == DownloadState.INSTALLING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
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
                text = "Permission Required",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This app needs permission to install apps from unknown sources.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onGrantPermissionClick) {
                    Text("Grant Permission")
                }
                Button(onClick = onRetryClick) {
                    Text("Retry Install")
                }
            }
        }
    }
}
