package com.lelloman.store.remoteadb

import android.util.AtomicFile
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

interface AdbSigner {
    fun sign(token: ByteArray): ByteArray
    fun publicKey(): ByteArray
}

class AdbIdentity private constructor(private val key: RSAPrivateCrtKey) : AdbSigner {
    override fun sign(token: ByteArray): ByteArray {
        require(token.size == 20) { "Invalid ADB authentication challenge" }
        return AndroidPubkey.adbAuthSign(key, token)
    }
    override fun publicKey(): ByteArray {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(key.modulus, key.publicExponent),
        ) as RSAPublicKey
        return AndroidPubkey.encodeWithName(publicKey, "LelloStore Pesce e pesce")
    }
    companion object {
        @Synchronized
        fun load(file: File): AdbIdentity {
            val atomic = AtomicFile(file)
            val key = if (file.exists() || File(file.path + ".bak").exists()) {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(atomic.readFully()))
            } else {
                val generated = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
                file.parentFile?.mkdirs()
                val output = atomic.startWrite()
                try {
                    output.write(generated.encoded)
                    atomic.finishWrite(output)
                } catch (error: Exception) {
                    atomic.failWrite(output)
                    throw error
                }
                generated
            }
            return AdbIdentity(key as RSAPrivateCrtKey)
        }
    }
}
