package com.lelloman.store.e2e

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.lelloman.store.di.AppModule
import com.lelloman.store.di.AppBindingsModule
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.OidcConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.download.DownloadManagerImpl
import com.lelloman.store.interactor.AppDetailInteractorImpl
import com.lelloman.store.interactor.CatalogInteractorImpl
import com.lelloman.store.interactor.LoginInteractorImpl
import com.lelloman.store.interactor.SettingsInteractorImpl
import com.lelloman.store.interactor.UpdatesInteractorImpl
import com.lelloman.store.localdata.apps.AppsRepositoryImpl
import com.lelloman.store.localdata.apps.InstalledAppsRepositoryImpl
import com.lelloman.store.localdata.auth.AuthStoreImpl
import com.lelloman.store.localdata.config.ConfigStoreImpl
import com.lelloman.store.logger.Logger
import com.lelloman.store.localdata.db.LellostoreDatabase
import com.lelloman.store.localdata.db.dao.AppVersionsDao
import com.lelloman.store.localdata.db.dao.AppsDao
import com.lelloman.store.localdata.db.dao.InstalledAppsDao
import com.lelloman.store.localdata.di.DefaultServerUrl
import com.lelloman.store.localdata.di.LocalDataModule
import com.lelloman.store.localdata.di.OidcConfigQualifier
import com.lelloman.store.localdata.prefs.UserPreferencesStoreImpl
import com.lelloman.store.remoteapi.di.RemoteApiModule
import com.lelloman.store.ui.screen.catalog.CatalogViewModel
import com.lelloman.store.ui.screen.detail.AppDetailViewModel
import com.lelloman.store.ui.screen.login.AuthIntentProvider
import com.lelloman.store.ui.screen.login.LoginViewModel
import com.lelloman.store.ui.screen.settings.SettingsViewModel
import com.lelloman.store.ui.screen.updates.UpdatesViewModel
import com.lelloman.store.updates.UpdateCheckerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the test application scope
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TestApplicationScope

/**
 * Module that replaces AppModule for E2E tests.
 * Provides fake auth and test configuration.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestAppModule {

    @Provides
    @DefaultServerUrl
    fun provideDefaultServerUrl(): String = "http://localhost:8080"

    @Provides
    @Singleton
    @OidcConfigQualifier
    fun provideOidcConfig(): OidcConfig = OidcConfig(
        issuerUrl = "https://auth.example.com",
        clientId = "test-client-id",
        redirectUri = "com.lelloman.store:/oauth2redirect",
    )

    @Provides
    @Singleton
    fun provideAuthIntentProvider(): AuthIntentProvider = object : AuthIntentProvider {
        override fun createAuthIntent(): Intent = Intent()
    }

    @Provides
    @Singleton
    @TestApplicationScope
    fun provideTestApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @Provides
    @Singleton
    fun provideAuthStoreImpl(
        @ApplicationContext context: Context,
        @OidcConfigQualifier oidcConfig: OidcConfig,
        @ApplicationScope scope: CoroutineScope,
        logger: Logger,
    ): AuthStoreImpl = AuthStoreImpl(context, oidcConfig, scope, logger)
}

/**
 * Module that replaces AppBindingsModule for E2E tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppBindingsModule::class]
)
abstract class TestAppBindingsModule {

    @Binds
    abstract fun bindLoginInteractor(impl: LoginInteractorImpl): LoginViewModel.Interactor

    @Binds
    abstract fun bindCatalogInteractor(impl: CatalogInteractorImpl): CatalogViewModel.Interactor

    @Binds
    abstract fun bindAppDetailInteractor(impl: AppDetailInteractorImpl): AppDetailViewModel.Interactor

    @Binds
    abstract fun bindUpdatesInteractor(impl: UpdatesInteractorImpl): UpdatesViewModel.Interactor

    @Binds
    abstract fun bindSettingsInteractor(impl: SettingsInteractorImpl): SettingsViewModel.Interactor

    @Binds
    @Singleton
    abstract fun bindDownloadManager(impl: DownloadManagerImpl): DownloadManager

    @Binds
    @Singleton
    abstract fun bindUpdateChecker(impl: UpdateCheckerImpl): UpdateChecker
}

/**
 * Module that replaces LocalDataModule for E2E tests.
 * Provides in-memory database and fake auth.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [LocalDataModule::class]
)
object TestLocalDataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LellostoreDatabase {
        return Room.inMemoryDatabaseBuilder(
            context,
            LellostoreDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @Provides
    fun provideAppsDao(db: LellostoreDatabase): AppsDao = db.appsDao()

    @Provides
    fun provideAppVersionsDao(db: LellostoreDatabase): AppVersionsDao = db.appVersionsDao()

    @Provides
    fun provideInstalledAppsDao(db: LellostoreDatabase): InstalledAppsDao = db.installedAppsDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("test_settings_${System.currentTimeMillis()}")
        }
    }

    @Provides
    @Singleton
    fun provideConfigStore(
        dataStore: DataStore<Preferences>,
        @DefaultServerUrl defaultServerUrl: String,
        @TestApplicationScope scope: CoroutineScope,
    ): ConfigStore = ConfigStoreImpl(dataStore, defaultServerUrl, scope)

    @Provides
    @Singleton
    fun provideUserPreferencesStore(
        dataStore: DataStore<Preferences>,
        @TestApplicationScope scope: CoroutineScope,
    ): UserPreferencesStore = UserPreferencesStoreImpl(dataStore, scope)

    @Provides
    @Singleton
    fun provideAuthStore(): AuthStore = FakeAuthStore()

    @Provides
    @Singleton
    fun provideAppsRepository(
        appsDao: AppsDao,
        appVersionsDao: AppVersionsDao,
        remoteApiClient: RemoteApiClient,
    ): AppsRepository = AppsRepositoryImpl(appsDao, appVersionsDao, remoteApiClient)

    @Provides
    @Singleton
    fun provideInstalledAppsRepository(
        @ApplicationContext context: Context,
        installedAppsDao: InstalledAppsDao,
        appsDao: AppsDao,
    ): InstalledAppsRepository = InstalledAppsRepositoryImpl(context, installedAppsDao, appsDao)
}

/**
 * Module that replaces RemoteApiModule for E2E tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RemoteApiModule::class]
)
object TestRemoteApiModuleImpl {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    @Provides
    @Singleton
    fun provideRemoteApiClient(
        httpClient: HttpClient,
    ): RemoteApiClient = TestRemoteApiClient(httpClient)
}

/**
 * Test implementation that reads the base URL from TestServerUrlHolder
 * on every request, allowing it to be changed between tests.
 */
class TestRemoteApiClient(
    private val httpClient: HttpClient,
) : RemoteApiClient {

    private val baseUrl: String
        get() = TestServerUrlHolder.serverUrl

    override suspend fun getApps(): Result<List<App>> = runCatching {
        val response = httpClient.get("$baseUrl/api/apps")
        if (!response.status.isSuccess()) {
            throw Exception("Failed to get apps: ${response.status}")
        }
        val appsResponse: AppsResponseDto = response.body()
        appsResponse.apps.map { it.toDomain() }
    }

    override suspend fun getApp(packageName: String): Result<AppDetail> = runCatching {
        val response = httpClient.get("$baseUrl/api/apps/$packageName")
        if (!response.status.isSuccess()) {
            throw Exception("Failed to get app $packageName: ${response.status}")
        }
        val appDetail: AppDetailDto = response.body()
        appDetail.toDomain()
    }

    override suspend fun downloadApk(packageName: String, versionCode: Int): Result<InputStream> =
        runCatching {
            val response = httpClient.get("$baseUrl/api/apps/$packageName/versions/$versionCode/apk")
            if (!response.status.isSuccess()) {
                throw Exception("Failed to download APK: ${response.status}")
            }
            response.bodyAsChannel().toInputStream()
        }
}

// DTOs for test deserialization
@Serializable
data class AppsResponseDto(
    val apps: List<AppDto>,
)

@Serializable
data class AppDto(
    @SerialName("package_name") val packageName: String,
    val name: String,
    val description: String? = null,
    @SerialName("icon_url") val iconUrl: String,
    @SerialName("latest_version") val latestVersion: AppVersionDto,
)

@Serializable
data class AppVersionDto(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    val size: Long,
    val sha256: String,
    @SerialName("min_sdk") val minSdk: Int,
    @SerialName("uploaded_at") val uploadedAt: String,
)

@Serializable
data class AppDetailDto(
    @SerialName("package_name") val packageName: String,
    val name: String,
    val description: String? = null,
    @SerialName("icon_url") val iconUrl: String,
    val versions: List<AppVersionDto>,
)

fun AppDto.toDomain(): App = App(
    packageName = packageName,
    name = name,
    description = description,
    iconUrl = iconUrl,
    latestVersion = latestVersion.toDomain(),
)

fun AppVersionDto.toDomain(): AppVersion = AppVersion(
    versionCode = versionCode,
    versionName = versionName,
    size = size,
    sha256 = sha256,
    minSdk = minSdk,
    uploadedAt = Instant.parse(uploadedAt),
)

fun AppDetailDto.toDomain(): AppDetail = AppDetail(
    packageName = packageName,
    name = name,
    description = description,
    iconUrl = iconUrl,
    versions = versions.map { it.toDomain() },
)

/**
 * Fake AuthStore that is always authenticated for E2E tests.
 */
class FakeAuthStore : AuthStore {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Authenticated("test@example.com"))
    override val authState: StateFlow<AuthState> = _authState

    override suspend fun getAccessToken(): String = "test-access-token"

    override suspend fun logout() {
        _authState.value = AuthState.NotAuthenticated
    }

    fun setAuthenticated(email: String = "test@example.com") {
        _authState.value = AuthState.Authenticated(email)
    }

    fun setNotAuthenticated() {
        _authState.value = AuthState.NotAuthenticated
    }
}
