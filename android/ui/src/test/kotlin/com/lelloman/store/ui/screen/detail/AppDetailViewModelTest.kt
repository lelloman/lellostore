package com.lelloman.store.ui.screen.detail

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.ui.model.AppDetailModel
import com.lelloman.store.ui.model.AppVersionModel
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeInteractor: FakeAppDetailInteractor
    private lateinit var viewModel: AppDetailViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeInteractor = FakeAppDetailInteractor()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(packageName: String = "com.test.app") {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle().apply {
            set("packageName", packageName)
        }
        viewModel = AppDetailViewModel(savedStateHandle, fakeInteractor)
    }

    @Test
    fun `initial state triggers refresh`() = runTest {
        createViewModel()
        advanceUntilIdle()

        assertThat(fakeInteractor.refreshAppCalled).isTrue()
        assertThat(fakeInteractor.refreshAppPackageName).isEqualTo("com.test.app")
    }

    @Test
    fun `app detail is transformed to UI model`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        createViewModel()
        advanceUntilIdle()

        val app = viewModel.state.value.app
        assertThat(app).isNotNull()
        assertThat(app?.name).isEqualTo("Test App")
        assertThat(app?.packageName).isEqualTo("com.test.app")
        assertThat(app?.description).isEqualTo("Test description")
        assertThat(app?.versions).hasSize(2)
    }

    @Test
    fun `latest version is correctly identified`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        createViewModel()
        advanceUntilIdle()

        val app = viewModel.state.value.app
        assertThat(app?.latestVersion?.versionCode).isEqualTo(2)
        assertThat(app?.latestVersion?.versionName).isEqualTo("2.0.0")
    }

    @Test
    fun `version size is formatted correctly`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail(
            versions = listOf(
                AppVersionModel(1, "1.0.0", 500, 1000L), // bytes
                AppVersionModel(2, "2.0.0", 2048, 2000L), // KB
                AppVersionModel(3, "3.0.0", 5 * 1024 * 1024, 3000L), // MB
            )
        )
        createViewModel()
        advanceUntilIdle()

        val versions = viewModel.state.value.app?.versions
        assertThat(versions?.find { it.versionCode == 1 }?.size).isEqualTo("500 B")
        assertThat(versions?.find { it.versionCode == 2 }?.size).isEqualTo("2 KB")
        assertThat(versions?.find { it.versionCode == 3 }?.size).isEqualTo("5.0 MB")
    }

    @Test
    fun `not installed app shows canInstall true`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        fakeInteractor.mutableInstalledVersion.value = null
        createViewModel()
        advanceUntilIdle()

        val app = viewModel.state.value.app
        assertThat(app?.canInstall).isTrue()
        assertThat(app?.canUpdate).isFalse()
        assertThat(app?.canOpen).isFalse()
    }

    @Test
    fun `installed app with same version shows canOpen only`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        fakeInteractor.mutableInstalledVersion.value = InstalledAppModel("com.test.app", 2, "2.0.0")
        createViewModel()
        advanceUntilIdle()

        val app = viewModel.state.value.app
        assertThat(app?.canInstall).isFalse()
        assertThat(app?.canUpdate).isFalse()
        assertThat(app?.canOpen).isTrue()
        assertThat(app?.installedVersion?.versionCode).isEqualTo(2)
    }

    @Test
    fun `installed app with older version shows canUpdate`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        fakeInteractor.mutableInstalledVersion.value = InstalledAppModel("com.test.app", 1, "1.0.0")
        createViewModel()
        advanceUntilIdle()

        val app = viewModel.state.value.app
        assertThat(app?.canInstall).isFalse()
        assertThat(app?.canUpdate).isTrue()
        assertThat(app?.canOpen).isTrue()
        assertThat(app?.installedVersion?.versionCode).isEqualTo(1)
    }

    @Test
    fun `installed version not in versions list still shows correctly`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        // Version 3 is not in the app's versions list
        fakeInteractor.mutableInstalledVersion.value = InstalledAppModel("com.test.app", 3, "3.0.0")
        createViewModel()
        advanceUntilIdle()

        val app = viewModel.state.value.app
        assertThat(app?.installedVersion?.versionCode).isEqualTo(3)
        assertThat(app?.installedVersion?.versionName).isEqualTo("3.0.0")
        // Size and uploadedAt should be empty since version is not in list
        assertThat(app?.installedVersion?.size).isEmpty()
    }

    @Test
    fun `onInstallClick calls downloadAndInstall`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        createViewModel()
        advanceUntilIdle()

        viewModel.onInstallClick()
        advanceUntilIdle()

        assertThat(fakeInteractor.downloadAndInstallCalled).isTrue()
        assertThat(fakeInteractor.downloadAndInstallPackageName).isEqualTo("com.test.app")
        assertThat(fakeInteractor.downloadAndInstallVersionCode).isEqualTo(2)
    }

    @Test
    fun `onUpdateClick calls downloadAndInstall`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        fakeInteractor.mutableInstalledVersion.value = InstalledAppModel("com.test.app", 1, "1.0.0")
        createViewModel()
        advanceUntilIdle()

        viewModel.onUpdateClick()
        advanceUntilIdle()

        assertThat(fakeInteractor.downloadAndInstallCalled).isTrue()
        assertThat(fakeInteractor.downloadAndInstallVersionCode).isEqualTo(2)
    }

    @Test
    fun `onOpenClick emits OpenApp event`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        fakeInteractor.mutableInstalledVersion.value = InstalledAppModel("com.test.app", 2, "2.0.0")
        createViewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onOpenClick()
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isInstanceOf(AppDetailScreenEvent.OpenApp::class.java)
            assertThat((event as AppDetailScreenEvent.OpenApp).packageName).isEqualTo("com.test.app")
        }
    }

    @Test
    fun `refresh failure shows error`() = runTest {
        fakeInteractor.refreshAppResult = Result.failure(Exception("Network error"))
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Network error")
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `onRetry triggers refresh`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        createViewModel()
        advanceUntilIdle()
        fakeInteractor.refreshAppCalled = false

        viewModel.onRetry()
        advanceUntilIdle()

        assertThat(fakeInteractor.refreshAppCalled).isTrue()
    }

    @Test
    fun `loading state is true initially`() = runTest {
        createViewModel()

        assertThat(viewModel.state.value.isLoading).isTrue()
    }

    @Test
    fun `loading state is false after successful load`() = runTest {
        fakeInteractor.mutableApp.value = createAppDetail()
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `onInstallClick does nothing when app is null`() = runTest {
        // Don't set any app data - app will be null
        createViewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onInstallClick()
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `refresh failure with null message shows default error`() = runTest {
        fakeInteractor.refreshAppResult = Result.failure(Exception(null as String?))
        createViewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Failed to load app details")
    }

    private fun createAppDetail(
        packageName: String = "com.test.app",
        name: String = "Test App",
        versions: List<AppVersionModel> = listOf(
            AppVersionModel(2, "2.0.0", 2_000_000, 1700000000000L),
            AppVersionModel(1, "1.0.0", 1_500_000, 1690000000000L),
        ),
    ) = AppDetailModel(
        packageName = packageName,
        name = name,
        description = "Test description",
        iconUrl = "https://example.com/icon.png",
        versions = versions,
    )
}

class FakeAppDetailInteractor : AppDetailViewModel.Interactor {
    val mutableApp = MutableStateFlow<AppDetailModel?>(null)
    val mutableInstalledVersion = MutableStateFlow<InstalledAppModel?>(null)
    val mutableDownloadProgress = MutableStateFlow<DownloadProgress?>(null)
    var refreshAppResult: Result<AppDetailModel> = Result.success(
        AppDetailModel("com.test.app", "Test", null, "", emptyList())
    )
    var refreshAppCalled = false
    var refreshAppPackageName: String? = null
    var downloadAndInstallCalled = false
    var downloadAndInstallPackageName: String? = null
    var downloadAndInstallVersionCode: Int? = null
    var cancelDownloadCalled = false
    var cancelDownloadPackageName: String? = null

    override fun watchApp(packageName: String): Flow<AppDetailModel?> = mutableApp

    override fun watchInstalledVersion(packageName: String): Flow<InstalledAppModel?> = mutableInstalledVersion

    override fun watchDownloadProgress(packageName: String): Flow<DownloadProgress?> = mutableDownloadProgress

    override suspend fun refreshApp(packageName: String): Result<AppDetailModel> {
        refreshAppCalled = true
        refreshAppPackageName = packageName
        return refreshAppResult
    }

    override suspend fun downloadAndInstall(packageName: String, versionCode: Int) {
        downloadAndInstallCalled = true
        downloadAndInstallPackageName = packageName
        downloadAndInstallVersionCode = versionCode
    }

    override fun cancelDownload(packageName: String) {
        cancelDownloadCalled = true
        cancelDownloadPackageName = packageName
    }
}
