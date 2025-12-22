# lellostore Android - Architecture Specification

## Overview

This document defines the architecture for the lellostore Android app. The architecture is inspired by pezzottify, following Clean Architecture principles with MVVM pattern, adapted for lellostore's requirements.

## Module Structure

```
android/
├── app/                    # Application entry point, DI wiring
├── ui/                     # Jetpack Compose screens (NO dependency on app)
├── domain/                 # Business logic, interfaces (pure Kotlin)
├── remoteapi/              # Ktor HTTP client, API communication
├── localdata/              # Room database, DataStore persistence
└── logger/                 # Logging infrastructure
```

### Module Dependencies

```
                    ┌─────────────────┐
                    │      :app       │  ← Composition root
                    │  (DI wiring)    │
                    └────────┬────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
         ▼                   ▼                   ▼
┌─────────────┐      ┌─────────────┐     ┌─────────────┐
│    :ui      │      │ :remoteapi  │     │ :localdata  │
│  (Compose)  │      │   (Ktor)    │     │   (Room)    │
└──────┬──────┘      └──────┬──────┘     └──────┬──────┘
       │                    │                   │
       │                    ▼                   ▼
       │             ┌─────────────┐     ┌─────────────┐
       │             │   :domain   │     │   :domain   │
       │             └─────────────┘     └─────────────┘
       │                    │                   │
       ▼                    ▼                   ▼
┌─────────────┐      ┌─────────────────────────────────┐
│   :logger   │      │            :domain              │
└─────────────┘      │     (pure Kotlin, no deps)      │
                     └─────────────────────────────────┘
```

**Key principle**: `:ui` does NOT depend on `:app`. This ensures the UI layer is truly decoupled and can be easily tested in isolation.

### Module Responsibilities

#### `:app`
- `@HiltAndroidApp` Application class
- `@AndroidEntryPoint` MainActivity
- Hilt modules that bind implementations to interfaces
- Provides `@DefaultServerUrl` via DI
- Platform-specific implementations (e.g., PackageManager wrapper)

#### `:ui`
- All Jetpack Compose screens
- ViewModels with `@HiltViewModel`
- Screen state data classes
- Screen events (navigation, permissions)
- Interactor interfaces (ViewModel dependencies)
- Reusable composables
- Theme (Material 3)
- Navigation routes

#### `:domain`
- Domain models (App, AppVersion, etc.)
- Store/Repository interfaces
- Use cases (if needed)
- Platform abstraction interfaces

#### `:remoteapi`
- Ktor HttpClient configuration
- API client implementation
- Request/response DTOs
- Auth token interceptor

#### `:localdata`
- Room database and DAOs
- DataStore preferences
- Store implementations
- Auth token storage (EncryptedSharedPreferences)

#### `:logger`
- Logger interface
- LoggerFactory
- Console/file logging implementations

---

## Architecture Pattern

### Clean Architecture + MVVM

```
┌─────────────────────────────────────────┐
│         UI Layer (Jetpack Compose)      │
│    Screen + ViewModel + State/Events    │
└─────────────────────────────────────────┘
                    ↓ Interactor interface
┌─────────────────────────────────────────┐
│       Domain Layer (Business Logic)     │
│    Models, Store interfaces, Use cases  │
└─────────────────────────────────────────┘
                    ↓ Store interfaces
┌─────────────────────────────────────────┐
│    Data Layer (Repositories, Stores)    │
│   RemoteAPI (Ktor), LocalData (Room)    │
└─────────────────────────────────────────┘
```

### ViewModel Pattern

Each screen follows this pattern:

```kotlin
// State - immutable data class representing UI state
data class CatalogScreenState(
    val apps: List<AppUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val filter: CatalogFilter = CatalogFilter.All,
)

enum class CatalogFilter { All, Installed, NotInstalled }

// Events - one-time events (navigation, permissions, toasts)
sealed interface CatalogScreenEvents {
    data class NavigateToAppDetail(val packageName: String) : CatalogScreenEvents
    data object ShowInstallPermissionDialog : CatalogScreenEvents
}

// Actions - user interactions (implemented by ViewModel)
interface CatalogScreenActions {
    fun onSearchQueryChanged(query: String)
    fun onFilterChanged(filter: CatalogFilter)
    fun onAppClicked(app: AppUiModel)
    fun onRefresh()
}

// ViewModel
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel(), CatalogScreenActions {

    private val mutableState = MutableStateFlow(CatalogScreenState())
    val state = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<CatalogScreenEvents>()
    val events = mutableEvents.asSharedFlow()

    // Interactor interface - decouples ViewModel from domain/data
    interface Interactor {
        fun getApps(): Flow<List<App>>
        suspend fun refreshApps(): Result<Unit>
    }
}
```

### Interactor Pattern

ViewModels depend on an `Interactor` interface defined inside the ViewModel. This:
- Decouples UI from domain layer
- Makes testing trivial (implement fake Interactor)
- Defines exactly what the ViewModel needs

The `Interactor` implementation is provided by `:app` module via Hilt.

---

## Navigation

### Type-Safe Routes with Kotlin Serialization

```kotlin
// ui/src/.../Navigation.kt
sealed interface Screen {

    @Serializable
    data object Splash : Screen

    @Serializable
    data object Login : Screen

    sealed interface Main : Screen {

        @Serializable
        data object Catalog : Main

        @Serializable
        data class AppDetail(val packageName: String) : Main

        @Serializable
        data object Updates : Main

        @Serializable
        data object Settings : Main
    }
}

// Navigation helpers
fun NavController.fromSplashToLogin() = navigate(Screen.Login) {
    popUpTo(Screen.Splash) { inclusive = true }
}

fun NavController.fromSplashToCatalog() = navigate(Screen.Main.Catalog) {
    popUpTo(Screen.Splash) { inclusive = true }
}

fun NavController.toAppDetail(packageName: String) =
    navigate(Screen.Main.AppDetail(packageName))
```

### NavHost Setup

```kotlin
// ui/src/.../AppUi.kt
@Composable
fun AppUi(
    themeMode: ThemeMode = ThemeMode.System,
) {
    val navController = rememberNavController()

    LellostoreTheme(themeMode = themeMode) {
        NavHost(
            navController = navController,
            startDestination = Screen.Splash,
        ) {
            composable<Screen.Splash> { SplashScreen(navController) }
            composable<Screen.Login> { LoginScreen(navController) }
            composable<Screen.Main.Catalog> { CatalogScreen(navController) }
            composable<Screen.Main.AppDetail> { AppDetailScreen(navController) }
            composable<Screen.Main.Updates> { UpdatesScreen(navController) }
            composable<Screen.Main.Settings> { SettingsScreen(navController) }
        }
    }
}
```

---

## Authentication

### OIDC via AppAuth

Authentication uses AppAuth for Android with Authorization Code + PKCE flow.

```kotlin
// domain/src/.../auth/AuthStore.kt
interface AuthStore {
    val authState: StateFlow<AuthState>

    suspend fun login(): AuthResult
    suspend fun logout()
    suspend fun getAccessToken(): String?  // Returns valid token, refreshing if needed
}

sealed interface AuthState {
    data object Loading : AuthState
    data object NotAuthenticated : AuthState
    data class Authenticated(val userEmail: String) : AuthState
}

sealed interface AuthResult {
    data object Success : AuthResult
    data object Cancelled : AuthResult
    data class Error(val message: String) : AuthResult
}
```

### Token Storage

Tokens are stored in `EncryptedSharedPreferences`:

```kotlin
// localdata/src/.../auth/AuthStoreImpl.kt
internal class AuthStoreImpl(
    context: Context,
    private val authService: AuthorizationService,
    private val oidcConfig: OidcConfig,
) : AuthStore {

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Store AppAuth's AuthState (contains tokens + refresh logic)
}
```

### OIDC Configuration

Build-time configuration for OIDC:

```kotlin
// domain/src/.../auth/OidcConfig.kt
data class OidcConfig(
    val issuerUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = listOf("openid", "profile", "email"),
)

// Provided via BuildConfig in :app module
```

---

## Server URL Configuration

### Pattern (following pezzottify)

```kotlin
// localdata/src/.../DefaultServerUrl.kt
@Qualifier
annotation class DefaultServerUrl

// domain/src/.../config/ConfigStore.kt
interface ConfigStore {
    val serverUrl: StateFlow<String>

    suspend fun setServerUrl(url: String): SetServerUrlResult

    sealed interface SetServerUrlResult {
        data object Success : SetServerUrlResult
        data object InvalidUrl : SetServerUrlResult
    }
}

// localdata/src/.../config/ConfigStoreImpl.kt
internal class ConfigStoreImpl(
    private val dataStore: DataStore<Preferences>,
    @DefaultServerUrl private val defaultServerUrl: String,
) : ConfigStore {

    private val serverUrlKey = stringPreferencesKey("server_url")

    override val serverUrl: StateFlow<String> = dataStore.data
        .map { prefs -> prefs[serverUrlKey] ?: defaultServerUrl }
        .stateIn(...)

    override suspend fun setServerUrl(url: String): SetServerUrlResult {
        if (!isValidHttpUrl(url)) return SetServerUrlResult.InvalidUrl
        dataStore.edit { prefs -> prefs[serverUrlKey] = url }
        return SetServerUrlResult.Success
    }
}

// app/src/.../DomainModule.kt
@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {
    companion object {
        @Provides
        @DefaultServerUrl
        fun provideDefaultServerUrl(): String = BuildConfig.DEFAULT_SERVER_URL
    }
}
```

### Usage in Login Screen

```kotlin
// Login screen shows server URL field
// Initial value from ConfigStore.serverUrl
// User can edit before logging in
// On login: validate URL → set URL → perform OIDC login
```

### Usage in Settings Screen

```kotlin
// Settings screen shows server URL field
// Shows current value, allows editing
// Save button validates and persists
// Warning: changing server logs user out
```

---

## Data Layer

### Remote API (Ktor)

```kotlin
// remoteapi/src/.../RemoteApiClient.kt
interface RemoteApiClient {
    suspend fun getApps(): Result<List<AppDto>>
    suspend fun getApp(packageName: String): Result<AppDetailDto>
    suspend fun downloadApk(
        packageName: String,
        versionCode: Int,
        onProgress: (Float) -> Unit,
    ): Result<File>
}

// remoteapi/src/.../internal/RemoteApiClientImpl.kt
internal class RemoteApiClientImpl(
    private val httpClient: HttpClient,
    private val configStore: ConfigStore,
    private val authStore: AuthStore,
) : RemoteApiClient {

    override suspend fun getApps(): Result<List<AppDto>> {
        val token = authStore.getAccessToken() ?: return Result.failure(NotAuthenticatedException())
        val baseUrl = configStore.serverUrl.value

        return runCatching {
            httpClient.get("$baseUrl/api/apps") {
                bearerAuth(token)
            }.body<AppsResponseDto>().apps
        }
    }
}
```

### Ktor Configuration

```kotlin
// remoteapi/src/.../RemoteApiModule.kt
@Module
@InstallIn(SingletonComponent::class)
class RemoteApiModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
        install(Logging) {
            level = LogLevel.BODY
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }
}
```

### Local Data (Room)

```kotlin
// localdata/src/.../db/LellostoreDatabase.kt
@Database(
    entities = [
        CachedAppEntity::class,
        CachedAppVersionEntity::class,
        InstalledAppEntity::class,
    ],
    version = 1,
)
abstract class LellostoreDatabase : RoomDatabase() {
    abstract fun appsDao(): AppsDao
    abstract fun installedAppsDao(): InstalledAppsDao
}

// localdata/src/.../db/AppsDao.kt
@Dao
interface AppsDao {
    @Query("SELECT * FROM cached_apps ORDER BY name ASC")
    fun watchApps(): Flow<List<CachedAppEntity>>

    @Query("SELECT * FROM cached_apps WHERE package_name = :packageName")
    suspend fun getApp(packageName: String): CachedAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<CachedAppEntity>)

    @Query("DELETE FROM cached_apps")
    suspend fun deleteAll()
}
```

### DataStore Preferences

```kotlin
// localdata/src/.../prefs/UserPreferencesStore.kt
interface UserPreferencesStore {
    val themeMode: StateFlow<ThemeMode>
    val updateCheckInterval: StateFlow<Duration>
    val wifiOnlyDownloads: StateFlow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUpdateCheckInterval(interval: Duration)
    suspend fun setWifiOnlyDownloads(enabled: Boolean)
}
```

---

## Domain Models

```kotlin
// domain/src/.../model/App.kt
data class App(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val latestVersion: AppVersion,
)

data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val sha256: String,
    val minSdk: Int,
    val uploadedAt: Instant,
)

data class AppDetail(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val versions: List<AppVersion>,
)

data class InstalledApp(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
)
```

---

## Features

### Screens

| Screen | Route | Description |
|--------|-------|-------------|
| Splash | `Screen.Splash` | Check auth state, navigate to Login or Catalog |
| Login | `Screen.Login` | Server URL + OIDC login button |
| Catalog | `Screen.Main.Catalog` | List all apps with filters, search, pull-to-refresh |
| App Detail | `Screen.Main.AppDetail` | App info, versions, install/update button |
| Updates | `Screen.Main.Updates` | Apps with available updates |
| Settings | `Screen.Main.Settings` | Server URL, theme, update interval |

### Catalog Filters

The Catalog screen includes filter chips to show:
- **All** (default) - All available apps
- **Installed** - Apps currently installed on device
- **Not Installed** - Apps not yet installed

### Bottom Navigation

Main screens use bottom navigation (3 tabs):
- **Catalog** (home icon) - Browse all apps
- **Updates** (refresh icon, with badge for update count) - Apps with available updates
- **Settings** (gear icon) - App preferences

### Top Bar Profile

The top app bar includes a profile button (avatar icon) that opens a bottom sheet with:
- User email (from OIDC)
- Logout button

### Download & Installation

```kotlin
// domain/src/.../download/DownloadManager.kt
interface DownloadManager {
    val activeDownloads: StateFlow<Map<String, DownloadProgress>>

    suspend fun downloadAndInstall(
        packageName: String,
        versionCode: Int,
    ): DownloadResult

    fun cancelDownload(packageName: String)
}

data class DownloadProgress(
    val packageName: String,
    val progress: Float,  // 0.0 to 1.0
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val state: DownloadState,
)

enum class DownloadState {
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
```

### Update Detection

```kotlin
// domain/src/.../updates/UpdateChecker.kt
interface UpdateChecker {
    val availableUpdates: StateFlow<List<AvailableUpdate>>

    suspend fun checkForUpdates(): Result<List<AvailableUpdate>>
}

data class AvailableUpdate(
    val app: App,
    val installedVersionCode: Int,
    val installedVersionName: String,
)
```

### Background Update Check (WorkManager)

```kotlin
// app/src/.../work/UpdateCheckWorker.kt
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updates = updateChecker.checkForUpdates().getOrNull() ?: return Result.retry()

        if (updates.isNotEmpty()) {
            notificationHelper.showUpdatesAvailable(updates.size)
        }

        return Result.success()
    }
}
```

---

## Dependency Injection

### Hilt Modules

```
app/
├── ApplicationModule.kt      # App-scoped dependencies (ImageLoader, etc.)
├── DomainModule.kt           # Binds domain interfaces, provides defaults
├── InteractorModule.kt       # Binds ViewModel interactor implementations
└── WorkerModule.kt           # WorkManager configuration

remoteapi/
└── RemoteApiModule.kt        # HttpClient, API client bindings

localdata/
└── LocalDataModule.kt        # Room, DataStore, Store bindings
```

### Example Bindings

```kotlin
// app/src/.../InteractorModule.kt
@Module
@InstallIn(ViewModelComponent::class)
abstract class InteractorModule {

    @Binds
    abstract fun bindLoginInteractor(
        impl: LoginInteractorImpl
    ): LoginViewModel.Interactor

    @Binds
    abstract fun bindCatalogInteractor(
        impl: CatalogInteractorImpl
    ): CatalogViewModel.Interactor
}

// app/src/.../interactor/LoginInteractorImpl.kt
class LoginInteractorImpl @Inject constructor(
    private val authStore: AuthStore,
    private val configStore: ConfigStore,
) : LoginViewModel.Interactor {

    override fun getInitialServerUrl(): String = configStore.serverUrl.value

    override suspend fun setServerUrl(url: String) = configStore.setServerUrl(url)

    override suspend fun login() = authStore.login()
}
```

---

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (Dagger) |
| Navigation | Navigation Compose + Kotlin Serialization |
| Networking | Ktor Client (OkHttp engine) |
| Local DB | Room |
| Preferences | DataStore |
| Auth | AppAuth for Android |
| Token Storage | EncryptedSharedPreferences |
| Image Loading | Coil 3 |
| Background Work | WorkManager |
| Async | Kotlin Coroutines + Flow |
| Serialization | kotlinx.serialization |

---

## Testing Strategy

### Unit Tests
- ViewModels with fake Interactors
- Use cases and domain logic
- Store implementations with in-memory databases

### Instrumented Tests
- Room DAO tests
- Navigation tests
- Compose UI tests

### Test Doubles
- Fake implementations for all interfaces
- In-memory Room database
- Mock HTTP responses with Ktor MockEngine

---

## Project Configuration

### Version Catalog (`gradle/libs.versions.toml`)

Centralized dependency versions following pezzottify pattern.

### Build Variants

- `debug` - Development build
- `release` - Production build with ProGuard/R8

### Build Config Fields

The default server URL is configured via `local.properties` (which is not checked into version control):

```properties
# android/local.properties
default.server.url=https://store.lelloman.com
```

The `app/build.gradle.kts` reads this property and exposes it via BuildConfig:

```kotlin
// app/build.gradle.kts
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val defaultServerUrl: String = localProperties.getProperty("default.server.url", "https://store.lelloman.com")

android {
    defaultConfig {
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
    }
    buildFeatures {
        buildConfig = true
    }
}
```

OIDC configuration is hardcoded in `AppModule.kt`:

```kotlin
// app/src/.../AppModule.kt
private const val OIDC_ISSUER_URL = "https://auth.lelloman.com"
private const val OIDC_CLIENT_ID = "22cd4a2d-a771-41e3-b76e-3f83ff8e9bbf"
private const val OIDC_REDIRECT_URI = "com.lelloman.store:/oauth2redirect"
```

---

## File Structure (Initial)

```
android/
├── app/
│   └── src/main/java/com/lelloman/store/
│       ├── LellostoreApplication.kt
│       ├── MainActivity.kt
│       ├── ApplicationModule.kt
│       ├── DomainModule.kt
│       ├── InteractorModule.kt
│       ├── interactor/
│       │   ├── LoginInteractorImpl.kt
│       │   ├── CatalogInteractorImpl.kt
│       │   └── ...
│       └── work/
│           └── UpdateCheckWorker.kt
├── ui/
│   └── src/main/java/com/lelloman/store/ui/
│       ├── AppUi.kt
│       ├── Navigation.kt
│       ├── screen/
│       │   ├── splash/
│       │   ├── login/
│       │   ├── main/
│       │   │   ├── catalog/
│       │   │   ├── appdetail/
│       │   │   ├── updates/
│       │   │   ├── settings/
│       │   │   └── profile/  (bottom sheet)
│       │   └── MainScreen.kt (bottom nav + top bar container)
│       ├── component/
│       └── theme/
├── domain/
│   └── src/main/java/com/lelloman/store/domain/
│       ├── model/
│       ├── auth/
│       ├── config/
│       ├── apps/
│       ├── download/
│       └── updates/
├── remoteapi/
│   └── src/main/java/com/lelloman/store/remoteapi/
│       ├── RemoteApiModule.kt
│       ├── RemoteApiClient.kt
│       ├── dto/
│       └── internal/
├── localdata/
│   └── src/main/java/com/lelloman/store/localdata/
│       ├── LocalDataModule.kt
│       ├── DefaultServerUrl.kt
│       ├── db/
│       ├── prefs/
│       ├── auth/
│       └── internal/
├── logger/
│   └── src/main/java/com/lelloman/store/logger/
│       ├── Logger.kt
│       └── LoggerFactory.kt
├── settings.gradle.kts
├── build.gradle.kts
└── gradle/
    └── libs.versions.toml
```

---

## Implementation Order

1. **Project Setup** - Multi-module structure, Gradle config, version catalog
2. **Logger Module** - Basic logging infrastructure
3. **Domain Module** - Models, interfaces
4. **LocalData Module** - Room, DataStore, ConfigStore
5. **RemoteAPI Module** - Ktor client (can test against backend)
6. **UI Module** - Theme, navigation, screens
7. **App Module** - DI wiring, interactors
8. **Auth Integration** - AppAuth OIDC flow
9. **Download & Install** - APK handling
10. **Background Updates** - WorkManager

---

## Revision History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-19 | Initial architecture specification |
| 1.1 | 2025-12-19 | Removed Installed screen, added Catalog filters; 3-tab bottom nav; added top-bar profile button |
