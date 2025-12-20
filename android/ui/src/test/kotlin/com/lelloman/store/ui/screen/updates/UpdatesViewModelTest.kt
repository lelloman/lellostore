package com.lelloman.store.ui.screen.updates

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var interactor: UpdatesViewModel.Interactor
    private lateinit var viewModel: UpdatesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        interactor = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() = runTest {
        every { interactor.watchUpdates() } returns flowOf(emptyList())
        coEvery { interactor.checkForUpdates() } returns Result.success(Unit)

        viewModel = UpdatesViewModel(interactor)

        assertThat(viewModel.state.value.isLoading).isTrue()
    }

    @Test
    fun `updates are collected from interactor`() = runTest {
        val updates = listOf(
            UpdateUiModel(
                packageName = "com.test.app",
                appName = "Test App",
                iconUrl = "https://example.com/icon.png",
                installedVersion = "1.0",
                availableVersion = "2.0",
                updateSize = "10 MB",
            )
        )

        every { interactor.watchUpdates() } returns flowOf(updates)
        coEvery { interactor.checkForUpdates() } returns Result.success(Unit)

        viewModel = UpdatesViewModel(interactor)
        advanceUntilIdle()

        assertThat(viewModel.state.value.updates).isEqualTo(updates)
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `refreshUpdates clears error on success`() = runTest {
        every { interactor.watchUpdates() } returns flowOf(emptyList())
        coEvery { interactor.checkForUpdates() } returns Result.success(Unit)

        viewModel = UpdatesViewModel(interactor)
        advanceUntilIdle()

        viewModel.refreshUpdates()
        advanceUntilIdle()

        assertThat(viewModel.state.value.isRefreshing).isFalse()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `refreshUpdates sets error on failure`() = runTest {
        every { interactor.watchUpdates() } returns flowOf(emptyList())
        coEvery { interactor.checkForUpdates() } returns Result.failure(Exception("Network error"))

        viewModel = UpdatesViewModel(interactor)
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Network error")
        assertThat(viewModel.state.value.isRefreshing).isFalse()
    }

    @Test
    fun `onUpdateClick calls downloadAndInstall`() = runTest {
        every { interactor.watchUpdates() } returns flowOf(emptyList())
        coEvery { interactor.checkForUpdates() } returns Result.success(Unit)
        coEvery { interactor.downloadAndInstall(any()) } returns Unit

        viewModel = UpdatesViewModel(interactor)
        advanceUntilIdle()

        viewModel.onUpdateClick("com.test.app")
        advanceUntilIdle()

        coVerify { interactor.downloadAndInstall("com.test.app") }
    }
}
