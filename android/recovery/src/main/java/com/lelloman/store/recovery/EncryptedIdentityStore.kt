package com.lelloman.store.recovery

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.AtomicFile

internal class EncryptedIdentityStore(private val context: Context) {
    private val file get() = context.noBackupFilesDir.resolve("store-adb-identity-v1.enc")

    @Synchronized
    fun backup(privateKey: ByteArray, certificate: ByteArray) {
        verifyIdentity(privateKey, certificate)
        val cleartext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(privateKey.size)
                output.write(privateKey)
                output.writeInt(certificate.size)
                output.write(certificate)
            }
            bytes.toByteArray()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(cleartext)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(cipher.iv.size)
            output.write(cipher.iv)
            output.write(encrypted)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        } finally {
            cleartext.fill(0)
        }
    }

    @Synchronized
    fun restore(): Pair<ByteArray, ByteArray>? {
        if (!file.isFile) return null
        val payload = AtomicFile(file).readFully()
        val ivSize = payload.firstOrNull()?.toInt()?.and(0xff) ?: return null
        require(ivSize in 12..32 && payload.size > ivSize + 1) { "Invalid encrypted identity" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey(),
            GCMParameterSpec(128, payload.copyOfRange(1, ivSize + 1)),
        )
        cipher.updateAAD(AAD)
        val cleartext = cipher.doFinal(payload.copyOfRange(ivSize + 1, payload.size))
        return DataInputStream(ByteArrayInputStream(cleartext)).use { input ->
            val privateSize = input.readInt().also { require(it in 1..MAX_IDENTITY_BYTES) }
            val privateKey = ByteArray(privateSize).also(input::readFully)
            val certificateSize = input.readInt().also { require(it in 1..MAX_IDENTITY_BYTES) }
            val certificate = ByteArray(certificateSize).also(input::readFully)
            require(input.available() == 0) { "Unexpected identity payload" }
            verifyIdentity(privateKey, certificate)
            privateKey to certificate
        }.also { cleartext.fill(0) }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun verifyIdentity(privateBytes: ByteArray, certificateBytes: ByteArray) {
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateBytes))
        val challenge = "LelloStore recovery identity v1".toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
        check(Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(challenge)
            verify(signed)
        }) { "Private key and certificate do not match" }
    }

    private companion object {
        const val KEY_ALIAS = "lellostore-recovery-identity-wrap-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_IDENTITY_BYTES = 64 * 1024
        val AAD = "com.lelloman.store.recovery/identity/v1".toByteArray()
    }
}
