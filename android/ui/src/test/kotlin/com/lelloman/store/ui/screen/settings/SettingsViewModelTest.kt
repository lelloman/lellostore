package com.lelloman.store.ui.screen.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeInteractor: FakeSettingsInteractor
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeInteractor = FakeSettingsInteractor()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel = SettingsViewModel(fakeInteractor)
    }

    @Test
    fun `initial state reflects interactor values`() = runTest {
        fakeInteractor.mutableThemeMode.value = ThemeModeOption.Dark
        fakeInteractor.mutableUpdateCheckInterval.value = UpdateCheckIntervalOption.Hours12
        fakeInteractor.mutableWifiOnlyDownloads.value = false
        fakeInteractor.mutableUserEmail.value = "test@example.com"
        fakeInteractor.mutableServerUrl.value = "https://api.example.com"
        fakeInteractor.setAppVersion("1.2.3")

        createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.themeMode).isEqualTo(ThemeModeOption.Dark)
        assertThat(state.updateCheckInterval).isEqualTo(UpdateCheckIntervalOption.Hours12)
        assertThat(state.wifiOnlyDownloads).isFalse()
        assertThat(state.userEmail).isEqualTo("test@example.com")
        assertThat(state.serverUrl).isEqualTo("https://api.example.com")
        assertThat(state.appVersion).isEqualTo("1.2.3")
    }

    @Test
    fun `onThemeModeChanged calls interactor`() = runTest {
        createViewModel()
        advanceUntilIdle()

        viewModel.onThemeModeChanged(ThemeModeOption.Light)
        advanceUntilIdle()

        assertThat(fakeInteractor.setThemeModeCalled).isTrue()
        assertThat(fakeInteractor.lastThemeMode).isEqualTo(ThemeModeOption.Light)
    }

    @Test
    fun `onUpdateCheckIntervalChanged calls interactor`() = runTest {
        createViewModel()
        advanceUntilIdle()

        viewModel.onUpdateCheckIntervalChanged(UpdateCheckIntervalOption.Hours6)
        advanceUntilIdle()

        assertThat(fakeInteractor.setUpdateCheckIntervalCalled).isTrue()
        assertThat(fakeInteractor.lastUpdateCheckInterval).isEqualTo(UpdateCheckIntervalOption.Hours6)
    }

    @Test
    fun `onWifiOnlyDownloadsChanged calls interactor`() = runTest {
        createViewModel()
        advanceUntilIdle()

        viewModel.onWifiOnlyDownloadsChanged(false)
        advanceUntilIdle()

        assertThat(fakeInteractor.setWifiOnlyDownloadsCalled).isTrue()
        assertThat(fakeInteractor.lastWifiOnlyDownloads).isFalse()
    }

    @Test
    fun `onLogoutClick calls interactor and emits NavigateToLogin`() = runTest {
        createViewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onLogoutClick()
            advanceUntilIdle()

            assertThat(fakeInteractor.logoutCalled).isTrue()
            val event = awaitItem()
            assertThat(event).isEqualTo(SettingsScreenEvent.NavigateToLogin)
        }
    }

    @Test
    fun `onServerUrlInputChanged updates state`() = runTest {
        createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlInputChanged("https://new.example.com")

        assertThat(viewModel.state.value.serverUrlInput).isEqualTo("https://new.example.com")
    }

    @Test
    fun `onServerUrlInputChanged clears previous error`() = runTest {
        fakeInteractor.setServerUrlResult = SettingsViewModel.SetServerUrlResult.InvalidUrl
        createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlInputChanged("invalid")
        viewModel.onServerUrlSave()
        advanceUntilIdle()

        assertThat(viewModel.state.value.serverUrlError).isNotNull()

        viewModel.onServerUrlInputChanged("https://new.example.com")

        assertThat(viewModel.state.value.serverUrlError).isNull()
    }

    @Test
    fun `onServerUrlSave with valid URL clears error`() = runTest {
        fakeInteractor.setServerUrlResult = SettingsViewModel.SetServerUrlResult.Success
        createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlInputChanged("https://valid.example.com")
        viewModel.onServerUrlSave()
        advanceUntilIdle()

        assertThat(viewModel.state.value.serverUrlError).isNull()
        assertThat(fakeInteractor.setServerUrlCalled).isTrue()
        assertThat(fakeInteractor.lastServerUrl).isEqualTo("https://valid.example.com")
    }

    @Test
    fun `onServerUrlSave with invalid URL shows error`() = runTest {
        fakeInteractor.setServerUrlResult = SettingsViewModel.SetServerUrlResult.InvalidUrl
        createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlInputChanged("invalid-url")
        viewModel.onServerUrlSave()
        advanceUntilIdle()

        assertThat(viewModel.state.value.serverUrlError).isEqualTo("Invalid URL")
    }

    @Test
    fun `state updates when interactor values change`() = runTest {
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.themeMode).isEqualTo(ThemeModeOption.System)

        fakeInteractor.mutableThemeMode.value = ThemeModeOption.Dark
        advanceUntilIdle()

        assertThat(viewModel.state.value.themeMode).isEqualTo(ThemeModeOption.Dark)
    }

    @Test
    fun `user email null shows as null in state`() = runTest {
        fakeInteractor.mutableUserEmail.value = null
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.userEmail).isNull()
    }

    @Test
    fun `serverUrlInput is initialized from serverUrl`() = runTest {
        fakeInteractor.mutableServerUrl.value = "https://initial.example.com"
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.serverUrlInput).isEqualTo("https://initial.example.com")
    }
}

class FakeSettingsInteractor : SettingsViewModel.Interactor {
    val mutableThemeMode = MutableStateFlow(ThemeModeOption.System)
    val mutableUpdateCheckInterval = MutableStateFlow(UpdateCheckIntervalOption.Hours24)
    val mutableWifiOnlyDownloads = MutableStateFlow(true)
    val mutableUserEmail = MutableStateFlow<String?>(null)
    val mutableServerUrl = MutableStateFlow("")
    private var _appVersion = "1.0.0"

    fun setAppVersion(version: String) {
        _appVersion = version
    }

    var setThemeModeCalled = false
    var lastThemeMode: ThemeModeOption? = null

    var setUpdateCheckIntervalCalled = false
    var lastUpdateCheckInterval: UpdateCheckIntervalOption? = null

    var setWifiOnlyDownloadsCalled = false
    var lastWifiOnlyDownloads: Boolean? = null

    var setServerUrlCalled = false
    var lastServerUrl: String? = null
    var setServerUrlResult: SettingsViewModel.SetServerUrlResult = SettingsViewModel.SetServerUrlResult.Success

    var logoutCalled = false

    override fun themeMode(): StateFlow<ThemeModeOption> = mutableThemeMode
    override fun updateCheckInterval(): StateFlow<UpdateCheckIntervalOption> = mutableUpdateCheckInterval
    override fun wifiOnlyDownloads(): StateFlow<Boolean> = mutableWifiOnlyDownloads
    override fun userEmail(): StateFlow<String?> = mutableUserEmail
    override fun serverUrl(): StateFlow<String> = mutableServerUrl
    override fun getAppVersion(): String = _appVersion

    override suspend fun setThemeMode(mode: ThemeModeOption) {
        setThemeModeCalled = true
        lastThemeMode = mode
        mutableThemeMode.value = mode
    }

    override suspend fun setUpdateCheckInterval(interval: UpdateCheckIntervalOption) {
        setUpdateCheckIntervalCalled = true
        lastUpdateCheckInterval = interval
        mutableUpdateCheckInterval.value = interval
    }

    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        setWifiOnlyDownloadsCalled = true
        lastWifiOnlyDownloads = enabled
        mutableWifiOnlyDownloads.value = enabled
    }

    override suspend fun setServerUrl(url: String): SettingsViewModel.SetServerUrlResult {
        setServerUrlCalled = true
        lastServerUrl = url
        if (setServerUrlResult == SettingsViewModel.SetServerUrlResult.Success) {
            mutableServerUrl.value = url
        }
        return setServerUrlResult
    }

    override suspend fun logout() {
        logoutCalled = true
    }
}
