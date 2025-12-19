package com.lelloman.store.localdata.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import kotlin.coroutines.resume

class AuthStoreImpl(
    context: Context,
    private val oidcConfig: OidcConfig,
    scope: CoroutineScope,
    private val logger: Logger,
) : AuthStore {

    private val encryptedPrefs: SharedPreferences = createEncryptedPrefs(context)
    private val authService = AuthorizationService(context)

    private val mutableAuthState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = mutableAuthState.asStateFlow()

    private var appAuthState: net.openid.appauth.AuthState? = null

    init {
        scope.launch {
            loadAuthState()
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun loadAuthState() {
        val stateJson = encryptedPrefs.getString(KEY_AUTH_STATE, null)
        if (stateJson != null) {
            try {
                appAuthState = net.openid.appauth.AuthState.jsonDeserialize(stateJson)
                val email = extractEmail(appAuthState)
                mutableAuthState.value = AuthState.Authenticated(email ?: "Unknown")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to load auth state", e)
                mutableAuthState.value = AuthState.NotAuthenticated
            }
        } else {
            mutableAuthState.value = AuthState.NotAuthenticated
        }
    }

    private fun extractEmail(state: net.openid.appauth.AuthState?): String? {
        val idToken = state?.idToken ?: return null
        return try {
            val parts = idToken.split(".")
            if (parts.size >= 2) {
                val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
                val json = JSONObject(payload)
                json.optString("email", null)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to extract email from ID token", e)
            null
        }
    }

    override suspend fun getAccessToken(): String? {
        val state = appAuthState ?: return null

        return suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                if (ex != null) {
                    logger.e(TAG, "Token refresh failed", ex)
                    cont.resume(null)
                } else {
                    saveAuthState(state)
                    cont.resume(accessToken)
                }
            }
        }
    }

    override suspend fun logout() {
        appAuthState = null
        encryptedPrefs.edit().remove(KEY_AUTH_STATE).apply()
        mutableAuthState.value = AuthState.NotAuthenticated
    }

    fun createAuthIntent(): Intent {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse("${oidcConfig.issuerUrl}/authorize"),
            Uri.parse("${oidcConfig.issuerUrl}/token")
        )

        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            oidcConfig.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(oidcConfig.redirectUri)
        )
            .setScopes(oidcConfig.scopes)
            .build()

        return authService.getAuthorizationRequestIntent(authRequest)
    }

    fun handleAuthResponse(
        response: AuthorizationResponse?,
        exception: AuthorizationException?,
        onResult: (AuthResult) -> Unit,
    ) {
        if (exception != null) {
            logger.e(TAG, "Authorization failed", exception)
            onResult(AuthResult.Error(exception.message ?: "Authorization failed"))
            return
        }

        if (response == null) {
            onResult(AuthResult.Cancelled)
            return
        }

        // Create AuthState from the authorization response
        val newAuthState = net.openid.appauth.AuthState(response, exception)

        val tokenRequest = response.createTokenExchangeRequest()
        authService.performTokenRequest(tokenRequest) { tokenResponse, tokenException ->
            handleTokenResponse(newAuthState, tokenResponse, tokenException, onResult)
        }
    }

    private fun handleTokenResponse(
        authState: net.openid.appauth.AuthState,
        response: TokenResponse?,
        exception: AuthorizationException?,
        onResult: (AuthResult) -> Unit,
    ) {
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
        encryptedPrefs.edit()
            .putString(KEY_AUTH_STATE, state.jsonSerializeString())
            .apply()
    }

    companion object {
        private const val TAG = "AuthStoreImpl"
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_AUTH_STATE = "auth_state"
    }
}
