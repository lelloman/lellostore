package com.lelloman.store.ui.screen.catalog

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreAppRow
import com.lelloman.store.ui.components.LelloStoreStateContent
import com.lelloman.store.ui.components.lelloStoreFilterChipColors
import com.lelloman.store.ui.theme.LelloGreen
import com.lelloman.store.ui.theme.LelloStoreSpacing

@Composable
fun SortOption.getDisplayName(): String = when (this) {
    SortOption.NameAsc -> stringResource(R.string.sort_name_asc)
    SortOption.NameDesc -> stringResource(R.string.sort_name_desc)
}

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
                CatalogControls(
                    state = state,
                    onSearchQueryChanged = onSearchQueryChanged,
                    onClearSearch = onClearSearch,
                    onFilterChanged = {
                        focusManager.clearFocus()
                        onFilterChanged(it)
                    },
                    onSortOptionChanged = onSortOptionChanged,
                )

                when {
                    state.isLoading && state.apps.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = LelloGreen)
                        }
                    }
                    state.error != null && state.apps.isEmpty() -> {
                        LelloStoreStateContent(
                            icon = Icons.Default.Refresh,
                            title = stringResource(R.string.catalog_load_error_title),
                            message = state.error,
                            actionLabel = stringResource(R.string.retry),
                            onAction = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    state.apps.isEmpty() -> {
                        val title = when {
                            state.searchQuery.isNotBlank() -> stringResource(
                                R.string.no_apps_found_for_query,
                                state.searchQuery,
                            )
                            state.filter == CatalogFilter.Installed -> stringResource(R.string.no_installed_apps)
                            state.filter == CatalogFilter.Updates -> stringResource(R.string.no_updates_available)
                            else -> stringResource(R.string.no_apps_available)
                        }
                        LelloStoreStateContent(
                            icon = Icons.Default.Search,
                            title = title,
                            message = stringResource(R.string.catalog_empty_hint),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = LelloStoreSpacing.large,
                                top = LelloStoreSpacing.small,
                                end = LelloStoreSpacing.large,
                                bottom = LelloStoreSpacing.xLarge,
                            ),
                            verticalArrangement = Arrangement.spacedBy(LelloStoreSpacing.medium),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.apps, key = { it.packageName }) { app ->
                                CatalogAppRow(
                                    app = app,
                                    onClick = { onAppClicked(app) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.error != null && state.apps.isNotEmpty()) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(LelloStoreSpacing.large),
                action = {
                    TextButton(onClick = onErrorDismissed) {
                        Text(stringResource(R.string.dismiss))
                    }
                },
            ) {
                Text(state.error)
            }
        }
    }
}

@Composable
private fun CatalogControls(
    state: CatalogScreenState,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onFilterChanged: (CatalogFilter) -> Unit,
    onSortOptionChanged: (SortOption) -> Unit,
) {
    Column(
        modifier = Modifier.padding(
            start = LelloStoreSpacing.large,
            end = LelloStoreSpacing.large,
            bottom = LelloStoreSpacing.small,
        ),
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = { Text(stringResource(R.string.search_apps)) },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = onClearSearch) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.content_description_clear_search),
                        )
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LelloStoreSpacing.small))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.small),
        ) {
            CatalogFilter.entries.forEach { filter ->
                val count = when (filter) {
                    CatalogFilter.All -> state.allCount
                    CatalogFilter.Installed -> state.installedCount
                    CatalogFilter.Updates -> state.updatesCount
                }
                val label = when (filter) {
                    CatalogFilter.All -> stringResource(R.string.filter_all, count)
                    CatalogFilter.Installed -> stringResource(R.string.filter_installed, count)
                    CatalogFilter.Updates -> stringResource(R.string.filter_updates, count)
                }
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilterChanged(filter) },
                    label = { Text(label) },
                    colors = lelloStoreFilterChipColors(),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.catalog_results,
                    state.apps.size,
                    state.apps.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            SortDropdown(
                selectedOption = state.sortOption,
                onOptionSelected = onSortOptionChanged,
            )
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
                text = selectedOption.getDisplayName(),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.getDisplayName()) },
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
private fun CatalogAppRow(
    app: AppUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusLabel = when {
        app.hasUpdate -> stringResource(R.string.status_update)
        app.isInstalled -> stringResource(R.string.status_installed)
        else -> null
    }
    LelloStoreAppRow(
        name = app.name,
        iconUrl = app.iconUrl,
        iconContentDescription = stringResource(R.string.content_description_app_icon, app.name),
        supportingText = stringResource(R.string.version_value, app.versionName),
        description = app.description,
        statusLabel = statusLabel,
        statusEmphasized = app.hasUpdate,
        onClick = onClick,
        modifier = modifier,
    )
}
