package com.lelloman.store.localdata.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small secrets with a key that never leaves this device. */
interface CredentialCipher {
    /** The nonce followed by the ciphertext and its authentication tag. */
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** Throws [GeneralSecurityException] when [sealed] was not produced by [seal] with this key. */
    fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable Android Keystore key. Keystore picks a fresh 12-byte nonce
 * for every encryption (randomized encryption is required), so the nonce is stored in front of
 * the ciphertext. The key material is never visible to the app process.
 */
class KeystoreCredentialCipher(private val alias: String = DEFAULT_ALIAS) : CredentialCipher {

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        val nonce = cipher.iv
        check(nonce.size == NONCE_BYTES) { "Unexpected nonce size" }
        return nonce + ciphertext
    }

    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        if (sealed.size <= NONCE_BYTES + TAG_BYTES) throw GeneralSecurityException("Sealed data is too short")
        val key = key(create = false) ?: throw GeneralSecurityException("The device credential key is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 0, NONCE_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    @Synchronized
    private fun key(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "lellostore.device_credential.v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
    }
}

/**
 * The device credential (the `device-token.json` content) sealed with [cipher] in
 * `noBackupFilesDir`, so it never leaves the device in a backup and a copy of the file is useless
 * without this device's Keystore. Root can still use the key on the device (see the threat model
 * in docs/CREDENTIAL_PROVIDER_DESIGN.md).
 *
 * [legacyMarker] records which legacy root-provisioned `device-token.json` was last imported (a
 * SHA-256 of its content, not the secret), so a file that was already imported is not imported
 * again over a newer enrollment, and a forgotten device stays forgotten.
 */
class DeviceCredentialStore(
    private val file: File,
    private val legacyMarker: File,
    private val cipher: CredentialCipher,
) {

    @Synchronized
    fun read(): DeviceTokenFile.Result {
        if (!file.exists()) return DeviceTokenFile.Result.Absent
        val bytes = try {
            if (!file.isFile || file.length() > MAX_BYTES) return DeviceTokenFile.Result.Invalid("unreadable")
            file.readBytes()
        } catch (_: IOException) {
            return DeviceTokenFile.Result.Invalid("unreadable")
        } catch (_: SecurityException) {
            return DeviceTokenFile.Result.Invalid("unreadable")
        }
        if (bytes.size <= HEADER.size || !bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            return DeviceTokenFile.Result.Invalid("unsupported_format")
        }
        val plaintext = try {
            cipher.open(bytes.copyOfRange(HEADER.size, bytes.size), ASSOCIATED_DATA)
        } catch (_: GeneralSecurityException) {
            // A lost or replaced Keystore key, or a tampered file.
            return DeviceTokenFile.Result.Invalid("undecryptable")
        } catch (_: RuntimeException) {
            // Keystore implementations also throw unchecked exceptions (ProviderException).
            return DeviceTokenFile.Result.Invalid("undecryptable")
        }
        return try {
            DeviceTokenFile.parse(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    /** Replaces the stored credential atomically. Throws when it could not be sealed or written. */
    @Synchronized
    fun write(token: DeviceToken) {
        val plaintext = DeviceTokenFile.serialize(token).toByteArray(Charsets.UTF_8)
        val sealed = try {
            cipher.seal(plaintext, ASSOCIATED_DATA)
        } finally {
            plaintext.fill(0)
        }
        writeAtomically(file, HEADER + sealed)
    }

    /** Deletes the stored credential; returns false when a file is left behind. */
    @Synchronized
    fun clear(): Boolean = !file.exists() || file.delete()

    @Synchronized
    fun importedLegacyFingerprint(): String? = try {
        legacyMarker.takeIf { it.isFile && it.length() <= MARKER_MAX_BYTES }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }

    @Synchronized
    fun recordLegacyImport(fingerprint: String) {
        writeAtomically(legacyMarker, fingerprint.toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomically(target: File, content: ByteArray) {
        val directory = target.absoluteFile.parentFile ?: throw IOException("No parent directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the credential directory")
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content)
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot replace ${target.name}")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        const val FILE_NAME = "device-credential.bin"
        const val LEGACY_MARKER_FILE_NAME = "device-credential-legacy-import"
        private const val MAX_BYTES = 8192L
        private const val MARKER_MAX_BYTES = 256L

        // "LSDC" and a format version, also bound into the authentication tag.
        private val HEADER = byteArrayOf(0x4C, 0x53, 0x44, 0x43, 0x01)
        private val ASSOCIATED_DATA = "lellostore.device-credential.v1".toByteArray(Charsets.UTF_8)

        /** Identifies a legacy file's content without keeping its secret. */
        fun fingerprint(token: DeviceToken): String =
            MessageDigest.getInstance("SHA-256")
                .digest(DeviceTokenFile.serialize(token).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
