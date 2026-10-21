package com.lelloman.store.credential

import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.credential.CredentialIssuer
import com.lelloman.store.domain.credential.CredentialIssuerRegistry
import com.lelloman.store.ui.model.AuthState
import com.lelloman.store.ui.screen.credential.IssueCredentialViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import com.lelloman.store.domain.auth.AuthState as DomainAuthState

@Singleton
class IssueCredentialInteractorImpl @Inject constructor(
    private val registry: CredentialIssuerRegistry,
    authStore: AuthStore,
) : IssueCredentialViewModel.Interactor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val authState: StateFlow<AuthState> = authStore.authState
        .map { state ->
            when (state) {
                is DomainAuthState.Loading -> AuthState.Loading
                is DomainAuthState.NotAuthenticated -> AuthState.NotAuthenticated
                is DomainAuthState.Authenticated -> AuthState.Authenticated(state.userEmail, state.deviceToken)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    override fun issuer(type: String): CredentialIssuer? = registry[type]
}
