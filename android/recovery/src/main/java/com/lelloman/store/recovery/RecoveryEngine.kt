package com.lelloman.store.recovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class RecoveryEngine(private val context: Context) {
    fun testConnection(): Result<String> = adbOperation {
        val adb = connect()
        readText(adb.openStream("shell:id; getprop ro.product.model").openInputStream()).trim().also {
            check(it.contains("uid=2000(shell)")) { "ADB did not provide a shell" }
        }
    }

    fun repair(attempt: RecoveryAttempt): Result<String> = adbOperation {
        require(attempt.status == RecoveryStatus.REPAIRING) { "Repair was not explicitly approved" }
        val expectedSigner = SigningCertificates.sha256(context, context.packageName)
        check(attempt.expectedSignerSha256 in expectedSigner) { "Attempt signer does not match recovery signer" }
        verifyInstalledStoreSigner(expectedSigner)

        val recoveryApk = RecoverySnapshot(context).verifiedApk(attempt.currentVersion)
        val adb = connect()

        val inPlace = install(adb, recoveryApk, allowDowngrade = true)
        if (!inPlace.startsWith("Success")) {
            check(inPlace.startsWith("Failure [")) { "In-place repair result is uncertain; inspect Store before proceeding" }
            val uninstall = readText(
                adb.openStream("shell:cmd package uninstall ${RecoveryPackages.STORE}").openInputStream()
            ).trim()
            check(uninstall.startsWith("Success")) { "Store uninstall failed: $uninstall" }
            val cleanInstall = install(adb, recoveryApk, allowDowngrade = false)
            check(cleanInstall.startsWith("Success")) { "Recovery install failed: $cleanInstall" }
        }
        "Known-good LelloStore installed; waiting for identity handover and health acknowledgement"
    }

    fun launchStore() = adbOperation {
        val adb = connect()
        readText(adb.openStream(
            "shell:am start -n ${RecoveryPackages.STORE}/com.lelloman.store.MainActivity"
        ).openInputStream())
    }.getOrThrow()

    private fun <T> adbOperation(block: () -> T): Result<T> = runCatching {
        synchronized(RecoveryAdbConnectionManager::class.java) {
            val adb = RecoveryAdbConnectionManager.getInstance(context)
            val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
            val expiry = watchdog.schedule({
                runCatching { adb.disconnect() }
            }, 60, java.util.concurrent.TimeUnit.SECONDS)
            try {
                block()
            } finally {
                expiry.cancel(false)
                watchdog.shutdownNow()
            }
        }
    }

    private fun connect(): RecoveryAdbConnectionManager {
        val adb = RecoveryAdbConnectionManager.getInstance(context)
        if (adb.isConnected) adb.disconnect()
        if (runCatching { adb.connectTls(context, 10_000) }.getOrDefault(false)) return adb
        if (adb.isConnected) adb.disconnect()
        check(adb.connect("127.0.0.1", 5555)) {
            "Recovery is not connected. Enable Wireless Debugging and pair the recovery identity, or provision port 5555."
        }
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
