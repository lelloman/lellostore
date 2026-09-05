package com.lelloman.store.recovery

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.lelloman.store.recovery.protocol.IRecoveryService
import com.lelloman.store.recovery.protocol.RecoveryContract

class RecoveryService : Service() {
    private val attemptStore by lazy { RecoveryAttemptStore(this) }
    private val identityStore by lazy { EncryptedIdentityStore(this) }
    private val snapshot by lazy { RecoverySnapshot(this) }

    private val binder = object : IRecoveryService.Stub() {
        override fun protocolVersion(): Int = authorized {
            RecoveryContract.PROTOCOL_VERSION
        }

        override fun backupStoreApk(apk: ParcelFileDescriptor, sha256: String, versionCode: Int): Boolean = authorized {
            apk.use {
                val current = attemptStore.read()
                if (current != null && current.status !in setOf(RecoveryStatus.HEALTHY, RecoveryStatus.IDLE)) return@authorized false
                runCatching { snapshot.save(it, sha256, versionCode) }.isSuccess
            }
        }

        override fun testRecoveryConnection(): String = authorized(serialized = false) {
            RecoveryEngine(this@RecoveryService).testConnection().fold(
                { "" }, { it.message ?: "Recovery connection failed" },
            )
        }

        override fun pairRecoveryWireless(code: String): String = authorized(serialized = false) {
            runCatching { RecoveryWireless.pair(this@RecoveryService, code) }.fold(
                { testRecoveryConnection() }, { it.message ?: "Recovery pairing failed" },
            )
        }

        override fun startUpdatedStore() = authorized(serialized = false) {
            val attempt = attemptStore.read()
            if (attempt?.status == RecoveryStatus.AWAITING_HEALTH) {
                runCatching { RecoveryEngine(this@RecoveryService).launchStore() }
            }
            Unit
        }

        override fun recordUpdateAttempt(
            attemptId: String,
            currentVersion: Int,
            targetVersion: Int,
            packageName: String,
            expectedSignerSha256: String,
            startedAtMillis: Long,
            deadlineAtMillis: Long,
        ): Boolean = authorized {
            if (packageName != RecoveryContract.STORE_PACKAGE) return@authorized false
            val ownSigners = SigningCertificates.sha256(this@RecoveryService, this@RecoveryService.packageName)
            if (expectedSignerSha256.lowercase() !in ownSigners) return@authorized false
            if (runCatching { snapshot.verifiedApk(currentVersion); identityStore.restore() != null }.getOrDefault(false).not()) {
                return@authorized false
            }
            val incoming = RecoveryAttempt(
                id = attemptId,
                packageName = packageName,
                currentVersion = currentVersion,
                targetVersion = targetVersion,
                expectedSignerSha256 = expectedSignerSha256.lowercase(),
                startedAtMillis = startedAtMillis,
                deadlineAtMillis = deadlineAtMillis,
                status = RecoveryStatus.AWAITING_HEALTH,
            )
            RecoveryPolicy.record(attemptStore.read(), incoming)?.let {
                attemptStore.write(it)
                RecoveryDeadlineScheduler.schedule(this@RecoveryService, it.deadlineAtMillis)
                true
            } ?: false
        }

        override fun acknowledgeHealth(attemptId: String, installedVersion: Int): Boolean = authorized {
            RecoveryPolicy.acknowledge(
                attemptStore.read(),
                attemptId,
                installedVersion,
                System.currentTimeMillis(),
            )?.let {
                attemptStore.write(it)
                RecoveryDeadlineScheduler.cancel(this@RecoveryService)
                true
            } ?: false
        }

        override fun cancelUnreplacedAttempt(
            attemptId: String,
            reason: String,
            installedVersion: Int,
        ): Boolean = authorized {
            RecoveryPolicy.cancelUnreplaced(
                attemptStore.read(),
                attemptId,
                installedVersion,
                reason.take(512),
                System.currentTimeMillis(),
            )?.let {
                attemptStore.write(it)
                RecoveryDeadlineScheduler.cancel(this@RecoveryService)
                true
            } ?: false
        }

        override fun backupStoreIdentity(privateKey: ByteArray, certificate: ByteArray): Boolean = authorized {
            runCatching { identityStore.backup(privateKey, certificate) }.isSuccess
        }

        override fun restoreStorePrivateKey(): ByteArray = authorized {
            identityStore.restore()?.first ?: byteArrayOf()
        }

        override fun restoreStoreCertificate(): ByteArray = authorized {
            identityStore.restore()?.second ?: byteArrayOf()
        }

        override fun pendingAttemptId(): String = authorized {
            attemptStore.read()?.takeIf {
                it.status == RecoveryStatus.AWAITING_HEALTH ||
                    it.status == RecoveryStatus.NEEDS_ATTENTION ||
                    it.status == RecoveryStatus.REPAIRING
            }?.id.orEmpty()
        }

        override fun pendingTargetVersion(): Int = authorized {
            attemptStore.read()?.let { if (it.destructiveAttempts > 0) it.currentVersion else it.targetVersion } ?: -1
        }

        private fun <T> authorized(serialized: Boolean = true, block: () -> T): T {
            check(SigningCertificates.callerIsTrustedStore(this@RecoveryService, Binder.getCallingUid())) {
                "Caller is not the release-signed LelloStore"
            }
            return if (serialized) synchronized(RecoveryService::class.java) { block() } else block()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
