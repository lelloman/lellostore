package com.lelloman.store.localdata.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lelloman.store.domain.auth.AuthResult
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.OidcConfig
import com.lelloman.store.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthStoreImpl(
    context: Context,
    private val oidcConfig: OidcConfig?,
    scope: CoroutineScope,
    private val logger: Logger,
    private val configStore: com.lelloman.store.domain.config.ConfigStore? = null,
    private val serverDiscovery: com.lelloman.store.domain.config.ServerDiscovery? = null,
) : AuthStore {

    private val encryptedPrefs: SharedPreferences = createEncryptedPrefs(context)
    private val authService = AuthorizationService(context)

    private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    private val stateLock = Any()
    private var generation = 0L
    private var boundServer: String? = null
    private var pendingLogin: PendingLogin? = null
    private data class PendingLogin(val state: String?, val server: String, val generation: Long)

    @Volatile private var appAuthState: net.openid.appauth.AuthState? = null
    private val tokenRefreshMutex = Mutex()

    init {
        scope.launch {
            loadAuthState()
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Encryption keys and prefs file are out of sync (e.g., after reinstall).
            // Delete the corrupted prefs file and retry.
            context.deleteSharedPreferences(PREFS_NAME)
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private suspend fun loadAuthState() {
        val epoch = synchronized(stateLock) { generation }
        val server = configStore?.readServerUrl().orEmpty()
        val savedServer = encryptedPrefs.getString(KEY_SERVER, null)
        val stateJson = encryptedPrefs.getString(KEY_AUTH_STATE, null)
        val restored = try {
            if (stateJson == null || (configStore != null && (server.isBlank() || savedServer != server))) {
                null
            } else {
                val state = net.openid.appauth.AuthState.jsonDeserialize(stateJson)
                if (serverDiscovery != null) {
                    val current = serverDiscovery.discover(server).oidc
                    val request = state.lastAuthorizationResponse?.request
                    check(request?.clientId == current.clientId &&
                        request.configuration.discoveryDoc?.issuer?.toString() == current.issuerUrl) {
                        "The store's sign-in configuration changed. Sign in again."
                    }
                }
                state.takeIf { it.isAuthorized }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Could not restore the store session", e)
            null
        }
        synchronized(stateLock) {
            if (generation != epoch) return
            boundServer = if (restored != null) server else null
            appAuthState = restored
            mutableAuthState.value = if (restored != null) {
                AuthState.Authenticated(extractEmail(restored) ?: "Unknown")
            } else AuthState.NotAuthenticated
        }
    }

    private fun extractEmail(state: net.openid.appauth.AuthState?): String? {
        val idToken = state?.idToken ?: return null
        return try {
            val parts = idToken.split(".")
            if (parts.size >= 2) {
                val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
                val json = JSONObject(payload)
                json.optString("email").takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to extract email from ID token", e)
            null
        }
    }

    override suspend fun getAccessToken(): String? = tokenRefreshMutex.withLock {
        val state = appAuthState ?: return@withLock null
        if (configStore != null && boundServer != configStore.readServerUrl()) return@withLock null
        val epoch = synchronized(stateLock) { generation }

        val refresh = state.needsTokenRefresh
        val requestId = java.util.UUID.randomUUID().toString()
        if (refresh) logger.audit("auth.refresh_started", mapOf("request_id" to requestId))
        suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                if (refresh || ex != null) logger.audit("auth.refresh_finished", mapOf("request_id" to requestId, "success" to (ex == null), "error_code" to ex?.code))
                synchronized(stateLock) {
                    if (epoch != generation || appAuthState !== state) {
                        cont.resume(null)
                    } else if (ex != null) {
                        logger.e(TAG, "Token refresh failed", ex)
                        cont.resume(null)
                    } else {
                        saveAuthState(state)
                        cont.resume(accessToken)
                    }
                }
            }
        }
    }

    override suspend fun logout() {
        synchronized(stateLock) {
            generation++
            pendingLogin = null
            boundServer = null
            appAuthState = null
            encryptedPrefs.edit { remove(KEY_AUTH_STATE); remove(KEY_SERVER) }
            mutableAuthState.value = AuthState.NotAuthenticated
        }
    }

    suspend fun createAuthIntent(): Intent {
        val epoch = synchronized(stateLock) { generation }
        val server = configStore?.readServerUrl().orEmpty()
        val oidcConfig = if (serverDiscovery != null) serverDiscovery.discover(server).oidc
            else requireNotNull(oidcConfig)
        val serviceConfig = discoverServiceConfiguration(oidcConfig)

        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            oidcConfig.clientId,
            ResponseTypeValues.CODE,
            oidcConfig.redirectUri.toUri()
        )
            .setScopes(oidcConfig.scopes)
            .build()

        synchronized(stateLock) {
            check(generation == epoch) { "Server changed during sign-in. Try again." }
            pendingLogin = PendingLogin(authRequest.state, server, epoch)
        }
        logger.audit("ipc.authorization_requested")
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    private suspend fun discoverServiceConfiguration(oidcConfig: OidcConfig): AuthorizationServiceConfiguration =
        suspendCancellableCoroutine { continuation ->
            logger.audit("auth.discovery_started")
            AuthorizationServiceConfiguration.fetchFromIssuer(
                oidcConfig.issuerUrl.toUri(),
            ) { configuration, exception ->
                logger.audit("auth.discovery_finished", mapOf("success" to (configuration != null), "error_code" to exception?.code))
                if (!continuation.isActive) return@fetchFromIssuer
                if (configuration != null) {
                    continuation.resume(configuration)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException("OIDC discovery failed", exception),
                    )
                }
            }
        }

    fun handleAuthResponse(
        response: AuthorizationResponse?,
        exception: AuthorizationException?,
        onResult: (AuthResult) -> Unit,
    ) {
        logger.audit("ipc.authorization_response", mapOf("success" to (response != null && exception == null), "error_code" to exception?.code))
        if (exception != null) {
            logger.e(TAG, "Authorization failed", exception)
            onResult(AuthResult.Error(exception.message ?: "Authorization failed"))
            return
        }

        if (response == null) {
            onResult(AuthResult.Cancelled)
            return
        }

        val pending = synchronized(stateLock) { pendingLogin }
        if (pending == null || pending.state != response.request.state ||
            (configStore != null && pending.server != configStore.serverUrl.value)) {
            onResult(AuthResult.Error("Sign-in belongs to an old server selection. Try again."))
            return
        }
        // Create AuthState from the authorization response
        val newAuthState = net.openid.appauth.AuthState(response, exception)

        val tokenRequest = response.createTokenExchangeRequest()
        logger.audit("auth.exchange_started")
        authService.performTokenRequest(tokenRequest) { tokenResponse, tokenException ->
            synchronized(stateLock) {
                if (pending.generation != generation || pendingLogin != pending) {
                    onResult(AuthResult.Error("Server changed during sign-in. Try again."))
                } else {
                    boundServer = pending.server
                    pendingLogin = null
                    handleTokenResponse(newAuthState, tokenResponse, tokenException, onResult)
                }
            }
        }
    }

    private fun handleTokenResponse(
        authState: net.openid.appauth.AuthState,
        response: TokenResponse?,
        exception: AuthorizationException?,
        onResult: (AuthResult) -> Unit,
    ) {
        logger.audit("auth.exchange_finished", mapOf("success" to (response != null && exception == null), "error_code" to exception?.code))
        // Update the auth state with the token response
        authState.update(response, exception)

        if (exception != null) {
            logger.e(TAG, "Token exchange failed", exception)
            onResult(AuthResult.Error(exception.message ?: "Token exchange failed"))
            return
        }

        if (response == null) {
            onResult(AuthResult.Error("No token response"))
            return
        }

        appAuthState = authState
        saveAuthState(authState)

        val email = extractEmail(appAuthState)
        mutableAuthState.value = AuthState.Authenticated(email ?: "Unknown")
        onResult(AuthResult.Success)
    }

    private fun saveAuthState(state: net.openid.appauth.AuthState) {
        encryptedPrefs.edit {
            putString(KEY_AUTH_STATE, state.jsonSerializeString())
            putString(KEY_SERVER, boundServer)
        }
    }

    companion object {
        private const val TAG = "AuthStoreImpl"
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_AUTH_STATE = "auth_state"
        private const val KEY_SERVER = "server_origin"
    }
}
