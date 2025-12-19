package com.lelloman.store.localdata.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.config.ConfigStore.SetServerUrlResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigStoreImplTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var configStore: ConfigStoreImpl

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("test.preferences_pb") }
        )
        configStore = ConfigStoreImpl(
            dataStore = dataStore,
            defaultServerUrl = "https://default.example.com",
            scope = testScope.backgroundScope,
        )
    }

    @Test
    fun `serverUrl returns default when not set`() = testScope.runTest {
        assertThat(configStore.serverUrl.value).isEqualTo("https://default.example.com")
    }

    @Test
    fun `setServerUrl with valid HTTPS URL returns Success`() = testScope.runTest {
        val result = configStore.setServerUrl("https://new.example.com")

        assertThat(result).isEqualTo(SetServerUrlResult.Success)
    }

    @Test
    fun `setServerUrl with valid HTTP URL returns Success`() = testScope.runTest {
        val result = configStore.setServerUrl("http://local.example.com")

        assertThat(result).isEqualTo(SetServerUrlResult.Success)
    }

    @Test
    fun `setServerUrl updates serverUrl flow`() = testScope.runTest {
        configStore.serverUrl.test {
            assertThat(awaitItem()).isEqualTo("https://default.example.com")

            configStore.setServerUrl("https://updated.example.com")

            assertThat(awaitItem()).isEqualTo("https://updated.example.com")
        }
    }

    @Test
    fun `setServerUrl with invalid URL returns InvalidUrl`() = testScope.runTest {
        val result = configStore.setServerUrl("not-a-url")

        assertThat(result).isEqualTo(SetServerUrlResult.InvalidUrl)
    }

    @Test
    fun `setServerUrl with empty string returns InvalidUrl`() = testScope.runTest {
        val result = configStore.setServerUrl("")

        assertThat(result).isEqualTo(SetServerUrlResult.InvalidUrl)
    }

    @Test
    fun `setServerUrl with blank string returns InvalidUrl`() = testScope.runTest {
        val result = configStore.setServerUrl("   ")

        assertThat(result).isEqualTo(SetServerUrlResult.InvalidUrl)
    }

    @Test
    fun `setServerUrl with ftp URL returns InvalidUrl`() = testScope.runTest {
        val result = configStore.setServerUrl("ftp://files.example.com")

        assertThat(result).isEqualTo(SetServerUrlResult.InvalidUrl)
    }

    @Test
    fun `setServerUrl with URL missing host returns InvalidUrl`() = testScope.runTest {
        val result = configStore.setServerUrl("https://")

        assertThat(result).isEqualTo(SetServerUrlResult.InvalidUrl)
    }

    @Test
    fun `invalid URL does not change serverUrl`() = testScope.runTest {
        configStore.setServerUrl("https://valid.example.com")

        configStore.serverUrl.test {
            assertThat(awaitItem()).isEqualTo("https://valid.example.com")

            configStore.setServerUrl("invalid")

            // Should not emit new value since URL is invalid
            expectNoEvents()
        }
    }
}
