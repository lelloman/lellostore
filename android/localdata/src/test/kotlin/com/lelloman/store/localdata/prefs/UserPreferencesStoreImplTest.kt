package com.lelloman.store.localdata.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.preferences.ThemeMode
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesStoreImplTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesStore: UserPreferencesStoreImpl

    @Before
    fun setup() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmpFolder.newFile("test.preferences_pb") }
        )
        preferencesStore = UserPreferencesStoreImpl(
            dataStore = dataStore,
            scope = testScope.backgroundScope,
        )
    }

    @Test
    fun `themeMode defaults to System`() = testScope.runTest {
        assertThat(preferencesStore.themeMode.value).isEqualTo(ThemeMode.System)
    }

    @Test
    fun `setThemeMode updates themeMode flow`() = testScope.runTest {
        preferencesStore.themeMode.test {
            assertThat(awaitItem()).isEqualTo(ThemeMode.System)

            preferencesStore.setThemeMode(ThemeMode.Dark)

            assertThat(awaitItem()).isEqualTo(ThemeMode.Dark)
        }
    }

    @Test
    fun `setThemeMode to Light works correctly`() = testScope.runTest {
        preferencesStore.setThemeMode(ThemeMode.Light)

        preferencesStore.themeMode.test {
            assertThat(awaitItem()).isEqualTo(ThemeMode.Light)
        }
    }

    @Test
    fun `updateCheckInterval defaults to Hours24`() = testScope.runTest {
        assertThat(preferencesStore.updateCheckInterval.value).isEqualTo(UpdateCheckInterval.Hours24)
    }

    @Test
    fun `setUpdateCheckInterval updates flow`() = testScope.runTest {
        preferencesStore.updateCheckInterval.test {
            assertThat(awaitItem()).isEqualTo(UpdateCheckInterval.Hours24)

            preferencesStore.setUpdateCheckInterval(UpdateCheckInterval.Hours6)

            assertThat(awaitItem()).isEqualTo(UpdateCheckInterval.Hours6)
        }
    }

    @Test
    fun `setUpdateCheckInterval to Manual works correctly`() = testScope.runTest {
        preferencesStore.setUpdateCheckInterval(UpdateCheckInterval.Manual)

        preferencesStore.updateCheckInterval.test {
            assertThat(awaitItem()).isEqualTo(UpdateCheckInterval.Manual)
        }
    }

    @Test
    fun `wifiOnlyDownloads defaults to true`() = testScope.runTest {
        assertThat(preferencesStore.wifiOnlyDownloads.value).isTrue()
    }

    @Test
    fun `setWifiOnlyDownloads updates flow`() = testScope.runTest {
        preferencesStore.wifiOnlyDownloads.test {
            assertThat(awaitItem()).isTrue()

            preferencesStore.setWifiOnlyDownloads(false)

            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `can toggle wifiOnlyDownloads back and forth`() = testScope.runTest {
        preferencesStore.wifiOnlyDownloads.test {
            assertThat(awaitItem()).isTrue()

            preferencesStore.setWifiOnlyDownloads(false)
            assertThat(awaitItem()).isFalse()

            preferencesStore.setWifiOnlyDownloads(true)
            assertThat(awaitItem()).isTrue()
        }
    }

    @Test
    fun `all preferences can be set independently`() = testScope.runTest {
        preferencesStore.setThemeMode(ThemeMode.Dark)
        preferencesStore.setUpdateCheckInterval(UpdateCheckInterval.Hours12)
        preferencesStore.setWifiOnlyDownloads(false)

        assertThat(preferencesStore.themeMode.value).isEqualTo(ThemeMode.Dark)
        assertThat(preferencesStore.updateCheckInterval.value).isEqualTo(UpdateCheckInterval.Hours12)
        assertThat(preferencesStore.wifiOnlyDownloads.value).isFalse()
    }
}
