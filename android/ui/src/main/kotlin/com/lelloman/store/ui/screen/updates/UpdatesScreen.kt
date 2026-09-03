package com.lelloman.store.ui.screen.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreStateContent
import com.lelloman.store.ui.components.LelloStoreStatusBadge
import com.lelloman.store.ui.components.lelloStoreButtonColors
import com.lelloman.store.ui.theme.LelloGreen
import com.lelloman.store.ui.theme.LelloStoreSpacing

@Composable
fun UpdatesScreen(
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpdatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading && state.updates.isEmpty() -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = LelloGreen,
                )
            }
            state.error != null && state.updates.isEmpty() -> {
                LelloStoreStateContent(
                    icon = Icons.Default.Refresh,
                    title = stringResource(R.string.updates_load_error_title),
                    message = state.error!!,
                    actionLabel = stringResource(R.string.retry),
                    onAction = viewModel::refreshUpdates,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            state.updates.isEmpty() -> {
                LelloStoreStateContent(
                    icon = Icons.Default.Refresh,
                    title = stringResource(R.string.all_apps_up_to_date),
                    message = stringResource(R.string.check_back_later),
                    actionLabel = if (state.isRefreshing) null else stringResource(R.string.settings_check_for_updates),
                    onAction = if (state.isRefreshing) null else viewModel::refreshUpdates,
                    modifier = Modifier.align(Alignment.Center),
                )
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 184.dp),
                        color = LelloGreen,
                    )
                }
            }
            else -> {
                UpdatesList(
                    state = state,
                    onAppClick = onAppClick,
                    onUpdateClick = viewModel::onUpdateClick,
                    onRefresh = viewModel::refreshUpdates,
                )
            }
        }
    }
}

@Composable
private fun UpdatesList(
    state: UpdatesScreenState,
    onAppClick: (String) -> Unit,
    onUpdateClick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LelloStoreSpacing.large),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(
                    start = LelloStoreSpacing.large,
                    top = LelloStoreSpacing.small,
                    end = LelloStoreSpacing.small,
                    bottom = LelloStoreSpacing.small,
                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = pluralStringResource(
                            R.plurals.updates_available,
                            state.updates.size,
                            state.updates.size,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.updates_header_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        color = LelloGreen,
                        modifier = Modifier.padding(LelloStoreSpacing.medium),
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.content_description_check_updates),
                        )
                    }
                }
            }
        }

        state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(
                    horizontal = LelloStoreSpacing.xLarge,
                    vertical = LelloStoreSpacing.small,
                ),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = LelloStoreSpacing.large,
                top = LelloStoreSpacing.medium,
                end = LelloStoreSpacing.large,
                bottom = LelloStoreSpacing.xLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium),
        ) {
            items(state.updates, key = { it.packageName }) { update ->
                UpdateRow(
                    update = update,
                    onClick = { onAppClick(update.packageName) },
                    onUpdateClick = { onUpdateClick(update.packageName) },
                )
            }
        }
    }
}

@Composable
private fun UpdateRow(
    update: UpdateUiModel,
    onClick: () -> Unit,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channel = stringResource(
        when (update.releaseChannel) {
            ReleaseChannel.Stable -> R.string.release_channel_stable
            ReleaseChannel.Beta -> R.string.release_channel_beta
        },
    )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(LelloStoreSpacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = update.iconUrl,
                    contentDescription = stringResource(R.string.content_description_app_icon, update.appName),
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.width(LelloStoreSpacing.medium))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = update.appName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(LelloStoreSpacing.xSmall))
                    Text(
                        text = stringResource(
                            R.string.update_versions_and_size,
                            update.installedVersion,
                            update.availableVersion,
                            update.updateSize,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(LelloStoreSpacing.small))
                LelloStoreStatusBadge(
                    label = channel,
                    emphasized = update.releaseChannel == ReleaseChannel.Beta,
                )
            }
            Spacer(Modifier.height(LelloStoreSpacing.medium))
            Button(
                onClick = onUpdateClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = lelloStoreButtonColors(),
            ) {
                Text(stringResource(R.string.update))
            }
        }
    }
}
