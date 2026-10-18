package com.lelloman.store.localdata.auth

import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Prefers a provisioned device token over the OIDC session; falls back to OIDC otherwise. */
class CompositeAuthStore(
    private val deviceToken: DeviceTokenAuthStore,
    private val oidc: AuthStore,
    scope: CoroutineScope,
) : AuthStore {

    // Loading until the token file has been checked, so the splash never races to login.
    override val authState: StateFlow<AuthState> = combine(deviceToken.mode, oidc.authState) { mode, oidcState ->
        when (mode) {
            DeviceTokenAuthStore.Mode.Loading -> AuthState.Loading
            is DeviceTokenAuthStore.Mode.Provisioned -> AuthState.Authenticated(mode.token.name, deviceToken = true)
            DeviceTokenAuthStore.Mode.Absent, is DeviceTokenAuthStore.Mode.Rejected -> oidcState
        }
    }.stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    override val deviceTokenRejected: StateFlow<Boolean> = deviceToken.mode
        .map { it is DeviceTokenAuthStore.Mode.Rejected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun getAccessToken(): String? =
        if (provisioned()) deviceToken.getAccessToken() else oidc.getAccessToken()

    // A device token has no expiry to refresh ahead of.
    override suspend fun getAccessToken(minValiditySeconds: Long): String? =
        if (provisioned()) deviceToken.getAccessToken() else oidc.getAccessToken(minValiditySeconds)

    override suspend fun logout() {
        // Never delete the provisioned file: the next process start retries it.
        if (provisioned()) deviceToken.reject()
        oidc.logout()
    }

    private suspend fun provisioned(): Boolean =
        deviceToken.mode.first { it != DeviceTokenAuthStore.Mode.Loading } is DeviceTokenAuthStore.Mode.Provisioned
}
