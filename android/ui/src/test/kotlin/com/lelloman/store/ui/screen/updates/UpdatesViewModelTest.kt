package com.lelloman.store.ui.screen.updates

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.DownloadFailureKind
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.preferences.ReleaseChannel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
                releaseChannel = ReleaseChannel.Stable,
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
        coEvery { interactor.downloadAndInstall(any()) } returns DownloadResult.Success

        viewModel = UpdatesViewModel(interactor)
        advanceUntilIdle()

        viewModel.onUpdateClick("com.test.app")
        advanceUntilIdle()

        coVerify { interactor.downloadAndInstall("com.test.app") }
    }

    private fun update(packageName: String) = UpdateUiModel(
        packageName = packageName,
        appName = packageName,
        iconUrl = "",
        installedVersion = "1.0",
        availableVersion = "2.0",
        updateSize = "10 MB",
        releaseChannel = ReleaseChannel.Stable,
    )

    private fun createViewModel(vararg packages: String) {
        every { interactor.watchUpdates() } returns flowOf(packages.map(::update))
        coEvery { interactor.checkForUpdates() } returns Result.success(Unit)
        viewModel = UpdatesViewModel(interactor)
    }

    @Test
    fun `failure outcome is kept per package with reason and kind`() = runTest {
        createViewModel("com.a", "com.b")
        coEvery { interactor.downloadAndInstall("com.a") } returns
            DownloadResult.Failed("signatures differ", DownloadFailureKind.INCOMPATIBLE_SIGNATURE)
        coEvery { interactor.downloadAndInstall("com.b") } returns DownloadResult.Failed("disk full")
        advanceUntilIdle()

        viewModel.onUpdateClick("com.a")
        viewModel.onUpdateClick("com.b")
        advanceUntilIdle()

        assertThat(viewModel.state.value.outcomes).containsExactly(
            "com.a", UpdateOutcome.Failed("signatures differ", DownloadFailureKind.INCOMPATIBLE_SIGNATURE),
            "com.b", UpdateOutcome.Failed("disk full", DownloadFailureKind.GENERIC),
        )
    }

    @Test
    fun `permission required and awaiting confirmation outcomes are kept per package`() = runTest {
        createViewModel("com.a", "com.b")
        coEvery { interactor.downloadAndInstall("com.a") } returns DownloadResult.PermissionRequired
        coEvery { interactor.downloadAndInstall("com.b") } returns DownloadResult.AwaitingConfirmation
        advanceUntilIdle()

        viewModel.onUpdateClick("com.a")
        viewModel.onUpdateClick("com.b")
        advanceUntilIdle()

        assertThat(viewModel.state.value.outcomes).containsExactly(
            "com.a", UpdateOutcome.PermissionRequired,
            "com.b", UpdateOutcome.AwaitingConfirmation,
        )
    }

    @Test
    fun `retry clears the previous outcome of that package only`() = runTest {
        createViewModel("com.a", "com.b")
        coEvery { interactor.downloadAndInstall(any()) } returns DownloadResult.Failed("boom")
        advanceUntilIdle()
        viewModel.onUpdateClick("com.a")
        viewModel.onUpdateClick("com.b")
        advanceUntilIdle()

        coEvery { interactor.downloadAndInstall("com.a") } returns DownloadResult.Success
        viewModel.onUpdateClick("com.a")
        // Cleared as soon as the new attempt starts, before it completes.
        assertThat(viewModel.state.value.outcomes).doesNotContainKey("com.a")
        advanceUntilIdle()

        assertThat(viewModel.state.value.outcomes).containsExactly("com.b", UpdateOutcome.Failed("boom"))
    }

    @Test
    fun `grant permission delegates to interactor`() = runTest {
        createViewModel("com.a")
        every { interactor.openInstallPermissionSettings() } returns Unit
        advanceUntilIdle()

        viewModel.onGrantPermissionClick()

        verify { interactor.openInstallPermissionSettings() }
    }
}
