package com.lelloman.store.ui.screen.catalog

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.ui.model.AppModel
import com.lelloman.store.ui.model.InstalledAppModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeInteractor: FakeCatalogInteractor
    private lateinit var viewModel: CatalogViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeInteractor = FakeCatalogInteractor()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel = CatalogViewModel(fakeInteractor)
    }

    @Test
    fun `initial state triggers refresh`() = runTest {
        createViewModel()
        advanceUntilIdle()

        assertThat(fakeInteractor.refreshAppsCalled).isTrue()
    }

    @Test
    fun `apps are transformed to UI models`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "Test App 1"),
            createApp("com.test.app2", "Test App 2"),
        )
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps).hasSize(2)
        assertThat(viewModel.state.value.apps[0].name).isEqualTo("Test App 1")
        assertThat(viewModel.state.value.apps[1].name).isEqualTo("Test App 2")
    }

    @Test
    fun `installed apps are marked correctly`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "Test App 1"),
        )
        fakeInteractor.mutableInstalledApps.value = listOf(
            InstalledAppModel("com.test.app1", 1, "1.0.0"),
        )
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps[0].isInstalled).isTrue()
        assertThat(viewModel.state.value.apps[0].hasUpdate).isFalse()
    }

    @Test
    fun `apps with updates are marked correctly`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "Test App 1", versionCode = 2),
        )
        fakeInteractor.mutableInstalledApps.value = listOf(
            InstalledAppModel("com.test.app1", 1, "1.0.0"),
        )
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps[0].isInstalled).isTrue()
        assertThat(viewModel.state.value.apps[0].hasUpdate).isTrue()
    }

    @Test
    fun `search query filters apps by name`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "Calculator"),
            createApp("com.test.app2", "Calendar"),
            createApp("com.test.app3", "Notes"),
        )
        createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("Cal")
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps).hasSize(2)
        assertThat(viewModel.state.value.apps.map { it.name }).containsExactly("Calculator", "Calendar")
    }

    @Test
    fun `filter shows only installed apps`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "App 1"),
            createApp("com.test.app2", "App 2"),
        )
        fakeInteractor.mutableInstalledApps.value = listOf(
            InstalledAppModel("com.test.app1", 1, "1.0.0"),
        )
        createViewModel()
        advanceUntilIdle()

        viewModel.onFilterChanged(CatalogFilter.Installed)
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps).hasSize(1)
        assertThat(viewModel.state.value.apps[0].packageName).isEqualTo("com.test.app1")
    }

    @Test
    fun `filter shows only apps with updates`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "App 1", versionCode = 2),
            createApp("com.test.app2", "App 2", versionCode = 1),
        )
        fakeInteractor.mutableInstalledApps.value = listOf(
            InstalledAppModel("com.test.app1", 1, "1.0.0"),
            InstalledAppModel("com.test.app2", 1, "1.0.0"),
        )
        createViewModel()
        advanceUntilIdle()

        viewModel.onFilterChanged(CatalogFilter.Updates)
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps).hasSize(1)
        assertThat(viewModel.state.value.apps[0].packageName).isEqualTo("com.test.app1")
    }

    @Test
    fun `onAppClicked emits navigation event`() = runTest {
        fakeInteractor.mutableApps.value = listOf(createApp("com.test.app1", "App 1"))
        createViewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onAppClicked(viewModel.state.value.apps[0])
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isInstanceOf(CatalogScreenEvent.NavigateToAppDetail::class.java)
            assertThat((event as CatalogScreenEvent.NavigateToAppDetail).packageName)
                .isEqualTo("com.test.app1")
        }
    }

    @Test
    fun `refresh failure shows error`() = runTest {
        fakeInteractor.refreshAppsResult = Result.failure(Exception("Network error"))
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Network error")
        assertThat(viewModel.state.value.isRefreshing).isFalse()
    }

    @Test
    fun `onClearSearch clears search query`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "App 1"),
        )
        createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("test")
        advanceUntilIdle()
        assertThat(viewModel.state.value.searchQuery).isEqualTo("test")

        viewModel.onClearSearch()
        advanceUntilIdle()
        assertThat(viewModel.state.value.searchQuery).isEmpty()
    }

    @Test
    fun `sort by name ascending orders apps alphabetically`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "Zebra"),
            createApp("com.test.app2", "Apple"),
            createApp("com.test.app3", "Mango"),
        )
        createViewModel()
        advanceUntilIdle()

        viewModel.onSortOptionChanged(SortOption.NameAsc)
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps.map { it.name })
            .containsExactly("Apple", "Mango", "Zebra").inOrder()
    }

    @Test
    fun `sort by name descending orders apps reverse alphabetically`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "Zebra"),
            createApp("com.test.app2", "Apple"),
            createApp("com.test.app3", "Mango"),
        )
        createViewModel()
        advanceUntilIdle()

        viewModel.onSortOptionChanged(SortOption.NameDesc)
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps.map { it.name })
            .containsExactly("Zebra", "Mango", "Apple").inOrder()
    }

    @Test
    fun `app counts are calculated correctly`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "App 1", versionCode = 2),
            createApp("com.test.app2", "App 2", versionCode = 1),
            createApp("com.test.app3", "App 3", versionCode = 1),
        )
        fakeInteractor.mutableInstalledApps.value = listOf(
            InstalledAppModel("com.test.app1", 1, "1.0.0"), // has update
            InstalledAppModel("com.test.app2", 1, "1.0.0"), // installed, no update
        )
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.allCount).isEqualTo(3)
        assertThat(viewModel.state.value.installedCount).isEqualTo(2)
        assertThat(viewModel.state.value.updatesCount).isEqualTo(1)
    }

    @Test
    fun `counts remain correct when filter is applied`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "App 1", versionCode = 2),
            createApp("com.test.app2", "App 2", versionCode = 1),
        )
        fakeInteractor.mutableInstalledApps.value = listOf(
            InstalledAppModel("com.test.app1", 1, "1.0.0"),
        )
        createViewModel()
        advanceUntilIdle()

        viewModel.onFilterChanged(CatalogFilter.Installed)
        advanceUntilIdle()

        // Counts should still reflect total, not filtered
        assertThat(viewModel.state.value.allCount).isEqualTo(2)
        assertThat(viewModel.state.value.installedCount).isEqualTo(1)
        assertThat(viewModel.state.value.updatesCount).isEqualTo(1)
        // But filtered list should only have installed apps
        assertThat(viewModel.state.value.apps).hasSize(1)
    }

    @Test
    fun `description is included in UI model`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            AppModel(
                packageName = "com.test.app1",
                name = "App 1",
                description = "A test description",
                iconUrl = "https://example.com/icon.png",
                latestVersionCode = 1,
                latestVersionName = "1.0.0",
            ),
        )
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps[0].description).isEqualTo("A test description")
    }

    @Test
    fun `sort is case insensitive`() = runTest {
        fakeInteractor.mutableApps.value = listOf(
            createApp("com.test.app1", "banana"),
            createApp("com.test.app2", "Apple"),
            createApp("com.test.app3", "CHERRY"),
        )
        createViewModel()
        advanceUntilIdle()

        viewModel.onSortOptionChanged(SortOption.NameAsc)
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps.map { it.name })
            .containsExactly("Apple", "banana", "CHERRY").inOrder()
    }

    private fun createApp(
        packageName: String,
        name: String,
        versionCode: Int = 1,
    ): AppModel = AppModel(
        packageName = packageName,
        name = name,
        description = null,
        iconUrl = "https://example.com/icon.png",
        latestVersionCode = versionCode,
        latestVersionName = "$versionCode.0.0",
    )
}

class FakeCatalogInteractor : CatalogViewModel.Interactor {
    val mutableApps = MutableStateFlow<List<AppModel>>(emptyList())
    val mutableInstalledApps = MutableStateFlow<List<InstalledAppModel>>(emptyList())
    var refreshAppsResult: Result<Unit> = Result.success(Unit)
    var refreshAppsCalled = false

    override fun watchApps(): Flow<List<AppModel>> = mutableApps

    override fun watchInstalledApps(): Flow<List<InstalledAppModel>> = mutableInstalledApps

    override suspend fun refreshApps(): Result<Unit> {
        refreshAppsCalled = true
        return refreshAppsResult
    }

    override suspend fun refreshInstalledApps() {
        // No-op
    }
}
