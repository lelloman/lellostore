package com.lelloman.store.ui.screen.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage

@Composable
fun CatalogScreen(
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CatalogScreenEvent.NavigateToAppDetail -> onAppClick(event.packageName)
            }
        }
    }

    CatalogScreenContent(
        state = state,
        onRefresh = viewModel::onRefresh,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onClearSearch = viewModel::onClearSearch,
        onFilterChanged = viewModel::onFilterChanged,
        onSortOptionChanged = viewModel::onSortOptionChanged,
        onAppClicked = viewModel::onAppClicked,
        onErrorDismissed = viewModel::onErrorDismissed,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogScreenContent(
    state: CatalogScreenState,
    onRefresh: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onFilterChanged: (CatalogFilter) -> Unit,
    onSortOptionChanged: (SortOption) -> Unit,
    onAppClicked: (AppUiModel) -> Unit,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Search bar with clear button
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChanged,
                    placeholder = { Text("Search apps") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                        )
                    },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = onClearSearch) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // Filter chips with counts and sort dropdown
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = state.filter == CatalogFilter.All,
                            onClick = {
                                focusManager.clearFocus()
                                onFilterChanged(CatalogFilter.All)
                            },
                            label = { Text("All (${state.allCount})") },
                        )
                        FilterChip(
                            selected = state.filter == CatalogFilter.Installed,
                            onClick = {
                                focusManager.clearFocus()
                                onFilterChanged(CatalogFilter.Installed)
                            },
                            label = { Text("Installed (${state.installedCount})") },
                        )
                        FilterChip(
                            selected = state.filter == CatalogFilter.Updates,
                            onClick = {
                                focusManager.clearFocus()
                                onFilterChanged(CatalogFilter.Updates)
                            },
                            label = { Text("Updates (${state.updatesCount})") },
                        )
                    }

                    SortDropdown(
                        selectedOption = state.sortOption,
                        onOptionSelected = onSortOptionChanged,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // App list
                if (state.apps.isEmpty() && !state.isRefreshing && !state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                state.searchQuery.isNotBlank() -> "No apps found for \"${state.searchQuery}\""
                                state.filter == CatalogFilter.Installed -> "No installed apps"
                                state.filter == CatalogFilter.Updates -> "No updates available"
                                else -> "No apps available"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.apps, key = { it.packageName }) { app ->
                            AppListItem(
                                app = app,
                                onClick = { onAppClicked(app) },
                            )
                        }
                    }
                }
            }

            // Loading indicator for initial load
            if (state.isLoading && state.apps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        // Error snackbar
        state.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = onErrorDismissed) {
                        Text("Dismiss")
                    }
                },
            ) {
                Text(error)
            }
        }
    }
}

@Composable
private fun SortDropdown(
    selectedOption: SortOption,
    onOptionSelected: (SortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = selectedOption.displayName,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AppListItem(
    app: AppUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = app.iconUrl,
                contentDescription = "${app.name} icon",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    if (app.hasUpdate) {
                        Text(
                            text = "Update",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    } else if (app.isInstalled) {
                        Text(
                            text = "Installed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                Text(
                    text = "v${app.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                app.description?.let { description ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
