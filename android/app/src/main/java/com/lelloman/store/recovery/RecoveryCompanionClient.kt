package com.lelloman.store.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.lelloman.store.installation.SelfAdbConnectionManager
import com.lelloman.store.recovery.protocol.IRecoveryService
import com.lelloman.store.recovery.protocol.RecoveryContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.UUID

class RecoveryCompanionClient(private val context: Context) {
    suspend fun recordSelfUpdate(targetVersion: Int): String? {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
        val started = System.currentTimeMillis()
        val attemptId = UUID.randomUUID().toString()
        val accepted = call { service ->
            service.recordUpdateAttempt(
                attemptId,
                currentVersion,
                targetVersion,
                context.packageName,
                ownSignerSha256(),
                started,
                started + HEALTH_DEADLINE_MILLIS,
            )
        } == true
        return attemptId.takeIf { accepted }
    }

    suspend fun cancelUnreplacedAttempt(attemptId: String, reason: String): Boolean =
        call { it.cancelUnreplacedAttempt(attemptId, reason, installedVersion()) } == true

    suspend fun backupIdentity(adb: SelfAdbConnectionManager): Boolean =
        call { it.backupStoreIdentity(adb.privateKeyBytes, adb.certificateBytes) } == true

    suspend fun restoreIdentityIfNeeded(): Boolean {
        if (SelfAdbConnectionManager.hasStoredIdentity(context)) return true
        val identity = call { service ->
            service.restoreStorePrivateKey() to service.restoreStoreCertificate()
        } ?: return false
        if (identity.first.isEmpty() || identity.second.isEmpty()) return false
        return runCatching {
            SelfAdbConnectionManager.restoreIdentity(context, identity.first, identity.second)
        }.isSuccess
    }

    suspend fun acknowledgePendingHealth(): Boolean = call { service ->
        val attemptId = service.pendingAttemptId()
        val targetVersion = service.pendingTargetVersion()
        if (attemptId.isBlank() || installedVersion() < targetVersion) false
        else service.acknowledgeHealth(attemptId, installedVersion())
    } == true

    private suspend fun <T> call(block: (IRecoveryService) -> T): T? = withContext(Dispatchers.IO) {
        val connected = CompletableDeferred<IRecoveryService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete(IRecoveryService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!connected.isCompleted) connected.complete(null)
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!connected.isCompleted) connected.complete(null)
            }
        }
        val intent = Intent().setComponent(
            ComponentName(RecoveryContract.RECOVERY_PACKAGE, RecoveryContract.SERVICE_CLASS)
        )
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) return@withContext null
        try {
            withTimeoutOrNull(SERVICE_TIMEOUT_MILLIS) {
                connected.await()?.takeIf {
                    runCatching { it.protocolVersion() }.getOrNull() == RecoveryContract.PROTOCOL_VERSION
                }?.let { runCatching { block(it) }.getOrNull() }
            }
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun ownSignerSha256(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireNotNull(info.signingInfo) { "LelloStore has no signing information" }
                .apkContentsSigners.single()
        } else {
            @Suppress("DEPRECATION")
            requireNotNull(info.signatures) { "LelloStore has no signing information" }.single()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun installedVersion(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private companion object {
        const val SERVICE_TIMEOUT_MILLIS = 5_000L
        const val HEALTH_DEADLINE_MILLIS = 2 * 60_000L
    }
}
