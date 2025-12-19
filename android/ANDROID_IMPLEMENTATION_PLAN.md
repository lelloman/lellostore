# lellostore Android - Implementation Plan

This document provides a detailed, step-by-step implementation plan for the lellostore Android app. Each phase builds upon the previous one, ensuring a working app at each milestone.

## Testing Strategy

This implementation follows a **test-as-you-build** approach:

1. **Unit Tests** - Every component gets unit tests (ViewModels, Stores, Repositories)
2. **Integration Tests** - Test components working together within each module
3. **E2E Android Tests** - Full instrumented tests mocking only the backend (MockWebServer)

### Test Infrastructure

| Component | Test Approach |
|-----------|---------------|
| ViewModels | Unit tests with fake Interactors, Turbine for Flow testing |
| Stores/Repositories | Unit tests with in-memory Room, fake dependencies |
| API Client | Unit tests with Ktor MockEngine |
| DAOs | Instrumented tests with in-memory Room |
| UI Screens | Compose UI tests with fake ViewModels |
| Full App | Instrumented E2E tests with MockWebServer |

### Test Dependencies

```toml
[versions]
junit = "4.13.2"
truth = "1.4.4"
mockk = "1.13.13"
turbine = "1.2.0"
coroutines-test = "1.9.0"
mockwebserver = "4.12.0"
robolectric = "4.14"
androidx-test-core = "1.6.1"
androidx-test-runner = "1.6.2"
androidx-test-rules = "1.6.1"
compose-ui-test = "1.7.6"
hilt-testing = "2.53.1"

[libraries]
# Unit Testing
junit = { module = "junit:junit", version.ref = "junit" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
mockk-android = { module = "io.mockk:mockk-android", version.ref = "mockk" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines-test" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }

# Instrumented Testing
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "mockwebserver" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test-core" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidx-test-runner" }
androidx-test-rules = { module = "androidx.test:rules", version.ref = "androidx-test-rules" }
compose-ui-test = { module = "androidx.compose.ui:ui-test-junit4", version.ref = "compose-ui-test" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest", version.ref = "compose-ui-test" }
hilt-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt-testing" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
```

---

## Overview

| Phase | Name | Description | Milestone |
|-------|------|-------------|-----------|
| 1 | Project Setup | Multi-module structure, Gradle configuration | Builds successfully |
| 2 | Logger Module | Logging infrastructure | Can log messages |
| 3 | Domain Module | Models and interfaces | Compiles with domain types |
| 4 | LocalData Module | Room, DataStore, stores | Can persist data locally |
| 5 | RemoteAPI Module | Ktor HTTP client | Can fetch from backend |
| 6 | UI Foundation | Theme, navigation, shell | App launches with navigation |
| 7 | Authentication | AppAuth OIDC flow | Can login/logout |
| 8 | Catalog & Detail | App list and detail screens | Can browse apps |
| 9 | Download & Install | APK download and installation | Can install apps |
| 10 | Updates & Background | Update detection, WorkManager | Background update checks |
| 11 | E2E Tests | Full app instrumented tests | Complete test coverage |

---

## Phase 1: Project Setup

**Goal**: Establish multi-module Gradle project with proper configuration.

### 1.1 Create Module Structure

```
android/
├── app/
├── ui/
├── domain/
├── remoteapi/
├── localdata/
├── logger/
├── settings.gradle.kts
├── build.gradle.kts
└── gradle/
    └── libs.versions.toml
```

#### Tasks

- [ ] Update `settings.gradle.kts` to include all modules
- [ ] Create module directories with `build.gradle.kts` for each
- [ ] Configure root `build.gradle.kts` with common plugins

### 1.2 Version Catalog

Create `gradle/libs.versions.toml` with all dependencies:

#### Versions to define

```toml
[versions]
kotlin = "2.0.21"
agp = "8.7.3"
hilt = "2.53.1"
room = "2.6.1"
ktor = "3.0.2"
coroutines = "1.9.0"
compose-bom = "2024.12.01"
navigation = "2.8.5"
datastore = "1.1.1"
coil = "3.0.4"
appauth = "0.11.1"
work = "2.10.0"
security-crypto = "1.1.0-alpha06"
kotlinx-serialization = "1.7.3"
```

#### Dependencies to define

- AndroidX Core, Lifecycle, Activity
- Compose (via BOM): UI, Material3, Foundation, Runtime
- Navigation Compose
- Hilt + Hilt Navigation Compose
- Room + KSP
- Ktor Client (Core, OkHttp, Content Negotiation, Logging)
- DataStore Preferences
- Coil Compose
- AppAuth
- WorkManager + Hilt Worker
- Security Crypto
- kotlinx.serialization
- Testing: JUnit, Truth, MockK, Coroutines Test, Turbine

### 1.3 Module Build Files

#### `:app` module

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.lelloman.store"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lelloman.store"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://10.0.2.2:3000\"")
        buildConfigField("String", "OIDC_ISSUER", "\"https://auth.example.com\"")
        buildConfigField("String", "OIDC_CLIENT_ID", "\"lellostore-android\"")
        buildConfigField("String", "OIDC_REDIRECT_URI", "\"com.lelloman.store:/oauth2callback\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":domain"))
    implementation(project(":remoteapi"))
    implementation(project(":localdata"))
    implementation(project(":logger"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // WorkManager
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // AppAuth
    implementation(libs.appauth)
}
```

#### `:ui` module

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.lelloman.store.ui"
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":logger"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coil
    implementation(libs.coil.compose)

    // Serialization (for navigation)
    implementation(libs.kotlinx.serialization.core)
}
```

#### `:domain` module

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.datetime)  // For Instant
}
```

#### `:remoteapi` module

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.lelloman.store.remoteapi"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":logger"))

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

#### `:localdata` module

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.lelloman.store.localdata"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":logger"))

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security
    implementation(libs.security.crypto)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

#### `:logger` module

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // No dependencies - pure Kotlin
}
```

### 1.4 Test Configuration

Each module needs test dependencies configured:

```kotlin
// For all modules - in build.gradle.kts
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}

// For Android library modules - add instrumented test deps
dependencies {
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}

// For :app module - add E2E test deps
dependencies {
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

### 1.5 Verification

- [ ] Run `./gradlew clean build` - should succeed
- [ ] Run `./gradlew test` - test task exists (no tests yet)
- [ ] All modules appear in Android Studio
- [ ] No dependency conflicts

---

## Phase 2: Logger Module

**Goal**: Create logging infrastructure for use across all modules.

### 2.1 Logger Interface

```kotlin
// logger/src/main/java/com/lelloman/store/logger/Logger.kt
interface Logger {
    fun verbose(message: String, throwable: Throwable? = null)
    fun debug(message: String, throwable: Throwable? = null)
    fun info(message: String, throwable: Throwable? = null)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)
}
```

### 2.2 LoggerFactory

```kotlin
// logger/src/main/java/com/lelloman/store/logger/LoggerFactory.kt
interface LoggerFactory {
    fun create(tag: String): Logger
}

// Extension for lazy delegation
operator fun LoggerFactory.provideDelegate(
    thisRef: Any,
    property: kotlin.reflect.KProperty<*>
): Lazy<Logger> = lazy { create(thisRef::class.java.simpleName) }
```

### 2.3 Console Logger Implementation

```kotlin
// logger/src/main/java/com/lelloman/store/logger/ConsoleLogger.kt
class ConsoleLogger(private val tag: String) : Logger {
    override fun debug(message: String, throwable: Throwable?) {
        println("D/$tag: $message")
        throwable?.printStackTrace()
    }
    // ... other levels
}

class ConsoleLoggerFactory : LoggerFactory {
    override fun create(tag: String): Logger = ConsoleLogger(tag)
}
```

### 2.4 Android Logger (in :app)

```kotlin
// app/src/.../logging/AndroidLogger.kt
class AndroidLogger(private val tag: String) : Logger {
    override fun debug(message: String, throwable: Throwable?) {
        Log.d(tag, message, throwable)
    }
    // ... other levels
}

class AndroidLoggerFactory : LoggerFactory {
    override fun create(tag: String): Logger = AndroidLogger(tag)
}
```

### 2.5 Unit Tests

```kotlin
// logger/src/test/java/com/lelloman/store/logger/ConsoleLoggerTest.kt
class ConsoleLoggerTest {

    @Test
    fun `debug logs message with correct tag`() {
        val output = captureStdout {
            val logger = ConsoleLogger("TestTag")
            logger.debug("test message")
        }
        assertThat(output).contains("D/TestTag: test message")
    }

    @Test
    fun `error logs message and throwable`() {
        val exception = RuntimeException("test error")
        val output = captureStdout {
            val logger = ConsoleLogger("TestTag")
            logger.error("error occurred", exception)
        }
        assertThat(output).contains("E/TestTag: error occurred")
        assertThat(output).contains("RuntimeException")
    }
}

// logger/src/test/java/com/lelloman/store/logger/LoggerFactoryTest.kt
class LoggerFactoryTest {

    @Test
    fun `provideDelegate creates logger with class name as tag`() {
        val factory = ConsoleLoggerFactory()
        val testClass = TestClass(factory)

        assertThat(testClass.loggerTag).isEqualTo("TestClass")
    }

    private class TestClass(loggerFactory: LoggerFactory) {
        private val logger: Logger by loggerFactory
        val loggerTag: String get() = (logger as ConsoleLogger).tag
    }
}
```

### 2.6 Verification

- [ ] Logger compiles in `:logger` module
- [ ] Can use logger via delegation: `private val logger: Logger by loggerFactory`
- [ ] `./gradlew :logger:test` passes

---

## Phase 3: Domain Module

**Goal**: Define all domain models and interfaces.

### 3.1 Models

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

data class AvailableUpdate(
    val app: App,
    val installedVersionCode: Int,
    val installedVersionName: String,
)
```

### 3.2 Auth Interfaces

```kotlin
// domain/src/.../auth/AuthStore.kt
interface AuthStore {
    val authState: StateFlow<AuthState>
    suspend fun getAccessToken(): String?
    suspend fun logout()
}

sealed interface AuthState {
    data object Loading : AuthState
    data object NotAuthenticated : AuthState
    data class Authenticated(val userEmail: String) : AuthState
}

// domain/src/.../auth/OidcConfig.kt
data class OidcConfig(
    val issuerUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = listOf("openid", "profile", "email"),
)
```

### 3.3 Config Interfaces

```kotlin
// domain/src/.../config/ConfigStore.kt
interface ConfigStore {
    val serverUrl: StateFlow<String>
    suspend fun setServerUrl(url: String): SetServerUrlResult

    sealed interface SetServerUrlResult {
        data object Success : SetServerUrlResult
        data object InvalidUrl : SetServerUrlResult
    }
}
```

### 3.4 Apps Interfaces

```kotlin
// domain/src/.../apps/AppsRepository.kt
interface AppsRepository {
    fun watchApps(): Flow<List<App>>
    fun watchApp(packageName: String): Flow<AppDetail?>
    suspend fun refreshApps(): Result<Unit>
    suspend fun refreshApp(packageName: String): Result<AppDetail>
}

// domain/src/.../apps/InstalledAppsRepository.kt
interface InstalledAppsRepository {
    fun watchInstalledApps(): Flow<List<InstalledApp>>
    suspend fun refreshInstalledApps()
    fun isInstalled(packageName: String): Flow<Boolean>
    fun getInstalledVersion(packageName: String): Flow<InstalledApp?>
}
```

### 3.5 Download Interfaces

```kotlin
// domain/src/.../download/DownloadManager.kt
interface DownloadManager {
    val activeDownloads: StateFlow<Map<String, DownloadProgress>>
    suspend fun downloadAndInstall(packageName: String, versionCode: Int): DownloadResult
    fun cancelDownload(packageName: String)
}

data class DownloadProgress(
    val packageName: String,
    val progress: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val state: DownloadState,
)

enum class DownloadState {
    PENDING, DOWNLOADING, VERIFYING, INSTALLING, COMPLETED, FAILED, CANCELLED
}

sealed interface DownloadResult {
    data object Success : DownloadResult
    data object Cancelled : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}
```

### 3.6 Updates Interfaces

```kotlin
// domain/src/.../updates/UpdateChecker.kt
interface UpdateChecker {
    val availableUpdates: StateFlow<List<AvailableUpdate>>
    suspend fun checkForUpdates(): Result<List<AvailableUpdate>>
}
```

### 3.7 Preferences Interfaces

```kotlin
// domain/src/.../preferences/UserPreferencesStore.kt
interface UserPreferencesStore {
    val themeMode: StateFlow<ThemeMode>
    val updateCheckInterval: StateFlow<UpdateCheckInterval>
    val wifiOnlyDownloads: StateFlow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUpdateCheckInterval(interval: UpdateCheckInterval)
    suspend fun setWifiOnlyDownloads(enabled: Boolean)
}

enum class ThemeMode { System, Light, Dark }

enum class UpdateCheckInterval {
    Hours6, Hours12, Hours24, Manual
}
```

### 3.8 Verification

- [ ] `:domain` module compiles
- [ ] All interfaces defined
- [ ] No Android dependencies in `:domain`

---

## Phase 4: LocalData Module

**Goal**: Implement local persistence with Room and DataStore.

### 4.1 Room Database

#### Entities

```kotlin
// localdata/src/.../db/entity/CachedAppEntity.kt
@Entity(tableName = "cached_apps")
data class CachedAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "icon_url")
    val iconUrl: String,
    @ColumnInfo(name = "latest_version_code")
    val latestVersionCode: Int,
    @ColumnInfo(name = "latest_version_name")
    val latestVersionName: String,
    @ColumnInfo(name = "latest_version_size")
    val latestVersionSize: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

// localdata/src/.../db/entity/CachedAppVersionEntity.kt
@Entity(
    tableName = "cached_app_versions",
    primaryKeys = ["package_name", "version_code"],
    foreignKeys = [ForeignKey(
        entity = CachedAppEntity::class,
        parentColumns = ["package_name"],
        childColumns = ["package_name"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class CachedAppVersionEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "version_code")
    val versionCode: Int,
    @ColumnInfo(name = "version_name")
    val versionName: String,
    val size: Long,
    val sha256: String,
    @ColumnInfo(name = "min_sdk")
    val minSdk: Int,
    @ColumnInfo(name = "uploaded_at")
    val uploadedAt: Long,
)

// localdata/src/.../db/entity/InstalledAppEntity.kt
@Entity(tableName = "installed_apps")
data class InstalledAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "version_code")
    val versionCode: Int,
    @ColumnInfo(name = "version_name")
    val versionName: String,
    @ColumnInfo(name = "last_checked")
    val lastChecked: Long,
)
```

#### DAOs

```kotlin
// localdata/src/.../db/dao/AppsDao.kt
@Dao
interface AppsDao {
    @Query("SELECT * FROM cached_apps ORDER BY name ASC")
    fun watchApps(): Flow<List<CachedAppEntity>>

    @Query("SELECT * FROM cached_apps WHERE package_name = :packageName")
    fun watchApp(packageName: String): Flow<CachedAppEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<CachedAppEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: CachedAppEntity)

    @Query("DELETE FROM cached_apps")
    suspend fun deleteAll()

    @Query("DELETE FROM cached_apps WHERE package_name = :packageName")
    suspend fun deleteApp(packageName: String)
}

// localdata/src/.../db/dao/AppVersionsDao.kt
@Dao
interface AppVersionsDao {
    @Query("SELECT * FROM cached_app_versions WHERE package_name = :packageName ORDER BY version_code DESC")
    fun watchVersions(packageName: String): Flow<List<CachedAppVersionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersions(versions: List<CachedAppVersionEntity>)

    @Query("DELETE FROM cached_app_versions WHERE package_name = :packageName")
    suspend fun deleteVersions(packageName: String)
}

// localdata/src/.../db/dao/InstalledAppsDao.kt
@Dao
interface InstalledAppsDao {
    @Query("SELECT * FROM installed_apps ORDER BY package_name ASC")
    fun watchAll(): Flow<List<InstalledAppEntity>>

    @Query("SELECT * FROM installed_apps WHERE package_name = :packageName")
    fun watch(packageName: String): Flow<InstalledAppEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: InstalledAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<InstalledAppEntity>)

    @Query("DELETE FROM installed_apps WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM installed_apps")
    suspend fun deleteAll()
}
```

#### Database

```kotlin
// localdata/src/.../db/LellostoreDatabase.kt
@Database(
    entities = [
        CachedAppEntity::class,
        CachedAppVersionEntity::class,
        InstalledAppEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LellostoreDatabase : RoomDatabase() {
    abstract fun appsDao(): AppsDao
    abstract fun appVersionsDao(): AppVersionsDao
    abstract fun installedAppsDao(): InstalledAppsDao
}
```

### 4.2 DataStore

```kotlin
// localdata/src/.../prefs/PreferencesKeys.kt
object PreferencesKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val UPDATE_CHECK_INTERVAL = stringPreferencesKey("update_check_interval")
    val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
}
```

### 4.3 Store Implementations

```kotlin
// localdata/src/.../config/ConfigStoreImpl.kt
internal class ConfigStoreImpl(
    private val dataStore: DataStore<Preferences>,
    @DefaultServerUrl private val defaultServerUrl: String,
    private val scope: CoroutineScope,
) : ConfigStore {

    override val serverUrl: StateFlow<String> = dataStore.data
        .map { it[PreferencesKeys.SERVER_URL] ?: defaultServerUrl }
        .stateIn(scope, SharingStarted.Eagerly, defaultServerUrl)

    override suspend fun setServerUrl(url: String): SetServerUrlResult {
        if (!isValidHttpUrl(url)) return SetServerUrlResult.InvalidUrl
        dataStore.edit { it[PreferencesKeys.SERVER_URL] = url }
        return SetServerUrlResult.Success
    }

    private fun isValidHttpUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) { false }
    }
}

// localdata/src/.../DefaultServerUrl.kt
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultServerUrl
```

### 4.4 Hilt Module

```kotlin
// localdata/src/.../LocalDataModule.kt
@Module
@InstallIn(SingletonComponent::class)
class LocalDataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LellostoreDatabase {
        return Room.databaseBuilder(
            context,
            LellostoreDatabase::class.java,
            "lellostore.db"
        ).build()
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
            context.preferencesDataStoreFile("settings")
        }
    }

    @Provides
    @Singleton
    fun provideConfigStore(
        dataStore: DataStore<Preferences>,
        @DefaultServerUrl defaultServerUrl: String,
        @ApplicationScope scope: CoroutineScope,
    ): ConfigStore = ConfigStoreImpl(dataStore, defaultServerUrl, scope)
}
```

### 4.5 Unit Tests - Stores

```kotlin
// localdata/src/test/java/com/lelloman/store/localdata/config/ConfigStoreImplTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigStoreImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var configStore: ConfigStoreImpl

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { File.createTempFile("test", ".preferences_pb") }
        )
        configStore = ConfigStoreImpl(
            dataStore = dataStore,
            defaultServerUrl = "http://default.example.com",
            scope = testScope.backgroundScope,
        )
    }

    @Test
    fun `serverUrl returns default when not set`() = testScope.runTest {
        assertThat(configStore.serverUrl.value).isEqualTo("http://default.example.com")
    }

    @Test
    fun `setServerUrl with valid URL returns Success`() = testScope.runTest {
        val result = configStore.setServerUrl("https://new.example.com")

        assertThat(result).isEqualTo(ConfigStore.SetServerUrlResult.Success)
        assertThat(configStore.serverUrl.value).isEqualTo("https://new.example.com")
    }

    @Test
    fun `setServerUrl with invalid URL returns InvalidUrl`() = testScope.runTest {
        val result = configStore.setServerUrl("not-a-url")

        assertThat(result).isEqualTo(ConfigStore.SetServerUrlResult.InvalidUrl)
        assertThat(configStore.serverUrl.value).isEqualTo("http://default.example.com")
    }

    @Test
    fun `setServerUrl with empty string returns InvalidUrl`() = testScope.runTest {
        val result = configStore.setServerUrl("")

        assertThat(result).isEqualTo(ConfigStore.SetServerUrlResult.InvalidUrl)
    }

    @Test
    fun `setServerUrl accepts http and https`() = testScope.runTest {
        assertThat(configStore.setServerUrl("http://example.com"))
            .isEqualTo(ConfigStore.SetServerUrlResult.Success)
        assertThat(configStore.setServerUrl("https://example.com"))
            .isEqualTo(ConfigStore.SetServerUrlResult.Success)
    }
}

// localdata/src/test/java/com/lelloman/store/localdata/prefs/UserPreferencesStoreImplTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesStoreImplTest {

    private val testScope = TestScope()
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefsStore: UserPreferencesStoreImpl

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { File.createTempFile("test", ".preferences_pb") }
        )
        prefsStore = UserPreferencesStoreImpl(dataStore, testScope.backgroundScope)
    }

    @Test
    fun `themeMode defaults to System`() = testScope.runTest {
        assertThat(prefsStore.themeMode.value).isEqualTo(ThemeMode.System)
    }

    @Test
    fun `setThemeMode updates value`() = testScope.runTest {
        prefsStore.setThemeMode(ThemeMode.Dark)
        advanceUntilIdle()

        assertThat(prefsStore.themeMode.value).isEqualTo(ThemeMode.Dark)
    }

    @Test
    fun `updateCheckInterval defaults to Hours24`() = testScope.runTest {
        assertThat(prefsStore.updateCheckInterval.value).isEqualTo(UpdateCheckInterval.Hours24)
    }

    @Test
    fun `wifiOnlyDownloads defaults to false`() = testScope.runTest {
        assertThat(prefsStore.wifiOnlyDownloads.value).isFalse()
    }
}
```

### 4.6 Instrumented Tests - DAOs

```kotlin
// localdata/src/androidTest/java/com/lelloman/store/localdata/db/AppsDaoTest.kt
@RunWith(AndroidJUnit4::class)
class AppsDaoTest {

    private lateinit var database: LellostoreDatabase
    private lateinit var appsDao: AppsDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LellostoreDatabase::class.java
        ).allowMainThreadQueries().build()
        appsDao = database.appsDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertApps_and_watchApps_returnsInsertedApps() = runTest {
        val apps = listOf(
            CachedAppEntity(
                packageName = "com.example.app1",
                name = "App 1",
                description = "Description 1",
                iconUrl = "http://example.com/icon1.png",
                latestVersionCode = 1,
                latestVersionName = "1.0.0",
                latestVersionSize = 1000L,
                updatedAt = System.currentTimeMillis(),
            ),
            CachedAppEntity(
                packageName = "com.example.app2",
                name = "App 2",
                description = null,
                iconUrl = "http://example.com/icon2.png",
                latestVersionCode = 2,
                latestVersionName = "2.0.0",
                latestVersionSize = 2000L,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        appsDao.insertApps(apps)

        val result = appsDao.watchApps().first()
        assertThat(result).hasSize(2)
        assertThat(result.map { it.packageName }).containsExactly("com.example.app1", "com.example.app2")
    }

    @Test
    fun watchApp_returnsSpecificApp() = runTest {
        val app = CachedAppEntity(
            packageName = "com.example.test",
            name = "Test App",
            description = "Test",
            iconUrl = "http://example.com/icon.png",
            latestVersionCode = 1,
            latestVersionName = "1.0.0",
            latestVersionSize = 1000L,
            updatedAt = System.currentTimeMillis(),
        )
        appsDao.insertApp(app)

        val result = appsDao.watchApp("com.example.test").first()
        assertThat(result?.name).isEqualTo("Test App")
    }

    @Test
    fun watchApp_returnsNullForNonexistent() = runTest {
        val result = appsDao.watchApp("com.nonexistent").first()
        assertThat(result).isNull()
    }

    @Test
    fun deleteAll_clearsAllApps() = runTest {
        appsDao.insertApps(listOf(
            createTestApp("com.example.app1"),
            createTestApp("com.example.app2"),
        ))

        appsDao.deleteAll()

        val result = appsDao.watchApps().first()
        assertThat(result).isEmpty()
    }

    private fun createTestApp(packageName: String) = CachedAppEntity(
        packageName = packageName,
        name = "Test",
        description = null,
        iconUrl = "",
        latestVersionCode = 1,
        latestVersionName = "1.0",
        latestVersionSize = 0,
        updatedAt = 0,
    )
}

// localdata/src/androidTest/java/com/lelloman/store/localdata/db/InstalledAppsDaoTest.kt
@RunWith(AndroidJUnit4::class)
class InstalledAppsDaoTest {

    private lateinit var database: LellostoreDatabase
    private lateinit var installedAppsDao: InstalledAppsDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LellostoreDatabase::class.java
        ).allowMainThreadQueries().build()
        installedAppsDao = database.installedAppsDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insert_and_watch_returnsInstalledApp() = runTest {
        val app = InstalledAppEntity(
            packageName = "com.example.installed",
            versionCode = 10,
            versionName = "1.0.0",
            lastChecked = System.currentTimeMillis(),
        )

        installedAppsDao.insert(app)

        val result = installedAppsDao.watch("com.example.installed").first()
        assertThat(result?.versionCode).isEqualTo(10)
    }

    @Test
    fun delete_removesApp() = runTest {
        installedAppsDao.insert(InstalledAppEntity(
            packageName = "com.example.toremove",
            versionCode = 1,
            versionName = "1.0",
            lastChecked = 0,
        ))

        installedAppsDao.delete("com.example.toremove")

        val result = installedAppsDao.watch("com.example.toremove").first()
        assertThat(result).isNull()
    }
}
```

### 4.7 Verification

- [ ] Room database compiles
- [ ] `./gradlew :localdata:test` passes (Store unit tests)
- [ ] `./gradlew :localdata:connectedAndroidTest` passes (DAO tests)
- [ ] ConfigStore reads/writes to DataStore

---

## Phase 5: RemoteAPI Module

**Goal**: Implement Ktor HTTP client for API communication.

### 5.1 DTOs

```kotlin
// remoteapi/src/.../dto/AppDto.kt
@Serializable
data class AppsResponseDto(
    val apps: List<AppDto>
)

@Serializable
data class AppDto(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val latestVersion: AppVersionDto,
)

@Serializable
data class AppVersionDto(
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val sha256: String,
    val minSdk: Int,
    val uploadedAt: String,  // ISO 8601
)

@Serializable
data class AppDetailDto(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val versions: List<AppVersionDto>,
)
```

### 5.2 API Client Interface

```kotlin
// remoteapi/src/.../RemoteApiClient.kt
interface RemoteApiClient {
    suspend fun getApps(): Result<List<AppDto>>
    suspend fun getApp(packageName: String): Result<AppDetailDto>
    suspend fun downloadApk(
        packageName: String,
        versionCode: Int,
        destination: File,
        onProgress: (bytesReceived: Long, contentLength: Long) -> Unit,
    ): Result<File>
}
```

### 5.3 API Client Implementation

```kotlin
// remoteapi/src/.../internal/RemoteApiClientImpl.kt
internal class RemoteApiClientImpl(
    private val httpClient: HttpClient,
    private val configStore: ConfigStore,
    private val authStore: AuthStore,
    private val loggerFactory: LoggerFactory,
) : RemoteApiClient {

    private val logger by loggerFactory

    override suspend fun getApps(): Result<List<AppDto>> = runCatching {
        val token = authStore.getAccessToken()
            ?: throw NotAuthenticatedException()

        httpClient.get("${configStore.serverUrl.value}/api/apps") {
            bearerAuth(token)
        }.body<AppsResponseDto>().apps
    }.onFailure { logger.error("Failed to get apps", it) }

    override suspend fun getApp(packageName: String): Result<AppDetailDto> = runCatching {
        val token = authStore.getAccessToken()
            ?: throw NotAuthenticatedException()

        httpClient.get("${configStore.serverUrl.value}/api/apps/$packageName") {
            bearerAuth(token)
        }.body()
    }

    override suspend fun downloadApk(
        packageName: String,
        versionCode: Int,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> = runCatching {
        val token = authStore.getAccessToken()
            ?: throw NotAuthenticatedException()

        val url = "${configStore.serverUrl.value}/api/apps/$packageName/versions/$versionCode/apk"

        httpClient.prepareGet(url) {
            bearerAuth(token)
        }.execute { response ->
            val contentLength = response.contentLength() ?: -1L
            var bytesReceived = 0L

            destination.outputStream().use { output ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(8192)

                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        bytesReceived += read
                        onProgress(bytesReceived, contentLength)
                    }
                }
            }
        }

        destination
    }
}

class NotAuthenticatedException : Exception("User is not authenticated")
```

### 5.4 Hilt Module

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
                isLenient = true
            })
        }
        install(Logging) {
            logger = object : io.ktor.client.plugins.logging.Logger {
                override fun log(message: String) {
                    android.util.Log.d("Ktor", message)
                }
            }
            level = LogLevel.HEADERS
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    @Provides
    @Singleton
    fun provideRemoteApiClient(
        httpClient: HttpClient,
        configStore: ConfigStore,
        authStore: AuthStore,
        loggerFactory: LoggerFactory,
    ): RemoteApiClient = RemoteApiClientImpl(
        httpClient, configStore, authStore, loggerFactory
    )
}
```

### 5.5 Unit Tests - API Client with MockEngine

```kotlin
// remoteapi/src/test/java/com/lelloman/store/remoteapi/RemoteApiClientImplTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteApiClientImplTest {

    private val testScope = TestScope()
    private lateinit var mockEngine: MockEngine
    private lateinit var httpClient: HttpClient
    private lateinit var fakeConfigStore: FakeConfigStore
    private lateinit var fakeAuthStore: FakeAuthStore
    private lateinit var apiClient: RemoteApiClientImpl

    @Before
    fun setup() {
        fakeConfigStore = FakeConfigStore("http://test.example.com")
        fakeAuthStore = FakeAuthStore()
        fakeAuthStore.setAccessToken("test-token")
    }

    private fun createClient(responseHandler: MockRequestHandler): RemoteApiClientImpl {
        mockEngine = MockEngine(responseHandler)
        httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return RemoteApiClientImpl(
            httpClient = httpClient,
            configStore = fakeConfigStore,
            authStore = fakeAuthStore,
            loggerFactory = ConsoleLoggerFactory(),
        )
    }

    @Test
    fun `getApps returns list of apps on success`() = testScope.runTest {
        val responseJson = """
            {
                "apps": [
                    {
                        "packageName": "com.example.app1",
                        "name": "App 1",
                        "description": "Description",
                        "iconUrl": "/icon1.png",
                        "latestVersion": {
                            "versionCode": 1,
                            "versionName": "1.0.0",
                            "size": 1000,
                            "sha256": "abc123",
                            "minSdk": 26,
                            "uploadedAt": "2025-01-01T00:00:00Z"
                        }
                    }
                ]
            }
        """.trimIndent()

        apiClient = createClient { request ->
            assertThat(request.url.toString()).isEqualTo("http://test.example.com/api/apps")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-token")
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val result = apiClient.getApps()

        assertThat(result.isSuccess).isTrue()
        val apps = result.getOrThrow()
        assertThat(apps).hasSize(1)
        assertThat(apps[0].packageName).isEqualTo("com.example.app1")
        assertThat(apps[0].name).isEqualTo("App 1")
    }

    @Test
    fun `getApps returns failure when not authenticated`() = testScope.runTest {
        fakeAuthStore.setAccessToken(null)
        apiClient = createClient { respond("", HttpStatusCode.OK) }

        val result = apiClient.getApps()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(NotAuthenticatedException::class.java)
    }

    @Test
    fun `getApps returns failure on HTTP error`() = testScope.runTest {
        apiClient = createClient {
            respond(
                content = """{"error": "internal_error", "message": "Server error"}""",
                status = HttpStatusCode.InternalServerError
            )
        }

        val result = apiClient.getApps()

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `getApp returns app detail on success`() = testScope.runTest {
        val responseJson = """
            {
                "packageName": "com.example.app1",
                "name": "App 1",
                "description": "Full description",
                "iconUrl": "/icon1.png",
                "versions": [
                    {
                        "versionCode": 2,
                        "versionName": "2.0.0",
                        "size": 2000,
                        "sha256": "def456",
                        "minSdk": 26,
                        "uploadedAt": "2025-01-02T00:00:00Z"
                    },
                    {
                        "versionCode": 1,
                        "versionName": "1.0.0",
                        "size": 1000,
                        "sha256": "abc123",
                        "minSdk": 26,
                        "uploadedAt": "2025-01-01T00:00:00Z"
                    }
                ]
            }
        """.trimIndent()

        apiClient = createClient { request ->
            assertThat(request.url.toString()).isEqualTo("http://test.example.com/api/apps/com.example.app1")
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val result = apiClient.getApp("com.example.app1")

        assertThat(result.isSuccess).isTrue()
        val app = result.getOrThrow()
        assertThat(app.packageName).isEqualTo("com.example.app1")
        assertThat(app.versions).hasSize(2)
    }

    @Test
    fun `getApp returns failure for 404`() = testScope.runTest {
        apiClient = createClient {
            respond(
                content = """{"error": "not_found", "message": "App not found"}""",
                status = HttpStatusCode.NotFound
            )
        }

        val result = apiClient.getApp("com.nonexistent")

        assertThat(result.isFailure).isTrue()
    }
}

// Test doubles
class FakeConfigStore(private val serverUrl: String) : ConfigStore {
    override val serverUrl = MutableStateFlow(serverUrl)
    override suspend fun setServerUrl(url: String) = ConfigStore.SetServerUrlResult.Success
}

class FakeAuthStore : AuthStore {
    private var accessToken: String? = null
    override val authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)

    fun setAccessToken(token: String?) {
        accessToken = token
        authState.value = if (token != null) AuthState.Authenticated("test@example.com") else AuthState.NotAuthenticated
    }

    override suspend fun getAccessToken() = accessToken
    override suspend fun logout() {
        accessToken = null
        authState.value = AuthState.NotAuthenticated
    }
}
```

### 5.6 Verification

- [ ] Ktor client compiles
- [ ] `./gradlew :remoteapi:test` passes (MockEngine tests)
- [ ] All API endpoints tested with mock responses

---

## Phase 6: UI Foundation

**Goal**: Create theme, navigation, and app shell.

### 6.1 Theme

```kotlin
// ui/src/.../theme/Color.kt
// Define color palette

// ui/src/.../theme/Type.kt
// Define typography

// ui/src/.../theme/Theme.kt
@Composable
fun LellostoreTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        typography = Typography,
        content = content,
    )
}
```

### 6.2 Navigation

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

fun NavController.fromSplashToMain() = navigate(Screen.Main.Catalog) {
    popUpTo(Screen.Splash) { inclusive = true }
}

fun NavController.fromLoginToMain() = navigate(Screen.Main.Catalog) {
    popUpTo(Screen.Login) { inclusive = true }
}

fun NavController.toAppDetail(packageName: String) =
    navigate(Screen.Main.AppDetail(packageName))

fun NavController.logout() = navigate(Screen.Login) {
    popUpTo(0) { inclusive = true }
}
```

### 6.3 Main Screen Shell

```kotlin
// ui/src/.../screen/main/MainScreen.kt
@Composable
fun MainScreen(
    navController: NavController,
    onProfileClick: () -> Unit,
) {
    val mainNavController = rememberNavController()

    Scaffold(
        topBar = {
            LellostoreTopBar(onProfileClick = onProfileClick)
        },
        bottomBar = {
            LellostoreBottomNav(mainNavController)
        },
    ) { padding ->
        NavHost(
            navController = mainNavController,
            startDestination = Screen.Main.Catalog,
            modifier = Modifier.padding(padding),
        ) {
            composable<Screen.Main.Catalog> {
                CatalogScreen(
                    onAppClick = { navController.toAppDetail(it) }
                )
            }
            composable<Screen.Main.Updates> {
                UpdatesScreen(
                    onAppClick = { navController.toAppDetail(it) }
                )
            }
            composable<Screen.Main.Settings> {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun LellostoreBottomNav(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Home, contentDescription = "Catalog") },
            label = { Text("Catalog") },
            selected = currentDestination?.hasRoute<Screen.Main.Catalog>() == true,
            onClick = { navController.navigate(Screen.Main.Catalog) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Refresh, contentDescription = "Updates") },
            label = { Text("Updates") },
            selected = currentDestination?.hasRoute<Screen.Main.Updates>() == true,
            onClick = { navController.navigate(Screen.Main.Updates) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentDestination?.hasRoute<Screen.Main.Settings>() == true,
            onClick = { navController.navigate(Screen.Main.Settings) }
        )
    }
}
```

### 6.4 Profile Bottom Sheet

```kotlin
// ui/src/.../screen/main/profile/ProfileBottomSheet.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBottomSheet(
    userEmail: String,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(userEmail, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Logout")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
```

### 6.5 App Entry Point

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

            composable<Screen.Main.Catalog> {
                // This will be replaced with MainScreen wrapper
                MainScreen(navController, onProfileClick = { /* TODO */ })
            }

            composable<Screen.Main.AppDetail> { backStackEntry ->
                val packageName = backStackEntry.toRoute<Screen.Main.AppDetail>().packageName
                AppDetailScreen(packageName, navController)
            }
        }
    }
}
```

### 6.6 Placeholder Screens

Create placeholder composables for:
- [ ] SplashScreen
- [ ] LoginScreen
- [ ] CatalogScreen
- [ ] AppDetailScreen
- [ ] UpdatesScreen
- [ ] SettingsScreen

### 6.7 Verification

- [ ] App launches
- [ ] Navigation works between screens
- [ ] Bottom navigation switches tabs
- [ ] Theme applies correctly

---

## Phase 7: Authentication

**Goal**: Implement OIDC authentication with AppAuth.

### 7.1 Auth Store Implementation

```kotlin
// localdata/src/.../auth/AuthStoreImpl.kt
internal class AuthStoreImpl(
    private val context: Context,
    private val oidcConfig: OidcConfig,
    private val scope: CoroutineScope,
    private val loggerFactory: LoggerFactory,
) : AuthStore {

    private val logger by loggerFactory

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val authService = AuthorizationService(context)

    private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    private var appAuthState: net.openid.appauth.AuthState? = null

    init {
        scope.launch {
            loadAuthState()
        }
    }

    private suspend fun loadAuthState() {
        val stateJson = encryptedPrefs.getString(KEY_AUTH_STATE, null)
        if (stateJson != null) {
            try {
                appAuthState = net.openid.appauth.AuthState.jsonDeserialize(stateJson)
                val email = extractEmail(appAuthState)
                mutableAuthState.value = AuthState.Authenticated(email ?: "Unknown")
            } catch (e: Exception) {
                logger.error("Failed to load auth state", e)
                mutableAuthState.value = AuthState.NotAuthenticated
            }
        } else {
            mutableAuthState.value = AuthState.NotAuthenticated
        }
    }

    override suspend fun getAccessToken(): String? {
        val state = appAuthState ?: return null

        return suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                if (ex != null) {
                    logger.error("Token refresh failed", ex)
                    cont.resume(null)
                } else {
                    saveAuthState(state)
                    cont.resume(accessToken)
                }
            }
        }
    }

    override suspend fun logout() {
        appAuthState = null
        encryptedPrefs.edit().remove(KEY_AUTH_STATE).apply()
        mutableAuthState.value = AuthState.NotAuthenticated
    }

    fun handleAuthResponse(response: AuthorizationResponse?, exception: AuthorizationException?) {
        // Handle OAuth callback...
    }

    private fun saveAuthState(state: net.openid.appauth.AuthState) {
        encryptedPrefs.edit()
            .putString(KEY_AUTH_STATE, state.jsonSerializeString())
            .apply()
    }

    companion object {
        private const val KEY_AUTH_STATE = "auth_state"
    }
}
```

### 7.2 Login Screen with AppAuth

```kotlin
// ui/src/.../screen/login/LoginViewModel.kt
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel(), LoginScreenActions {

    private val mutableState = MutableStateFlow(LoginScreenState())
    val state = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<LoginScreenEvents>()
    val events = mutableEvents.asSharedFlow()

    init {
        mutableState.value = mutableState.value.copy(
            serverUrl = interactor.getInitialServerUrl()
        )
    }

    override fun onServerUrlChanged(url: String) {
        mutableState.value = mutableState.value.copy(
            serverUrl = url,
            serverUrlError = null,
        )
    }

    override fun onLoginClick() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true)

            when (interactor.setServerUrl(mutableState.value.serverUrl)) {
                is ConfigStore.SetServerUrlResult.InvalidUrl -> {
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        serverUrlError = "Invalid URL",
                    )
                    return@launch
                }
                is ConfigStore.SetServerUrlResult.Success -> {}
            }

            val authIntent = interactor.createAuthIntent()
            mutableEvents.emit(LoginScreenEvents.LaunchAuth(authIntent))
        }
    }

    fun onAuthResult(result: AuthResult) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = false)
            when (result) {
                is AuthResult.Success -> mutableEvents.emit(LoginScreenEvents.NavigateToMain)
                is AuthResult.Cancelled -> {} // Do nothing
                is AuthResult.Error -> {
                    mutableState.value = mutableState.value.copy(
                        error = result.message
                    )
                }
            }
        }
    }

    interface Interactor {
        fun getInitialServerUrl(): String
        suspend fun setServerUrl(url: String): ConfigStore.SetServerUrlResult
        fun createAuthIntent(): Intent
    }
}

// LoginScreenState.kt
data class LoginScreenState(
    val serverUrl: String = "",
    val serverUrlError: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

// LoginScreenEvents.kt
sealed interface LoginScreenEvents {
    data class LaunchAuth(val intent: Intent) : LoginScreenEvents
    data object NavigateToMain : LoginScreenEvents
}
```

### 7.3 MainActivity Integration

```kotlin
// app/src/.../MainActivity.kt
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authStore: AuthStore
    @Inject lateinit var userPreferencesStore: UserPreferencesStore

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Handle auth result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            val themeMode by userPreferencesStore.themeMode.collectAsState()

            AppUi(themeMode = themeMode)
        }
    }
}
```

### 7.4 Unit Tests - LoginViewModel

```kotlin
// ui/src/test/java/com/lelloman/store/ui/screen/login/LoginViewModelTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeInteractor: FakeLoginInteractor
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeInteractor = FakeLoginInteractor()
        viewModel = LoginViewModel(fakeInteractor)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has server URL from interactor`() {
        fakeInteractor.initialServerUrl = "http://initial.example.com"
        viewModel = LoginViewModel(fakeInteractor)

        assertThat(viewModel.state.value.serverUrl).isEqualTo("http://initial.example.com")
    }

    @Test
    fun `onServerUrlChanged updates state`() {
        viewModel.onServerUrlChanged("http://new.example.com")

        assertThat(viewModel.state.value.serverUrl).isEqualTo("http://new.example.com")
        assertThat(viewModel.state.value.serverUrlError).isNull()
    }

    @Test
    fun `onLoginClick with invalid URL shows error`() = runTest {
        fakeInteractor.setServerUrlResult = ConfigStore.SetServerUrlResult.InvalidUrl
        viewModel.onServerUrlChanged("invalid-url")

        viewModel.onLoginClick()
        advanceUntilIdle()

        assertThat(viewModel.state.value.serverUrlError).isNotNull()
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun `onLoginClick with valid URL emits LaunchAuth event`() = runTest {
        fakeInteractor.setServerUrlResult = ConfigStore.SetServerUrlResult.Success
        viewModel.onServerUrlChanged("http://valid.example.com")

        viewModel.events.test {
            viewModel.onLoginClick()
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isInstanceOf(LoginScreenEvents.LaunchAuth::class.java)
        }
    }

    @Test
    fun `onAuthResult Success emits NavigateToMain`() = runTest {
        viewModel.events.test {
            viewModel.onAuthResult(AuthResult.Success)
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isEqualTo(LoginScreenEvents.NavigateToMain)
        }
    }

    @Test
    fun `onAuthResult Error updates state with error message`() = runTest {
        viewModel.onAuthResult(AuthResult.Error("Login failed"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Login failed")
    }
}

// Test double
class FakeLoginInteractor : LoginViewModel.Interactor {
    var initialServerUrl = "http://default.example.com"
    var setServerUrlResult: ConfigStore.SetServerUrlResult = ConfigStore.SetServerUrlResult.Success

    override fun getInitialServerUrl() = initialServerUrl
    override suspend fun setServerUrl(url: String) = setServerUrlResult
    override fun createAuthIntent() = Intent()
}
```

### 7.5 Verification

- [ ] Login screen shows server URL field
- [ ] Login button launches OIDC flow
- [ ] Successful login navigates to Catalog
- [ ] Token is stored and survives app restart
- [ ] Logout clears tokens
- [ ] `./gradlew :ui:test` passes (LoginViewModel tests)

---

## Phase 8: Catalog & Detail Screens

**Goal**: Implement app browsing functionality.

### 8.1 Catalog Screen

```kotlin
// ui/src/.../screen/main/catalog/CatalogViewModel.kt
@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel(), CatalogScreenActions {

    private val mutableState = MutableStateFlow(CatalogScreenState())
    val state = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<CatalogScreenEvents>()
    val events = mutableEvents.asSharedFlow()

    init {
        observeApps()
        refresh()
    }

    private fun observeApps() {
        viewModelScope.launch {
            combine(
                interactor.watchApps(),
                interactor.watchInstalledApps(),
                mutableState.map { it.filter },
                mutableState.map { it.searchQuery },
            ) { apps, installed, filter, query ->
                applyFilterAndSearch(apps, installed, filter, query)
            }.collect { filteredApps ->
                mutableState.value = mutableState.value.copy(apps = filteredApps)
            }
        }
    }

    override fun onRefresh() {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)
            interactor.refreshApps()
                .onSuccess {
                    mutableState.value = mutableState.value.copy(isLoading = false)
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        error = it.message,
                    )
                }
        }
    }

    override fun onSearchQueryChanged(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query)
    }

    override fun onFilterChanged(filter: CatalogFilter) {
        mutableState.value = mutableState.value.copy(filter = filter)
    }

    override fun onAppClicked(app: AppUiModel) {
        viewModelScope.launch {
            mutableEvents.emit(CatalogScreenEvents.NavigateToAppDetail(app.packageName))
        }
    }

    interface Interactor {
        fun watchApps(): Flow<List<App>>
        fun watchInstalledApps(): Flow<List<InstalledApp>>
        suspend fun refreshApps(): Result<Unit>
    }
}
```

### 8.2 App Detail Screen

```kotlin
// ui/src/.../screen/main/appdetail/AppDetailViewModel.kt
@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val interactor: Interactor,
) : ViewModel(), AppDetailScreenActions {

    private val packageName: String = savedStateHandle.toRoute<Screen.Main.AppDetail>().packageName

    private val mutableState = MutableStateFlow(AppDetailScreenState())
    val state = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AppDetailScreenEvents>()
    val events = mutableEvents.asSharedFlow()

    init {
        observeApp()
        observeInstalled()
        observeDownload()
        refresh()
    }

    private fun observeApp() {
        viewModelScope.launch {
            interactor.watchApp(packageName).collect { app ->
                mutableState.value = mutableState.value.copy(app = app)
            }
        }
    }

    private fun observeInstalled() {
        viewModelScope.launch {
            interactor.watchInstalledVersion(packageName).collect { installed ->
                mutableState.value = mutableState.value.copy(installedVersion = installed)
            }
        }
    }

    private fun observeDownload() {
        viewModelScope.launch {
            interactor.watchDownloadProgress(packageName).collect { progress ->
                mutableState.value = mutableState.value.copy(downloadProgress = progress)
            }
        }
    }

    override fun onInstallClick(versionCode: Int) {
        viewModelScope.launch {
            interactor.downloadAndInstall(packageName, versionCode)
        }
    }

    override fun onCancelDownload() {
        interactor.cancelDownload(packageName)
    }

    interface Interactor {
        fun watchApp(packageName: String): Flow<AppDetail?>
        fun watchInstalledVersion(packageName: String): Flow<InstalledApp?>
        fun watchDownloadProgress(packageName: String): Flow<DownloadProgress?>
        suspend fun refreshApp(packageName: String): Result<AppDetail>
        suspend fun downloadAndInstall(packageName: String, versionCode: Int)
        fun cancelDownload(packageName: String)
    }
}
```

### 8.3 Apps Repository Implementation

```kotlin
// app/src/.../repository/AppsRepositoryImpl.kt
class AppsRepositoryImpl @Inject constructor(
    private val remoteApiClient: RemoteApiClient,
    private val appsDao: AppsDao,
    private val appVersionsDao: AppVersionsDao,
    private val loggerFactory: LoggerFactory,
) : AppsRepository {

    private val logger by loggerFactory

    override fun watchApps(): Flow<List<App>> {
        return appsDao.watchApps().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun refreshApps(): Result<Unit> = runCatching {
        val apps = remoteApiClient.getApps().getOrThrow()
        appsDao.insertApps(apps.map { it.toEntity() })
    }

    // ... other methods
}
```

### 8.4 Unit Tests - CatalogViewModel

```kotlin
// ui/src/test/java/com/lelloman/store/ui/screen/main/catalog/CatalogViewModelTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
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

    @Test
    fun `initial state is loading`() {
        viewModel = CatalogViewModel(fakeInteractor)
        assertThat(viewModel.state.value.isLoading).isTrue()
    }

    @Test
    fun `apps from interactor are displayed`() = testScope.runTest {
        val apps = listOf(
            createTestApp("com.example.app1", "App 1"),
            createTestApp("com.example.app2", "App 2"),
        )
        fakeInteractor.apps.value = apps

        viewModel = CatalogViewModel(fakeInteractor)
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps).hasSize(2)
    }

    @Test
    fun `onSearchQueryChanged filters apps by name`() = testScope.runTest {
        fakeInteractor.apps.value = listOf(
            createTestApp("com.example.app1", "Calculator"),
            createTestApp("com.example.app2", "Calendar"),
            createTestApp("com.example.app3", "Notes"),
        )
        viewModel = CatalogViewModel(fakeInteractor)
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("Cal")
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps.map { it.name }).containsExactly("Calculator", "Calendar")
    }

    @Test
    fun `onFilterChanged to Installed shows only installed apps`() = testScope.runTest {
        fakeInteractor.apps.value = listOf(
            createTestApp("com.example.installed", "Installed App"),
            createTestApp("com.example.notinstalled", "Not Installed App"),
        )
        fakeInteractor.installedApps.value = listOf(
            InstalledApp("com.example.installed", 1, "1.0.0")
        )
        viewModel = CatalogViewModel(fakeInteractor)
        advanceUntilIdle()

        viewModel.onFilterChanged(CatalogFilter.Installed)
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps).hasSize(1)
        assertThat(viewModel.state.value.apps[0].name).isEqualTo("Installed App")
    }

    @Test
    fun `onFilterChanged to NotInstalled shows only not installed apps`() = testScope.runTest {
        fakeInteractor.apps.value = listOf(
            createTestApp("com.example.installed", "Installed App"),
            createTestApp("com.example.notinstalled", "Not Installed App"),
        )
        fakeInteractor.installedApps.value = listOf(
            InstalledApp("com.example.installed", 1, "1.0.0")
        )
        viewModel = CatalogViewModel(fakeInteractor)
        advanceUntilIdle()

        viewModel.onFilterChanged(CatalogFilter.NotInstalled)
        advanceUntilIdle()

        assertThat(viewModel.state.value.apps).hasSize(1)
        assertThat(viewModel.state.value.apps[0].name).isEqualTo("Not Installed App")
    }

    @Test
    fun `onRefresh calls interactor refreshApps`() = testScope.runTest {
        viewModel = CatalogViewModel(fakeInteractor)
        advanceUntilIdle()
        fakeInteractor.refreshAppsCalled = false

        viewModel.onRefresh()
        advanceUntilIdle()

        assertThat(fakeInteractor.refreshAppsCalled).isTrue()
    }

    @Test
    fun `onRefresh failure updates error state`() = testScope.runTest {
        fakeInteractor.refreshAppsResult = Result.failure(Exception("Network error"))
        viewModel = CatalogViewModel(fakeInteractor)
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNotNull()
    }

    @Test
    fun `onAppClicked emits NavigateToAppDetail event`() = testScope.runTest {
        viewModel = CatalogViewModel(fakeInteractor)
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onAppClicked(AppUiModel("com.example.app", "App", "", false, false))

            val event = awaitItem()
            assertThat(event).isInstanceOf(CatalogScreenEvents.NavigateToAppDetail::class.java)
            assertThat((event as CatalogScreenEvents.NavigateToAppDetail).packageName)
                .isEqualTo("com.example.app")
        }
    }

    private fun createTestApp(packageName: String, name: String) = App(
        packageName = packageName,
        name = name,
        description = null,
        iconUrl = "",
        latestVersion = AppVersion(1, "1.0.0", 0, "", 26, Instant.DISTANT_PAST),
    )
}

// Test double
class FakeCatalogInteractor : CatalogViewModel.Interactor {
    val apps = MutableStateFlow<List<App>>(emptyList())
    val installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    var refreshAppsCalled = false
    var refreshAppsResult: Result<Unit> = Result.success(Unit)

    override fun watchApps() = apps.asStateFlow()
    override fun watchInstalledApps() = installedApps.asStateFlow()
    override suspend fun refreshApps(): Result<Unit> {
        refreshAppsCalled = true
        return refreshAppsResult
    }
}
```

### 8.5 Unit Tests - AppDetailViewModel

```kotlin
// ui/src/test/java/com/lelloman/store/ui/screen/main/appdetail/AppDetailViewModelTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakeInteractor: FakeAppDetailInteractor
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: AppDetailViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeInteractor = FakeAppDetailInteractor()
        savedStateHandle = SavedStateHandle(mapOf("packageName" to "com.example.app"))
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads app detail on init`() = testScope.runTest {
        val appDetail = AppDetail(
            packageName = "com.example.app",
            name = "Test App",
            description = "Description",
            iconUrl = "/icon.png",
            versions = listOf(
                AppVersion(2, "2.0.0", 2000, "sha256", 26, Instant.DISTANT_PAST),
                AppVersion(1, "1.0.0", 1000, "sha256", 26, Instant.DISTANT_PAST),
            ),
        )
        fakeInteractor.appDetail.value = appDetail

        viewModel = AppDetailViewModel(savedStateHandle, fakeInteractor)
        advanceUntilIdle()

        assertThat(viewModel.state.value.app?.name).isEqualTo("Test App")
        assertThat(viewModel.state.value.app?.versions).hasSize(2)
    }

    @Test
    fun `shows installed version when app is installed`() = testScope.runTest {
        fakeInteractor.installedVersion.value = InstalledApp("com.example.app", 1, "1.0.0")

        viewModel = AppDetailViewModel(savedStateHandle, fakeInteractor)
        advanceUntilIdle()

        assertThat(viewModel.state.value.installedVersion?.versionCode).isEqualTo(1)
    }

    @Test
    fun `onInstallClick calls interactor downloadAndInstall`() = testScope.runTest {
        viewModel = AppDetailViewModel(savedStateHandle, fakeInteractor)
        advanceUntilIdle()

        viewModel.onInstallClick(2)
        advanceUntilIdle()

        assertThat(fakeInteractor.downloadCalled).isTrue()
        assertThat(fakeInteractor.downloadPackageName).isEqualTo("com.example.app")
        assertThat(fakeInteractor.downloadVersionCode).isEqualTo(2)
    }

    @Test
    fun `download progress is reflected in state`() = testScope.runTest {
        viewModel = AppDetailViewModel(savedStateHandle, fakeInteractor)
        advanceUntilIdle()

        fakeInteractor.downloadProgress.value = DownloadProgress(
            packageName = "com.example.app",
            progress = 0.5f,
            bytesDownloaded = 500,
            totalBytes = 1000,
            state = DownloadState.DOWNLOADING,
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.downloadProgress?.progress).isEqualTo(0.5f)
        assertThat(viewModel.state.value.downloadProgress?.state).isEqualTo(DownloadState.DOWNLOADING)
    }

    @Test
    fun `onCancelDownload calls interactor cancelDownload`() = testScope.runTest {
        viewModel = AppDetailViewModel(savedStateHandle, fakeInteractor)
        advanceUntilIdle()

        viewModel.onCancelDownload()

        assertThat(fakeInteractor.cancelDownloadCalled).isTrue()
    }
}

// Test double
class FakeAppDetailInteractor : AppDetailViewModel.Interactor {
    val appDetail = MutableStateFlow<AppDetail?>(null)
    val installedVersion = MutableStateFlow<InstalledApp?>(null)
    val downloadProgress = MutableStateFlow<DownloadProgress?>(null)

    var downloadCalled = false
    var downloadPackageName: String? = null
    var downloadVersionCode: Int? = null
    var cancelDownloadCalled = false

    override fun watchApp(packageName: String) = appDetail.asStateFlow()
    override fun watchInstalledVersion(packageName: String) = installedVersion.asStateFlow()
    override fun watchDownloadProgress(packageName: String) = downloadProgress.asStateFlow()
    override suspend fun refreshApp(packageName: String) = Result.success(appDetail.value!!)
    override suspend fun downloadAndInstall(packageName: String, versionCode: Int) {
        downloadCalled = true
        downloadPackageName = packageName
        downloadVersionCode = versionCode
    }
    override fun cancelDownload(packageName: String) {
        cancelDownloadCalled = true
    }
}
```

### 8.6 Unit Tests - AppsRepository

```kotlin
// app/src/test/java/com/lelloman/store/repository/AppsRepositoryImplTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class AppsRepositoryImplTest {

    private val testScope = TestScope()
    private lateinit var fakeApiClient: FakeRemoteApiClient
    private lateinit var fakeAppsDao: FakeAppsDao
    private lateinit var repository: AppsRepositoryImpl

    @Before
    fun setup() {
        fakeApiClient = FakeRemoteApiClient()
        fakeAppsDao = FakeAppsDao()
        repository = AppsRepositoryImpl(
            remoteApiClient = fakeApiClient,
            appsDao = fakeAppsDao,
            appVersionsDao = FakeAppVersionsDao(),
            loggerFactory = ConsoleLoggerFactory(),
        )
    }

    @Test
    fun `watchApps returns apps from DAO`() = testScope.runTest {
        fakeAppsDao.apps.value = listOf(
            createTestEntity("com.example.app1"),
            createTestEntity("com.example.app2"),
        )

        val apps = repository.watchApps().first()

        assertThat(apps).hasSize(2)
    }

    @Test
    fun `refreshApps fetches from API and inserts into DAO`() = testScope.runTest {
        fakeApiClient.apps = listOf(
            createTestDto("com.example.app1"),
            createTestDto("com.example.app2"),
        )

        val result = repository.refreshApps()

        assertThat(result.isSuccess).isTrue()
        assertThat(fakeAppsDao.insertedApps).hasSize(2)
    }

    @Test
    fun `refreshApps returns failure when API fails`() = testScope.runTest {
        fakeApiClient.shouldFail = true

        val result = repository.refreshApps()

        assertThat(result.isFailure).isTrue()
    }
}
```

### 8.7 Verification

- [ ] Catalog shows apps from server
- [ ] Pull-to-refresh works
- [ ] Filter chips filter the list
- [ ] Search works
- [ ] Tapping app navigates to detail
- [ ] Detail shows app info and versions
- [ ] Install button shown for not-installed apps
- [ ] Update button shown for outdated apps
- [ ] `./gradlew :ui:test` passes (CatalogViewModel, AppDetailViewModel tests)
- [ ] `./gradlew :app:test` passes (Repository tests)

---

## Phase 9: Download & Install

**Goal**: Implement APK download and installation.

### 9.1 Download Manager Implementation

```kotlin
// app/src/.../download/DownloadManagerImpl.kt
class DownloadManagerImpl @Inject constructor(
    private val context: Context,
    private val remoteApiClient: RemoteApiClient,
    private val loggerFactory: LoggerFactory,
) : DownloadManager {

    private val logger by loggerFactory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableActiveDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val activeDownloads = mutableActiveDownloads.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    override suspend fun downloadAndInstall(
        packageName: String,
        versionCode: Int,
    ): DownloadResult {
        if (downloadJobs.containsKey(packageName)) {
            return DownloadResult.Failed("Download already in progress")
        }

        val job = scope.launch {
            try {
                updateProgress(packageName, DownloadState.DOWNLOADING, 0f, 0, 0)

                val destination = File(context.cacheDir, "$packageName-$versionCode.apk")

                remoteApiClient.downloadApk(
                    packageName,
                    versionCode,
                    destination
                ) { received, total ->
                    val progress = if (total > 0) received.toFloat() / total else 0f
                    updateProgress(packageName, DownloadState.DOWNLOADING, progress, received, total)
                }.getOrThrow()

                updateProgress(packageName, DownloadState.VERIFYING, 1f, 0, 0)
                // TODO: Verify SHA-256

                updateProgress(packageName, DownloadState.INSTALLING, 1f, 0, 0)
                installApk(destination)

                updateProgress(packageName, DownloadState.COMPLETED, 1f, 0, 0)

            } catch (e: CancellationException) {
                updateProgress(packageName, DownloadState.CANCELLED, 0f, 0, 0)
                throw e
            } catch (e: Exception) {
                logger.error("Download failed", e)
                updateProgress(packageName, DownloadState.FAILED, 0f, 0, 0)
            } finally {
                downloadJobs.remove(packageName)
                // Clear progress after delay
                delay(3000)
                mutableActiveDownloads.update { it - packageName }
            }
        }

        downloadJobs[packageName] = job
        job.join()

        return when (activeDownloads.value[packageName]?.state) {
            DownloadState.COMPLETED -> DownloadResult.Success
            DownloadState.CANCELLED -> DownloadResult.Cancelled
            else -> DownloadResult.Failed("Download failed")
        }
    }

    override fun cancelDownload(packageName: String) {
        downloadJobs[packageName]?.cancel()
    }

    private fun updateProgress(
        packageName: String,
        state: DownloadState,
        progress: Float,
        bytesDownloaded: Long,
        totalBytes: Long,
    ) {
        mutableActiveDownloads.update { current ->
            current + (packageName to DownloadProgress(
                packageName, progress, bytesDownloaded, totalBytes, state
            ))
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }
}
```

### 9.2 FileProvider Configuration

```xml
<!-- app/src/main/res/xml/file_paths.xml -->
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="apks" path="." />
</paths>

<!-- In AndroidManifest.xml -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

### 9.3 Install Permission Handling

```kotlin
// Check and request REQUEST_INSTALL_PACKAGES permission
if (!context.packageManager.canRequestPackageInstalls()) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    // Launch intent to settings
}
```

### 9.4 Verification

- [ ] Download shows progress
- [ ] Can cancel download
- [ ] APK is verified (SHA-256)
- [ ] Package installer launches
- [ ] Installed apps are detected

---

## Phase 10: Updates & Background

**Goal**: Implement update detection and background checking.

### 10.1 Update Checker Implementation

```kotlin
// app/src/.../updates/UpdateCheckerImpl.kt
class UpdateCheckerImpl @Inject constructor(
    private val appsRepository: AppsRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val scope: CoroutineScope,
) : UpdateChecker {

    private val mutableUpdates = MutableStateFlow<List<AvailableUpdate>>(emptyList())
    override val availableUpdates = mutableUpdates.asStateFlow()

    init {
        scope.launch {
            combine(
                appsRepository.watchApps(),
                installedAppsRepository.watchInstalledApps(),
            ) { apps, installed ->
                findUpdates(apps, installed)
            }.collect { updates ->
                mutableUpdates.value = updates
            }
        }
    }

    override suspend fun checkForUpdates(): Result<List<AvailableUpdate>> {
        return runCatching {
            appsRepository.refreshApps().getOrThrow()
            installedAppsRepository.refreshInstalledApps()
            availableUpdates.value
        }
    }

    private fun findUpdates(
        apps: List<App>,
        installed: List<InstalledApp>,
    ): List<AvailableUpdate> {
        val installedMap = installed.associateBy { it.packageName }

        return apps.mapNotNull { app ->
            val installedApp = installedMap[app.packageName] ?: return@mapNotNull null

            if (app.latestVersion.versionCode > installedApp.versionCode) {
                AvailableUpdate(
                    app = app,
                    installedVersionCode = installedApp.versionCode,
                    installedVersionName = installedApp.versionName,
                )
            } else null
        }
    }
}
```

### 10.2 WorkManager Worker

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
        val updates = updateChecker.checkForUpdates().getOrNull()
            ?: return Result.retry()

        if (updates.isNotEmpty()) {
            notificationHelper.showUpdatesAvailable(updates.size)
        }

        return Result.success()
    }
}
```

### 10.3 WorkManager Configuration

```kotlin
// app/src/.../work/WorkManagerInitializer.kt
@Singleton
class WorkManagerInitializer @Inject constructor(
    private val context: Context,
    private val userPreferencesStore: UserPreferencesStore,
) {
    fun scheduleUpdateChecks() {
        val interval = when (userPreferencesStore.updateCheckInterval.value) {
            UpdateCheckInterval.Hours6 -> 6L
            UpdateCheckInterval.Hours12 -> 12L
            UpdateCheckInterval.Hours24 -> 24L
            UpdateCheckInterval.Manual -> return // Don't schedule
        }

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            interval, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "update_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
```

### 10.4 Notifications

```kotlin
// app/src/.../notifications/NotificationHelper.kt
class NotificationHelper @Inject constructor(
    private val context: Context,
) {
    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "App update notifications"
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun showUpdatesAvailable(count: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("destination", "updates")
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Updates Available")
            .setContentText("$count app${if (count > 1) "s have" else " has"} updates")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 1
    }
}
```

### 10.5 Updates Screen

```kotlin
// ui/src/.../screen/main/updates/UpdatesViewModel.kt
@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel(), UpdatesScreenActions {

    private val mutableState = MutableStateFlow(UpdatesScreenState())
    val state = mutableState.asStateFlow()

    init {
        observeUpdates()
    }

    private fun observeUpdates() {
        viewModelScope.launch {
            interactor.watchUpdates().collect { updates ->
                mutableState.value = mutableState.value.copy(updates = updates)
            }
        }
    }

    override fun onCheckNow() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isChecking = true)
            interactor.checkForUpdates()
            mutableState.value = mutableState.value.copy(isChecking = false)
        }
    }

    override fun onUpdateClick(packageName: String) {
        // Navigate to detail or start download
    }

    override fun onUpdateAllClick() {
        viewModelScope.launch {
            state.value.updates.forEach { update ->
                interactor.downloadAndInstall(
                    update.app.packageName,
                    update.app.latestVersion.versionCode
                )
            }
        }
    }

    interface Interactor {
        fun watchUpdates(): Flow<List<AvailableUpdate>>
        suspend fun checkForUpdates(): Result<List<AvailableUpdate>>
        suspend fun downloadAndInstall(packageName: String, versionCode: Int)
    }
}
```

### 10.6 Settings Screen

```kotlin
// ui/src/.../screen/main/settings/SettingsViewModel.kt
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel(), SettingsScreenActions {

    private val mutableState = MutableStateFlow(SettingsScreenState())
    val state = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<SettingsScreenEvents>()
    val events = mutableEvents.asSharedFlow()

    init {
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            combine(
                interactor.watchServerUrl(),
                interactor.watchThemeMode(),
                interactor.watchUpdateCheckInterval(),
                interactor.watchWifiOnlyDownloads(),
            ) { serverUrl, theme, interval, wifiOnly ->
                SettingsScreenState(
                    serverUrl = serverUrl,
                    serverUrlInput = serverUrl,
                    themeMode = theme,
                    updateCheckInterval = interval,
                    wifiOnlyDownloads = wifiOnly,
                )
            }.collect { mutableState.value = it }
        }
    }

    override fun onServerUrlInputChanged(url: String) {
        mutableState.value = mutableState.value.copy(
            serverUrlInput = url,
            serverUrlError = null,
        )
    }

    override fun onSaveServerUrl() {
        viewModelScope.launch {
            val result = interactor.setServerUrl(mutableState.value.serverUrlInput)
            when (result) {
                is ConfigStore.SetServerUrlResult.Success -> {
                    // Logout since server changed
                    mutableEvents.emit(SettingsScreenEvents.ServerChangedLogout)
                }
                is ConfigStore.SetServerUrlResult.InvalidUrl -> {
                    mutableState.value = mutableState.value.copy(
                        serverUrlError = "Invalid URL"
                    )
                }
            }
        }
    }

    override fun onThemeModeChanged(mode: ThemeMode) {
        viewModelScope.launch {
            interactor.setThemeMode(mode)
        }
    }

    override fun onUpdateIntervalChanged(interval: UpdateCheckInterval) {
        viewModelScope.launch {
            interactor.setUpdateCheckInterval(interval)
        }
    }

    override fun onWifiOnlyChanged(enabled: Boolean) {
        viewModelScope.launch {
            interactor.setWifiOnlyDownloads(enabled)
        }
    }

    interface Interactor {
        fun watchServerUrl(): Flow<String>
        fun watchThemeMode(): Flow<ThemeMode>
        fun watchUpdateCheckInterval(): Flow<UpdateCheckInterval>
        fun watchWifiOnlyDownloads(): Flow<Boolean>
        suspend fun setServerUrl(url: String): ConfigStore.SetServerUrlResult
        suspend fun setThemeMode(mode: ThemeMode)
        suspend fun setUpdateCheckInterval(interval: UpdateCheckInterval)
        suspend fun setWifiOnlyDownloads(enabled: Boolean)
    }
}
```

### 10.7 Unit Tests - UpdatesViewModel & SettingsViewModel

```kotlin
// ui/src/test/java/com/lelloman/store/ui/screen/main/updates/UpdatesViewModelTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakeInteractor: FakeUpdatesInteractor
    private lateinit var viewModel: UpdatesViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeInteractor = FakeUpdatesInteractor()
        viewModel = UpdatesViewModel(fakeInteractor)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `displays available updates from interactor`() = testScope.runTest {
        fakeInteractor.updates.value = listOf(
            AvailableUpdate(
                app = createTestApp("com.example.app1"),
                installedVersionCode = 1,
                installedVersionName = "1.0.0",
            )
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.updates).hasSize(1)
    }

    @Test
    fun `onCheckNow triggers update check`() = testScope.runTest {
        viewModel.onCheckNow()
        advanceUntilIdle()

        assertThat(fakeInteractor.checkForUpdatesCalled).isTrue()
    }

    @Test
    fun `onCheckNow shows loading state`() = testScope.runTest {
        viewModel.onCheckNow()

        assertThat(viewModel.state.value.isChecking).isTrue()

        advanceUntilIdle()

        assertThat(viewModel.state.value.isChecking).isFalse()
    }
}

// ui/src/test/java/com/lelloman/store/ui/screen/main/settings/SettingsViewModelTest.kt
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakeInteractor: FakeSettingsInteractor
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeInteractor = FakeSettingsInteractor()
        viewModel = SettingsViewModel(fakeInteractor)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `displays current settings from interactor`() = testScope.runTest {
        fakeInteractor.serverUrl.value = "http://current.example.com"
        fakeInteractor.themeMode.value = ThemeMode.Dark
        advanceUntilIdle()

        assertThat(viewModel.state.value.serverUrl).isEqualTo("http://current.example.com")
        assertThat(viewModel.state.value.themeMode).isEqualTo(ThemeMode.Dark)
    }

    @Test
    fun `onServerUrlInputChanged updates input field only`() = testScope.runTest {
        fakeInteractor.serverUrl.value = "http://original.example.com"
        advanceUntilIdle()

        viewModel.onServerUrlInputChanged("http://new.example.com")

        assertThat(viewModel.state.value.serverUrlInput).isEqualTo("http://new.example.com")
        assertThat(viewModel.state.value.serverUrl).isEqualTo("http://original.example.com")
    }

    @Test
    fun `onSaveServerUrl with valid URL emits logout event`() = testScope.runTest {
        fakeInteractor.setServerUrlResult = ConfigStore.SetServerUrlResult.Success
        viewModel.onServerUrlInputChanged("http://new.example.com")

        viewModel.events.test {
            viewModel.onSaveServerUrl()
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isEqualTo(SettingsScreenEvents.ServerChangedLogout)
        }
    }

    @Test
    fun `onSaveServerUrl with invalid URL shows error`() = testScope.runTest {
        fakeInteractor.setServerUrlResult = ConfigStore.SetServerUrlResult.InvalidUrl
        viewModel.onServerUrlInputChanged("invalid")

        viewModel.onSaveServerUrl()
        advanceUntilIdle()

        assertThat(viewModel.state.value.serverUrlError).isNotNull()
    }

    @Test
    fun `onThemeModeChanged calls interactor`() = testScope.runTest {
        viewModel.onThemeModeChanged(ThemeMode.Light)
        advanceUntilIdle()

        assertThat(fakeInteractor.setThemeModeCalled).isTrue()
        assertThat(fakeInteractor.lastThemeMode).isEqualTo(ThemeMode.Light)
    }
}
```

### 10.8 Verification

- [ ] Updates screen shows available updates
- [ ] "Check Now" button works
- [ ] "Update All" downloads all updates
- [ ] Background worker runs on schedule
- [ ] Notification appears when updates found
- [ ] Tapping notification opens Updates screen
- [ ] Settings persist and apply
- [ ] `./gradlew :ui:test` passes (UpdatesViewModel, SettingsViewModel tests)

---

## Phase 11: E2E Instrumented Tests

**Goal**: Full app instrumented tests with MockWebServer - testing the complete user journey with only the backend mocked.

### 11.1 Test Infrastructure

```kotlin
// app/src/androidTest/java/com/lelloman/store/TestRunner.kt
class LellostoreTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, name: String, context: Context): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}

// Update app/build.gradle.kts
android {
    defaultConfig {
        testInstrumentationRunner = "com.lelloman.store.TestRunner"
    }
}
```

### 11.2 MockWebServer Setup

```kotlin
// app/src/androidTest/java/com/lelloman/store/e2e/MockServerRule.kt
class MockServerRule : TestWatcher() {

    lateinit var mockWebServer: MockWebServer
    val baseUrl: String get() = mockWebServer.url("/").toString()

    override fun starting(description: Description) {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    override fun finished(description: Description) {
        mockWebServer.shutdown()
    }

    fun enqueueAppsResponse(apps: List<MockApp>) {
        val json = buildAppsJson(apps)
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(json))
    }

    fun enqueueAppDetailResponse(app: MockAppDetail) {
        val json = buildAppDetailJson(app)
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(json))
    }

    fun enqueueApkDownload(apkBytes: ByteArray) {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/vnd.android.package-archive")
            .setBody(Buffer().write(apkBytes)))
    }

    fun enqueueError(code: Int, message: String) {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(code)
            .setBody("""{"error": "error", "message": "$message"}"""))
    }
}

data class MockApp(
    val packageName: String,
    val name: String,
    val description: String? = null,
    val versionCode: Int = 1,
    val versionName: String = "1.0.0",
    val size: Long = 1000,
)

data class MockAppDetail(
    val packageName: String,
    val name: String,
    val description: String? = null,
    val versions: List<MockAppVersion> = emptyList(),
)

data class MockAppVersion(
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val sha256: String,
)
```

### 11.3 Test Hilt Module

```kotlin
// app/src/androidTest/java/com/lelloman/store/e2e/TestModule.kt
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DomainModule::class]
)
class TestModule {

    @Provides
    @DefaultServerUrl
    fun provideTestServerUrl(): String = "http://localhost:8080"  // Will be overridden per test

    // Provide test doubles for auth if needed
}

// For injecting the mock server URL into tests
@Module
@InstallIn(SingletonComponent::class)
object TestConfigModule {
    private var testServerUrl: String = "http://localhost:8080"

    fun setServerUrl(url: String) {
        testServerUrl = url
    }

    @Provides
    @TestServerUrl
    fun provideTestServerUrl(): String = testServerUrl
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TestServerUrl
```

### 11.4 E2E Test - Complete User Journey

```kotlin
// app/src/androidTest/java/com/lelloman/store/e2e/CatalogE2ETest.kt
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CatalogE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val mockServerRule = MockServerRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var configStore: ConfigStore

    @Before
    fun setup() {
        hiltRule.inject()
        runBlocking {
            configStore.setServerUrl(mockServerRule.baseUrl)
        }
    }

    @Test
    fun catalogScreen_displaysAppsFromServer() {
        // Given: Server returns list of apps
        mockServerRule.enqueueAppsResponse(listOf(
            MockApp("com.example.app1", "Calculator"),
            MockApp("com.example.app2", "Calendar"),
            MockApp("com.example.app3", "Notes"),
        ))

        // When: App launches and catalog loads
        // (Assuming user is already logged in or we skip auth for this test)

        // Then: Apps are displayed
        composeRule.onNodeWithText("Calculator").assertIsDisplayed()
        composeRule.onNodeWithText("Calendar").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").assertIsDisplayed()
    }

    @Test
    fun catalogScreen_searchFiltersApps() {
        // Given: Server returns list of apps
        mockServerRule.enqueueAppsResponse(listOf(
            MockApp("com.example.app1", "Calculator"),
            MockApp("com.example.app2", "Calendar"),
            MockApp("com.example.app3", "Notes"),
        ))

        // When: User types in search
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNodeWithTag("SearchField").performTextInput("Cal")

        // Then: Only matching apps are shown
        composeRule.onNodeWithText("Calculator").assertIsDisplayed()
        composeRule.onNodeWithText("Calendar").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").assertDoesNotExist()
    }

    @Test
    fun catalogScreen_filterByInstalled() {
        // Given: Server returns apps, one is installed locally
        mockServerRule.enqueueAppsResponse(listOf(
            MockApp("com.example.installed", "Installed App"),
            MockApp("com.example.notinstalled", "Not Installed App"),
        ))
        // Assume "com.example.installed" is tracked as installed

        // When: User selects "Installed" filter
        composeRule.onNodeWithText("Installed").performClick()

        // Then: Only installed app is shown
        composeRule.onNodeWithText("Installed App").assertIsDisplayed()
        composeRule.onNodeWithText("Not Installed App").assertDoesNotExist()
    }

    @Test
    fun catalogScreen_pullToRefresh() {
        // Given: Initial apps loaded
        mockServerRule.enqueueAppsResponse(listOf(
            MockApp("com.example.app1", "App 1"),
        ))

        // Wait for initial load
        composeRule.onNodeWithText("App 1").assertIsDisplayed()

        // Given: Server now returns more apps
        mockServerRule.enqueueAppsResponse(listOf(
            MockApp("com.example.app1", "App 1"),
            MockApp("com.example.app2", "App 2"),
        ))

        // When: User pulls to refresh
        composeRule.onNodeWithTag("CatalogList").performTouchInput {
            swipeDown()
        }

        // Then: New app appears
        composeRule.onNodeWithText("App 2").assertIsDisplayed()
    }

    @Test
    fun catalogScreen_navigatesToAppDetail() {
        // Given: Server returns apps
        mockServerRule.enqueueAppsResponse(listOf(
            MockApp("com.example.app1", "Calculator"),
        ))

        // And: Server will return app detail
        mockServerRule.enqueueAppDetailResponse(MockAppDetail(
            packageName = "com.example.app1",
            name = "Calculator",
            description = "A simple calculator app",
            versions = listOf(
                MockAppVersion(2, "2.0.0", 2000, "sha256-hash-2"),
                MockAppVersion(1, "1.0.0", 1000, "sha256-hash-1"),
            ),
        ))

        // When: User taps on app
        composeRule.onNodeWithText("Calculator").performClick()

        // Then: App detail screen is shown
        composeRule.onNodeWithText("A simple calculator app").assertIsDisplayed()
        composeRule.onNodeWithText("2.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("1.0.0").assertIsDisplayed()
    }

    @Test
    fun catalogScreen_showsErrorOnNetworkFailure() {
        // Given: Server returns error
        mockServerRule.enqueueError(500, "Internal server error")

        // Then: Error state is shown
        composeRule.onNodeWithText("Error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }
}
```

### 11.5 E2E Test - App Detail & Download

```kotlin
// app/src/androidTest/java/com/lelloman/store/e2e/AppDetailE2ETest.kt
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppDetailE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val mockServerRule = MockServerRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appDetail_showsInstallButtonForNewApp() {
        // Navigate to app detail for an app that's not installed

        // Then: Install button is shown
        composeRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun appDetail_showsUpdateButtonForOutdatedApp() {
        // Navigate to app detail for an app that has an update

        // Then: Update button is shown
        composeRule.onNodeWithText("Update").assertIsDisplayed()
    }

    @Test
    fun appDetail_showsDownloadProgress() {
        // Given: App detail is shown
        // And: Server will stream APK slowly

        // When: User taps Install
        composeRule.onNodeWithText("Install").performClick()

        // Then: Progress indicator is shown
        composeRule.onNodeWithTag("DownloadProgress").assertIsDisplayed()
    }

    @Test
    fun appDetail_canCancelDownload() {
        // Given: Download is in progress
        composeRule.onNodeWithText("Install").performClick()
        composeRule.onNodeWithTag("DownloadProgress").assertIsDisplayed()

        // When: User taps Cancel
        composeRule.onNodeWithText("Cancel").performClick()

        // Then: Download is cancelled, Install button returns
        composeRule.onNodeWithText("Install").assertIsDisplayed()
    }
}
```

### 11.6 E2E Test - Settings

```kotlin
// app/src/androidTest/java/com/lelloman/store/e2e/SettingsE2ETest.kt
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settings_displaysCurrentServerUrl() {
        // Navigate to settings
        composeRule.onNodeWithText("Settings").performClick()

        // Then: Current server URL is shown
        composeRule.onNodeWithTag("ServerUrlField").assertIsDisplayed()
    }

    @Test
    fun settings_changingServerUrlLogsOut() {
        // Given: On settings screen
        composeRule.onNodeWithText("Settings").performClick()

        // When: User changes server URL
        composeRule.onNodeWithTag("ServerUrlField").performTextClearance()
        composeRule.onNodeWithTag("ServerUrlField").performTextInput("http://new.example.com")
        composeRule.onNodeWithText("Save").performClick()

        // Then: Confirmation dialog is shown
        composeRule.onNodeWithText("Changing server will log you out").assertIsDisplayed()

        // When: User confirms
        composeRule.onNodeWithText("Confirm").performClick()

        // Then: User is logged out and on login screen
        composeRule.onNodeWithText("Login").assertIsDisplayed()
    }

    @Test
    fun settings_themeChangeAppliesImmediately() {
        // Given: On settings screen with Light theme
        composeRule.onNodeWithText("Settings").performClick()

        // When: User selects Dark theme
        composeRule.onNodeWithText("Dark").performClick()

        // Then: Theme changes immediately (would need to verify colors)
        // This is harder to test - might use screenshot testing
    }
}
```

### 11.7 E2E Test - Updates Flow

```kotlin
// app/src/androidTest/java/com/lelloman/store/e2e/UpdatesE2ETest.kt
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UpdatesE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val mockServerRule = MockServerRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun updates_showsAvailableUpdates() {
        // Given: User has installed app version 1, server has version 2
        // Setup installed apps and server response

        // When: Navigate to Updates tab
        composeRule.onNodeWithText("Updates").performClick()

        // Then: Update is shown
        composeRule.onNodeWithText("Update available").assertIsDisplayed()
    }

    @Test
    fun updates_checkNowRefreshesUpdates() {
        // Given: On updates screen with no updates
        composeRule.onNodeWithText("Updates").performClick()
        composeRule.onNodeWithText("No updates available").assertIsDisplayed()

        // Given: Server now returns app with update
        mockServerRule.enqueueAppsResponse(listOf(
            MockApp("com.example.app", "App", versionCode = 2),
        ))

        // When: User taps Check Now
        composeRule.onNodeWithText("Check Now").performClick()

        // Then: Update appears
        composeRule.onNodeWithText("Update available").assertIsDisplayed()
    }

    @Test
    fun updates_updateAllDownloadsAllUpdates() {
        // Given: Multiple apps have updates
        // Setup...

        // When: User taps Update All
        composeRule.onNodeWithText("Update All").performClick()

        // Then: All downloads start (progress indicators shown)
        composeRule.onAllNodesWithTag("DownloadProgress").assertCountEquals(2)
    }
}
```

### 11.8 Verification

- [ ] `./gradlew connectedAndroidTest` passes all E2E tests
- [ ] Catalog loads and displays mocked apps
- [ ] Search and filters work correctly
- [ ] Navigation to app detail works
- [ ] Download flow with progress works
- [ ] Settings changes work
- [ ] Updates detection works
- [ ] Error states are handled correctly
- [ ] All user journeys tested end-to-end

---

## Final Checklist

### Functionality

- [ ] Login with OIDC works
- [ ] Catalog loads and displays apps
- [ ] Filtering works (All/Installed/Not Installed)
- [ ] Search works
- [ ] App detail shows all info
- [ ] Download with progress works
- [ ] Install triggers package installer
- [ ] Installed apps are detected
- [ ] Updates are detected
- [ ] Background update check works
- [ ] Notifications work
- [ ] Settings persist
- [ ] Server URL can be changed
- [ ] Logout clears session
- [ ] Theme switching works

### Quality

- [ ] No ANRs or crashes
- [ ] Error states are handled
- [ ] Loading states are shown
- [ ] Empty states are shown
- [ ] Offline behavior is graceful
- [ ] Back navigation works correctly

### Security

- [ ] Tokens stored in EncryptedSharedPreferences
- [ ] APK SHA-256 verified before install
- [ ] No sensitive data in logs

---

## Revision History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-19 | Initial implementation plan |
| 1.1 | 2025-12-19 | Added comprehensive testing: unit tests for every component, E2E tests with MockWebServer |
