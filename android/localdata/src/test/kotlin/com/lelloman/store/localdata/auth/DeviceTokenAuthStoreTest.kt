package com.lelloman.store.localdata.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The encrypted device credential, enrollment, forgetting and the legacy file import. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceTokenAuthStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val config = RecordingConfigStore("https://store.example.com")
    private val logger = RecordingLogger()
    private val cipher = SoftwareCredentialCipher()
    private val legacyFile: File get() = folder.root.resolve(DeviceTokenFile.FILE_NAME)
    private val enrolled = DeviceToken("Mi Box", "https://store.example.com", "lellostore_dev_" + "1".repeat(64))
    private val legacy = DeviceToken("Living room TV", "https://store.example.com", "lellostore_dev_" + "2".repeat(64))

    private fun credentials() = DeviceCredentialStore(
        folder.root.resolve(DeviceCredentialStore.FILE_NAME),
        folder.root.resolve(DeviceCredentialStore.LEGACY_MARKER_FILE_NAME),
        cipher,
    )

    private fun writeLegacy(token: DeviceToken) {
        legacyFile.writeText(
            JSONObject().put("version", 1).put("server_url", token.serverUrl)
                .put("name", token.name).put("token", token.token).toString(),
        )
    }

    /** A new process: the store loads what is on disk. */
    private fun TestScope.newProcess(): DeviceTokenAuthStore =
        DeviceTokenAuthStore(legacyFile, credentials(), config, backgroundScope, logger, UnconfinedTestDispatcher(testScheduler))

    private fun DeviceTokenAuthStore.provisioned(): DeviceToken? =
        (mode.value as? DeviceTokenAuthStore.Mode.Provisioned)?.token

    @Test
    fun `nothing stored is absent`() = runTest(UnconfinedTestDispatcher()) {
        val store = newProcess()
        advanceUntilIdle()

        assertThat(store.mode.value).isEqualTo(DeviceTokenAuthStore.Mode.Absent)
        assertThat(store.getAccessToken()).isNull()
    }

    @Test
    fun `enrollment stores the credential, signs in at once and survives a restart`() = runTest(UnconfinedTestDispatcher()) {
        val store = newProcess()
        advanceUntilIdle()

        store.provision(enrolled)

        assertThat(store.provisioned()).isEqualTo(enrolled)
        assertThat(store.getAccessToken()).isEqualTo(enrolled.token)
        assertThat(logger.events).contains("auth.device_token_enrolled")
        assertThat(logger.fields.toString()).doesNotContain(enrolled.token)
        assertThat(legacyFile.exists()).isFalse()

        val restarted = newProcess()
        advanceUntilIdle()
        assertThat(restarted.provisioned()).isEqualTo(enrolled)
    }

    @Test
    fun `enrollment for another store selects that store`() = runTest(UnconfinedTestDispatcher()) {
        val store = newProcess()
        advanceUntilIdle()

        store.provision(enrolled.copy(serverUrl = "https://other.example.com"))

        assertThat(config.setCalls).containsExactly("https://other.example.com")
        assertThat(store.getAccessToken()).isEqualTo(enrolled.token)
    }

    @Test
    fun `a failed enrollment write leaves the mode unchanged`() = runTest(UnconfinedTestDispatcher()) {
        val store = newProcess()
        advanceUntilIdle()
        cipher.failSeal = true

        val result = runCatching { store.provision(enrolled) }

        assertThat(result.isFailure).isTrue()
        assertThat(store.mode.value).isEqualTo(DeviceTokenAuthStore.Mode.Absent)
    }

    @Test
    fun `a legacy file is imported on start and the file is kept for a downgrade`() = runTest(UnconfinedTestDispatcher()) {
        writeLegacy(legacy)

        val store = newProcess()
        advanceUntilIdle()

        assertThat(store.provisioned()).isEqualTo(legacy)
        assertThat(credentials().read()).isEqualTo(DeviceTokenFile.Result.Valid(legacy))
        assertThat(credentials().importedLegacyFingerprint()).isEqualTo(DeviceCredentialStore.fingerprint(legacy))
        assertThat(legacyFile.exists()).isTrue()
        assertThat(logger.events).contains("auth.device_token_imported")
    }

    @Test
    fun `the legacy file is still used while the import fails`() = runTest(UnconfinedTestDispatcher()) {
        writeLegacy(legacy)
        cipher.failSeal = true

        val store = newProcess()
        advanceUntilIdle()

        assertThat(store.provisioned()).isEqualTo(legacy)
        assertThat(credentials().read()).isEqualTo(DeviceTokenFile.Result.Absent)
        assertThat(logger.events).contains("auth.device_token_import_failed")

        // The next start retries.
        cipher.failSeal = false
        newProcess()
        advanceUntilIdle()
        assertThat(credentials().read()).isEqualTo(DeviceTokenFile.Result.Valid(legacy))
    }

    @Test
    fun `an imported legacy file does not replace a later enrollment`() = runTest(UnconfinedTestDispatcher()) {
        writeLegacy(legacy)
        val first = newProcess()
        advanceUntilIdle()
        first.provision(enrolled)

        val restarted = newProcess()
        advanceUntilIdle()

        assertThat(restarted.provisioned()).isEqualTo(enrolled)
    }

    @Test
    fun `a legacy file provisioned again by root replaces the stored credential`() = runTest(UnconfinedTestDispatcher()) {
        newProcess().also { advanceUntilIdle() }.provision(enrolled)
        writeLegacy(legacy)

        val restarted = newProcess()
        advanceUntilIdle()

        assertThat(restarted.provisioned()).isEqualTo(legacy)
    }

    @Test
    fun `an undecryptable credential falls back to the legacy file`() = runTest(UnconfinedTestDispatcher()) {
        writeLegacy(legacy)
        newProcess()
        advanceUntilIdle()

        // As if the Keystore key were lost.
        val store = DeviceTokenAuthStore(
            legacyFile,
            DeviceCredentialStore(
                folder.root.resolve(DeviceCredentialStore.FILE_NAME),
                folder.root.resolve(DeviceCredentialStore.LEGACY_MARKER_FILE_NAME),
                SoftwareCredentialCipher(),
            ),
            config, backgroundScope, logger, UnconfinedTestDispatcher(testScheduler),
        )
        advanceUntilIdle()

        assertThat(store.provisioned()).isEqualTo(legacy)
        assertThat(logger.events).contains("auth.device_credential_invalid")
    }

    @Test
    fun `forgetting deletes the credential and the legacy file for good`() = runTest(UnconfinedTestDispatcher()) {
        writeLegacy(legacy)
        val store = newProcess()
        advanceUntilIdle()

        assertThat(store.forget()).isTrue()

        assertThat(store.mode.value).isEqualTo(DeviceTokenAuthStore.Mode.Absent)
        assertThat(legacyFile.exists()).isFalse()
        assertThat(credentials().read()).isEqualTo(DeviceTokenFile.Result.Absent)
        assertThat(logger.events).contains("auth.device_token_forgotten")

        // A copy of the same file that could not be removed is not imported again.
        writeLegacy(legacy)
        val restarted = newProcess()
        advanceUntilIdle()
        assertThat(restarted.mode.value).isEqualTo(DeviceTokenAuthStore.Mode.Absent)
    }

    @Test
    fun `a rejected credential is kept for the next start`() = runTest(UnconfinedTestDispatcher()) {
        val store = newProcess()
        advanceUntilIdle()
        store.provision(enrolled)

        store.reject()

        assertThat(store.mode.value).isEqualTo(DeviceTokenAuthStore.Mode.Rejected("Mi Box"))
        assertThat(store.getAccessToken()).isNull()
        val restarted = newProcess()
        advanceUntilIdle()
        assertThat(restarted.provisioned()).isEqualTo(enrolled)
    }
}
