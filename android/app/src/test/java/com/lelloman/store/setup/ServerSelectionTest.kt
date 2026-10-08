package com.lelloman.store.setup

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.OidcConfig
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.config.ServerDiscovery
import com.lelloman.store.domain.config.ServerMetadata
import com.lelloman.store.domain.config.StoreSession
import com.lelloman.store.domain.remote.RemoteDeviceOperations
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.localdata.db.LellostoreDatabase
import com.lelloman.store.localdata.db.entity.CachedAppEntity
import com.lelloman.store.localdata.db.entity.InstalledAppEntity
import com.lelloman.store.notification.NotificationBrokerRuntime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ServerSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: LellostoreDatabase
    private val auth = mockk<AuthStore>(relaxed = true)
    private val broker = mockk<NotificationBrokerRuntime>(relaxed = true)
    private val updates = mockk<UpdateChecker>(relaxed = true)
    private val remote = mockk<RemoteDeviceOperations>(relaxed = true)
    private val discovery = mockk<ServerDiscovery>()
    private val session = StoreSession()
    private val appKey = stringPreferencesKey("app.example.release_channel")
    private val deviceKey = stringPreferencesKey("theme")
    private val settings = object : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf(appKey to "beta", deviceKey to "dark"))
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private val config = object : ConfigStore {
        override val serverUrl = MutableStateFlow("https://old.example")
        override suspend fun setServerUrl(url: String): ConfigStore.SetServerUrlResult {
            serverUrl.value = url
            return ConfigStore.SetServerUrlResult.Success
        }
    }
    private lateinit var selection: ServerSelection

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, LellostoreDatabase::class.java).build()
        database.appsDao().insertApp(CachedAppEntity("app.example", "Old catalog", null, "", 1,
            "1.0", 10, null, 24, 0, 0))
        database.installedAppsDao().insert(InstalledAppEntity("app.example", 1, "1.0", 0))
        context.cacheDir.resolve("apks").mkdirs()
        context.cacheDir.resolve("apks/old.apk").writeText("old artifact")
        coEvery { discovery.discover(any()) } returns ServerMetadata("New Store",
            OidcConfig("https://id.new.example", "android", "com.lelloman.store:/oauth2redirect"), false, false)
        selection = ServerSelection(config, discovery, auth, broker, session, database, settings, updates, remote, context)
    }

    @After fun close() { database.close() }

    @Test fun `switch clears old catalog and app state before selecting the new origin`() = runBlocking {
        selection.select("https://NEW.example:443/")
        assertThat(config.serverUrl.value).isEqualTo("https://new.example")
        assertThat(database.appsDao().watchApps().first()).isEmpty()
        assertThat(database.installedAppsDao().get("app.example")?.versionCode).isEqualTo(1)
        assertThat(settings.data.value[appKey]).isNull()
        assertThat(settings.data.value[deviceKey]).isEqualTo("dark")
        assertThat(context.cacheDir.resolve("apks/old.apk").exists()).isFalse()
        coVerify(exactly = 1) { auth.logout(); broker.signedOut() }
        verify(exactly = 1) { remote.cancel(); updates.clear() }
    }

    @Test fun `failed discovery preserves working connection and its data`() = runBlocking {
        coEvery { discovery.discover(any()) } throws IllegalStateException("Unsupported server")
        assertThat(runCatching { selection.select("https://new.example") }.isFailure).isTrue()
        assertOldStoreIntact()
    }

    @Test fun `active catalog or install operation prevents destructive switching`() = runBlocking {
        session.use {
            assertThat(runCatching { selection.select("https://new.example") }.isFailure).isTrue()
        }
        assertOldStoreIntact()
    }

    private suspend fun assertOldStoreIntact() {
        assertThat(config.serverUrl.value).isEqualTo("https://old.example")
        assertThat(database.appsDao().getApp("app.example")?.name).isEqualTo("Old catalog")
        assertThat(settings.data.value[appKey]).isEqualTo("beta")
        assertThat(context.cacheDir.resolve("apks/old.apk").exists()).isTrue()
        coVerify(exactly = 0) { auth.logout(); broker.signedOut() }
        verify(exactly = 0) { updates.clear() }
    }
}
