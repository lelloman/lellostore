package com.lelloman.store.localdata.auth

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.logger.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CompositeAuthStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val oidc = FakeOidcAuthStore()
    private val config = FakeConfigStore("https://store.example.com")
    private val logger = AuditLogger()

    private fun tokenFile(serverUrl: String = "https://store.example.com"): File =
        folder.root.resolve(DeviceTokenFile.FILE_NAME).apply {
            writeText(
                JSONObject().put("version", 1).put("server_url", serverUrl)
                    .put("name", "Living room TV").put("token", SECRET).toString(),
            )
        }

    private fun TestScope.composite(file: File): CompositeAuthStore {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val device = DeviceTokenAuthStore(file, config, backgroundScope, logger, dispatcher)
        return CompositeAuthStore(device, oidc, backgroundScope)
    }

    @Test
    fun `absent file delegates to oidc`() = runTest(UnconfinedTestDispatcher()) {
        val store = composite(folder.root.resolve(DeviceTokenFile.FILE_NAME))
        advanceUntilIdle()

        assertThat(store.authState.value).isEqualTo(AuthState.NotAuthenticated)
        oidc.state.value = AuthState.Authenticated("user@example.com")
        assertThat(store.authState.value).isEqualTo(AuthState.Authenticated("user@example.com"))
        assertThat(store.getAccessToken()).isEqualTo("oidc-token")
        assertThat(store.getAccessToken(600)).isEqualTo("oidc-token-600")
    }

    @Test
    fun `stays loading until the file has been checked`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = DeviceTokenAuthStore(tokenFile(), config, backgroundScope, logger, dispatcher)
        val store = CompositeAuthStore(device, oidc, backgroundScope)
        oidc.state.value = AuthState.NotAuthenticated

        assertThat(store.authState.value).isEqualTo(AuthState.Loading)
        // Waits for the file check, like any request issued during startup.
        assertThat(store.getAccessToken()).isEqualTo(SECRET)
        runCurrent()
        assertThat(store.authState.value).isEqualTo(AuthState.Authenticated("Living room TV", deviceToken = true))
    }

    @Test
    fun `provisioned token authenticates with its name and is returned`() = runTest(UnconfinedTestDispatcher()) {
        val store = composite(tokenFile())
        advanceUntilIdle()

        assertThat(store.authState.value).isEqualTo(AuthState.Authenticated("Living room TV", deviceToken = true))
        assertThat(store.getAccessToken()).isEqualTo(SECRET)
        assertThat(store.getAccessToken(600)).isEqualTo(SECRET)
        assertThat(config.setCalls).isEmpty()
        assertThat(logger.events).contains("auth.device_token_loaded")
        assertThat(logger.fields.toString()).doesNotContain(SECRET)
    }

    @Test
    fun `server from the file is applied when different`() = runTest(UnconfinedTestDispatcher()) {
        val store = composite(tokenFile(serverUrl = "https://other.example.com"))
        advanceUntilIdle()

        assertThat(config.setCalls).containsExactly("https://other.example.com")
        assertThat(logger.events).contains("auth.device_token_server_applied")
        assertThat(store.getAccessToken()).isEqualTo(SECRET)
    }

    @Test
    fun `token is withheld from another selected server`() = runTest(UnconfinedTestDispatcher()) {
        val store = composite(tokenFile())
        advanceUntilIdle()

        config.serverUrl.value = "https://elsewhere.example.com"
        assertThat(store.getAccessToken()).isNull()
    }

    @Test
    fun `logout rejects the token, falls back to oidc and keeps the file`() = runTest(UnconfinedTestDispatcher()) {
        val file = tokenFile()
        oidc.state.value = AuthState.Authenticated("user@example.com")
        val store = composite(file)
        advanceUntilIdle()
        assertThat(store.deviceTokenRejected.value).isFalse()

        store.logout()

        assertThat(store.authState.value).isEqualTo(AuthState.NotAuthenticated)
        assertThat(store.deviceTokenRejected.value).isTrue()
        assertThat(oidc.logouts).isEqualTo(1)
        assertThat(file.exists()).isTrue()
        assertThat(logger.events).contains("auth.device_token_rejected")
    }

    @Test
    fun `invalid file is audited and ignored`() = runTest(UnconfinedTestDispatcher()) {
        val file = folder.root.resolve(DeviceTokenFile.FILE_NAME).apply { writeText("{}") }
        val store = composite(file)
        advanceUntilIdle()

        assertThat(store.authState.value).isEqualTo(AuthState.NotAuthenticated)
        assertThat(store.getAccessToken()).isEqualTo("oidc-token")
        assertThat(logger.events).contains("auth.device_token_invalid")
    }

    private class FakeOidcAuthStore : AuthStore {
        val state = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
        var logouts = 0
        override val authState: StateFlow<AuthState> = state
        override suspend fun getAccessToken(): String = "oidc-token"
        override suspend fun getAccessToken(minValiditySeconds: Long): String = "oidc-token-$minValiditySeconds"
        override suspend fun logout() {
            logouts++
            state.value = AuthState.NotAuthenticated
        }
    }

    private class FakeConfigStore(initial: String) : ConfigStore {
        override val serverUrl = MutableStateFlow(initial)
        val setCalls = mutableListOf<String>()
        override suspend fun setServerUrl(url: String): ConfigStore.SetServerUrlResult {
            setCalls += url
            serverUrl.value = url
            return ConfigStore.SetServerUrlResult.Success
        }
    }

    private class AuditLogger : Logger {
        val events = mutableListOf<String>()
        val fields = mutableListOf<Map<String, Any?>>()
        override fun audit(event: String, fields: Map<String, Any?>) {
            events += event
            this.fields += fields
        }
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }

    companion object {
        val SECRET = "lellostore_dev_" + "fedcba9876543210".repeat(4)
    }
}
