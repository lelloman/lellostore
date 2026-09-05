package com.lelloman.store.recovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class RecoveryEngine(private val context: Context) {
    fun testConnection(): Result<String> = runCatching {
        val adb = connect()
        readText(adb.openStream("shell:id; getprop ro.product.model").openInputStream()).trim().also {
            check(it.contains("uid=2000(shell)")) { "ADB did not provide a shell" }
        }
    }

    fun repair(attempt: RecoveryAttempt): Result<String> = runCatching {
        require(attempt.status == RecoveryStatus.REPAIRING) { "Repair was not explicitly approved" }
        val expectedSigner = SigningCertificates.sha256(context, context.packageName)
        check(attempt.expectedSignerSha256 in expectedSigner) { "Attempt signer does not match recovery signer" }
        verifyInstalledStoreSigner(expectedSigner)

        val recoveryApk = extractAndVerifyRecoveryApk(expectedSigner)
        val adb = connect()

        val inPlace = install(adb, recoveryApk, allowDowngrade = true)
        if (!inPlace.startsWith("Success")) {
            val uninstall = readText(
                adb.openStream("shell:cmd package uninstall ${RecoveryPackages.STORE}").openInputStream()
            ).trim()
            check(uninstall.startsWith("Success")) { "Store uninstall failed: $uninstall" }
            val cleanInstall = install(adb, recoveryApk, allowDowngrade = false)
            check(cleanInstall.startsWith("Success")) { "Recovery install failed: $cleanInstall" }
        }
        runCatching {
            readText(
                adb.openStream(
                    "shell:am start -n ${RecoveryPackages.STORE}/com.lelloman.store.MainActivity"
                ).openInputStream()
            )
        }
        "Known-good LelloStore installed; waiting for identity handover and health acknowledgement"
    }

    private fun connect(): RecoveryAdbConnectionManager {
        val adb = RecoveryAdbConnectionManager.getInstance(context)
        if (adb.isConnected) adb.disconnect()
        check(adb.connect("127.0.0.1", 5555)) { "No authorized ADB service at 127.0.0.1:5555" }
        return adb
    }

    private fun verifyInstalledStoreSigner(expected: Set<String>) {
        val installed = try {
            SigningCertificates.sha256(context, RecoveryPackages.STORE)
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        check(installed.intersect(expected).isNotEmpty()) {
            "Installed LelloStore has an unexpected signing certificate"
        }
    }

    private fun extractAndVerifyRecoveryApk(expected: Set<String>): File {
        val destination = context.cacheDir.resolve("lellostore-recovery.apk")
        context.assets.open("lellostore-recovery.apk").use { input ->
            destination.outputStream().use(input::copyTo)
        }
        val expectedDigest = context.assets.open("lellostore-recovery.apk.sha256")
            .bufferedReader().use { it.readText().trim() }
        val actualDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(destination.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actualDigest == expectedDigest) { "Bundled recovery APK digest is invalid" }
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageArchiveInfo(destination.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(destination.path, PackageManager.GET_SIGNATURES)
        } ?: error("Bundled recovery APK is invalid")
        check(info.packageName == RecoveryPackages.STORE) { "Bundled APK has the wrong package name" }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.signingCertificateHistory.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        val archiveDigests = signatures.mapTo(mutableSetOf()) { signature ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        check(archiveDigests.intersect(expected).isNotEmpty()) { "Bundled APK signer is not trusted" }
        return destination
    }

    private fun install(adb: RecoveryAdbConnectionManager, apk: File, allowDowngrade: Boolean): String {
        val downgrade = if (allowDowngrade) " -d" else ""
        return adb.openStream("exec:cmd package install -r$downgrade -S ${apk.length()}").use { stream ->
            apk.inputStream().use { it.copyTo(stream.openOutputStream()) }
            readText(stream.openInputStream()).trim()
        }
    }

    private fun readText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            input.use {
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } catch (error: IOException) {
            if (output.size() == 0) throw error
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
