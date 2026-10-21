package com.lelloman.store.localdata.auth

import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with an in-memory key: the Android Keystore is not available in local tests. */
internal class SoftwareCredentialCipher(
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
) : CredentialCipher {
    var failSeal = false

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        if (failSeal) throw GeneralSecurityException("Keystore unavailable")
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

internal class RecordingConfigStore(initial: String) : ConfigStore {
    override val serverUrl = MutableStateFlow(initial)
    val setCalls = mutableListOf<String>()
    override suspend fun setServerUrl(url: String): ConfigStore.SetServerUrlResult {
        setCalls += url
        serverUrl.value = url
        return ConfigStore.SetServerUrlResult.Success
    }
}

internal class RecordingLogger : Logger {
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
