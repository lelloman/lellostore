package com.lelloman.store.localdata.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric provides the real org.json implementation used to parse the credential.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceCredentialStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val cipher = SoftwareCredentialCipher()
    private val token = DeviceToken("Living room TV", "https://store.example.com", SECRET)

    private fun store(cipher: CredentialCipher = this.cipher) = DeviceCredentialStore(
        folder.root.resolve(DeviceCredentialStore.FILE_NAME),
        folder.root.resolve(DeviceCredentialStore.LEGACY_MARKER_FILE_NAME),
        cipher,
    )

    @Test
    fun `nothing stored is absent`() {
        assertThat(store().read()).isEqualTo(DeviceTokenFile.Result.Absent)
    }

    @Test
    fun `a written credential reads back and is not stored in clear text`() {
        store().write(token)

        assertThat(store().read()).isEqualTo(DeviceTokenFile.Result.Valid(token))
        val raw = folder.root.resolve(DeviceCredentialStore.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1)
        assertThat(raw).doesNotContain(SECRET)
        assertThat(raw).doesNotContain("Living room TV")
        assertThat(folder.root.list()!!.toList()).containsExactly(DeviceCredentialStore.FILE_NAME)
    }

    @Test
    fun `writing replaces the previous credential`() {
        store().write(token)
        val newer = token.copy(name = "Kitchen TV", token = "lellostore_dev_" + "0".repeat(64))
        store().write(newer)

        assertThat(store().read()).isEqualTo(DeviceTokenFile.Result.Valid(newer))
    }

    @Test
    fun `a credential sealed with another key is undecryptable`() {
        store().write(token)

        assertThat(store(SoftwareCredentialCipher()).read()).isEqualTo(DeviceTokenFile.Result.Invalid("undecryptable"))
    }

    @Test
    fun `a tampered or foreign file is refused`() {
        store().write(token)
        val file = folder.root.resolve(DeviceCredentialStore.FILE_NAME)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThat(store().read()).isEqualTo(DeviceTokenFile.Result.Invalid("undecryptable"))

        file.writeText("""{"version":1}""")
        assertThat(store().read()).isEqualTo(DeviceTokenFile.Result.Invalid("unsupported_format"))
    }

    @Test
    fun `a failed seal leaves the previous credential in place`() {
        store().write(token)
        cipher.failSeal = true

        val result = runCatching { store().write(token.copy(name = "Other")) }

        assertThat(result.isFailure).isTrue()
        cipher.failSeal = false
        assertThat(store().read()).isEqualTo(DeviceTokenFile.Result.Valid(token))
    }

    @Test
    fun `clear deletes the credential but keeps the legacy import record`() {
        val store = store()
        store.write(token)
        store.recordLegacyImport("abc123")

        assertThat(store.clear()).isTrue()

        assertThat(store.read()).isEqualTo(DeviceTokenFile.Result.Absent)
        assertThat(store.importedLegacyFingerprint()).isEqualTo("abc123")
    }

    @Test
    fun `fingerprints identify the content without containing the secret`() {
        val fingerprint = DeviceCredentialStore.fingerprint(token)

        assertThat(fingerprint).matches("[0-9a-f]{64}")
        assertThat(fingerprint).isNotEqualTo(DeviceCredentialStore.fingerprint(token.copy(name = "Other")))
        assertThat(fingerprint).doesNotContain(SECRET.removePrefix("lellostore_dev_"))
    }

    private companion object {
        val SECRET = "lellostore_dev_" + "fedcba9876543210".repeat(4)
    }
}
