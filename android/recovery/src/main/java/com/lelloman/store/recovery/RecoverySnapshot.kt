package com.lelloman.store.recovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** The last running Store APK, transferred only over the signature-protected service. */
internal class RecoverySnapshot(private val context: Context) {
    private val apk = context.noBackupFilesDir.resolve("known-good-store.apk")
    private val prefs = context.getSharedPreferences("recovery-snapshot", Context.MODE_PRIVATE)

    @android.annotation.SuppressLint("UseKtx") // A failed synchronous metadata commit must reject the snapshot.
    fun save(descriptor: ParcelFileDescriptor, digest: String, version: Int) {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        val temporary = context.noBackupFilesDir.resolve("known-good-store.tmp.apk")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 512L * 1024 * 1024) { "Recovery APK is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            verify(temporary, digest, version)
            check(temporary.renameTo(apk)) { "Could not retain recovery APK" }
            check(prefs.edit().putString("digest", digest).putInt("version", version).commit())
        } finally {
            temporary.delete()
        }
    }

    fun verifiedApk(version: Int): File {
        check(prefs.getInt("version", -1) == version) { "Recovery APK version does not match this attempt" }
        verify(apk, prefs.getString("digest", "").orEmpty(), version)
        return apk
    }

    private fun verify(file: File, expectedDigest: String, version: Int) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expectedDigest) {
            "Recovery APK digest mismatch"
        }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: error("Invalid recovery APK")
        check(info.packageName == RecoveryPackages.STORE && PackageInfoCompat.getLongVersionCode(info) == version.toLong()) {
            "Recovery APK package or version mismatch"
        }
        @Suppress("DEPRECATION")
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        val actual = signers.mapTo(mutableSetOf()) {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }
        check(actual.isNotEmpty() && actual == SigningCertificates.sha256(context, context.packageName)) {
            "Recovery APK signer mismatch"
        }
    }
}
