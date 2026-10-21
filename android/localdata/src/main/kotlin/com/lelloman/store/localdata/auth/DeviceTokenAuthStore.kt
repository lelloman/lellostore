package com.lelloman.store.localdata.auth

import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.config.ServerAddress
import com.lelloman.store.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The device credential: a device token from enrollment, kept in the Keystore-encrypted
 * [credentials], or one that root provisioned as the legacy [legacyFile]
 * (`noBackupFilesDir/device-token.json`).
 *
 * It is loaded once per process. A legacy file that has not been imported yet is copied into
 * [credentials] on load; until that copy succeeds the file itself is used. A server rejection only
 * changes the in-memory mode, so a restart retries the stored credential.
 */
class DeviceTokenAuthStore(
    private val legacyFile: File,
    private val credentials: DeviceCredentialStore,
    private val configStore: ConfigStore,
    scope: CoroutineScope,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Mode {
        data object Loading : Mode
        data object Absent : Mode
        data class Provisioned(val token: DeviceToken) : Mode
        data class Rejected(val name: String) : Mode
    }

    private val mutableMode = MutableStateFlow<Mode>(Mode.Loading)
    val mode: StateFlow<Mode> = mutableMode.asStateFlow()

    // Serializes loading, enrollment and forgetting, which all write the stored credential.
    private val writeLock = Mutex()

    init {
        scope.launch { load() }
    }

    private suspend fun load() = writeLock.withLock {
        val loaded = try {
            resolve()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.audit("auth.device_token_invalid", mapOf("reason" to "load_failed"))
            logger.e(TAG, "Could not load the device token", e)
            Mode.Absent
        }
        mutableMode.compareAndSet(Mode.Loading, loaded)
    }

    private suspend fun resolve(): Mode {
        val token = withContext(ioDispatcher) { loadToken() } ?: return Mode.Absent
        if (!applyServer(token)) {
            logger.audit("auth.device_token_invalid", mapOf("reason" to "server_not_applied"))
            return Mode.Absent
        }
        logger.audit("auth.device_token_loaded", mapOf("name" to token.name))
        return Mode.Provisioned(token)
    }

    /** The stored credential, after importing a legacy file that was not imported before. */
    private fun loadToken(): DeviceToken? {
        val stored = credentials.read()
        if (stored is DeviceTokenFile.Result.Invalid) {
            logger.audit("auth.device_credential_invalid", mapOf("reason" to stored.reason))
        }
        val legacy = when (val result = DeviceTokenFile.read(legacyFile)) {
            DeviceTokenFile.Result.Absent -> null
            is DeviceTokenFile.Result.Invalid -> {
                logger.audit("auth.device_token_invalid", mapOf("reason" to result.reason))
                null
            }
            is DeviceTokenFile.Result.Valid -> result.token
        }
        val storedToken = (stored as? DeviceTokenFile.Result.Valid)?.token
        if (legacy != null) {
            val fingerprint = DeviceCredentialStore.fingerprint(legacy)
            // An already imported file is ignored: the stored credential may be a newer enrollment,
            // or the device was forgotten. A different file means root provisioned it again; an
            // unreadable stored credential falls back to the file.
            val imported = credentials.importedLegacyFingerprint() == fingerprint
            if (!imported || stored is DeviceTokenFile.Result.Invalid) return import(legacy, fingerprint)
        }
        return storedToken
    }

    private fun import(token: DeviceToken, fingerprint: String): DeviceToken {
        try {
            credentials.write(token)
            credentials.recordLegacyImport(fingerprint)
            logger.audit("auth.device_token_imported", mapOf("name" to token.name))
        } catch (e: Exception) {
            // Keep using the file; the next process start retries the import.
            logger.audit("auth.device_token_import_failed", mapOf("error_type" to e.javaClass.simpleName))
            logger.w(TAG, "Could not import the device token file", e)
        }
        return token
    }

    private suspend fun applyServer(token: DeviceToken): Boolean {
        if (normalized(configStore.readServerUrl()) == token.serverUrl) return true
        // Only the server URL is switched here. The OIDC session stays bound to the old
        // server (AuthStoreImpl refuses to use it elsewhere) and the catalog cache is
        // replaced by the next refresh, so ServerSelection's wipe is not needed.
        if (configStore.setServerUrl(token.serverUrl) != ConfigStore.SetServerUrlResult.Success) return false
        logger.audit("auth.device_token_server_applied", mapOf("server" to token.serverUrl))
        return true
    }

    /**
     * Stores [token] (from device enrollment) and signs in with it at once, replacing any earlier
     * device credential. Throws when it could not be stored or its server could not be selected;
     * the mode is then unchanged.
     */
    suspend fun provision(token: DeviceToken) {
        mode.first { it != Mode.Loading }
        writeLock.withLock {
            withContext(ioDispatcher) { credentials.write(token) }
            check(applyServer(token)) { "Could not select the device's store" }
            mutableMode.value = Mode.Provisioned(token)
            logger.audit("auth.device_token_enrolled", mapOf("name" to token.name))
        }
    }

    /**
     * Deletes the device credential, and the legacy file if there is one, so that this device is
     * signed out for good. The legacy import record is kept, so a file root fails to remove is not
     * imported again. Returns false when something could not be deleted (the mode still ends).
     */
    suspend fun forget(): Boolean {
        mode.first { it != Mode.Loading }
        return writeLock.withLock {
            val deleted = withContext(ioDispatcher) {
                val stored = credentials.clear()
                val legacy = !legacyFile.exists() || legacyFile.delete()
                stored && legacy
            }
            mutableMode.value = Mode.Absent
            logger.audit("auth.device_token_forgotten", mapOf("complete" to deleted))
            deleted
        }
    }

    /** The token, only while it is active and the selected server is the one it was issued for. */
    suspend fun getAccessToken(): String? {
        val current = mode.first { it != Mode.Loading } as? Mode.Provisioned ?: return null
        return current.token.token.takeIf { normalized(configStore.readServerUrl()) == current.token.serverUrl }
    }

    /** The server refused the token (or the user left device-token mode). The credential is kept. */
    fun reject() {
        var rejected = false
        mutableMode.update { current ->
            if (current is Mode.Provisioned) {
                rejected = true
                Mode.Rejected(current.token.name)
            } else current
        }
        if (rejected) logger.audit("auth.device_token_rejected")
    }

    private fun normalized(url: String): String? = runCatching { ServerAddress.normalize(url) }.getOrNull()

    private companion object {
        const val TAG = "DeviceTokenAuthStore"
    }
}
