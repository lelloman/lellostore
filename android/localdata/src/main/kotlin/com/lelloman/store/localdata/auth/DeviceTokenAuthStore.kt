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
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Provisioned device-token credential. The file is loaded once per process; a server rejection
 * only changes the in-memory mode, so re-provisioning (or a restart) retries the file.
 */
class DeviceTokenAuthStore(
    private val file: File,
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

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
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
        val token = when (val result = withContext(ioDispatcher) { DeviceTokenFile.read(file) }) {
            DeviceTokenFile.Result.Absent -> return Mode.Absent
            is DeviceTokenFile.Result.Invalid -> {
                logger.audit("auth.device_token_invalid", mapOf("reason" to result.reason))
                return Mode.Absent
            }
            is DeviceTokenFile.Result.Valid -> result.token
        }
        if (normalized(configStore.readServerUrl()) != token.serverUrl) {
            // Only the server URL is switched here. The OIDC session stays bound to the old
            // server (AuthStoreImpl refuses to use it elsewhere) and the catalog cache is
            // replaced by the next refresh, so ServerSelection's wipe is not needed.
            if (configStore.setServerUrl(token.serverUrl) != ConfigStore.SetServerUrlResult.Success) {
                logger.audit("auth.device_token_invalid", mapOf("reason" to "server_not_applied"))
                return Mode.Absent
            }
            logger.audit("auth.device_token_server_applied", mapOf("server" to token.serverUrl))
        }
        logger.audit("auth.device_token_loaded", mapOf("name" to token.name))
        return Mode.Provisioned(token)
    }

    /** The token, only while it is active and the selected server is the one it was issued for. */
    suspend fun getAccessToken(): String? {
        val current = mode.first { it != Mode.Loading } as? Mode.Provisioned ?: return null
        return current.token.token.takeIf { normalized(configStore.readServerUrl()) == current.token.serverUrl }
    }

    /** The server refused the token (or the user left device-token mode). The file is kept. */
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
