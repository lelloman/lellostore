package com.lelloman.store.domain.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthStateTest {

    @Test
    fun `AuthState Loading is a singleton`() {
        val state1: AuthState = AuthState.Loading
        val state2: AuthState = AuthState.Loading

        assertThat(state1).isSameInstanceAs(state2)
    }

    @Test
    fun `AuthState NotAuthenticated is a singleton`() {
        val state: AuthState = AuthState.NotAuthenticated

        assertThat(state).isEqualTo(AuthState.NotAuthenticated)
    }

    @Test
    fun `AuthState Authenticated contains user email`() {
        val state = AuthState.Authenticated(userEmail = "user@example.com")

        assertThat(state.userEmail).isEqualTo("user@example.com")
    }

    @Test
    fun `OidcConfig has default scopes`() {
        val config = OidcConfig(
            issuerUrl = "https://auth.example.com",
            clientId = "my-client-id",
            redirectUri = "com.lelloman.store://callback"
        )

        assertThat(config.scopes).containsExactly("openid", "profile", "email")
    }

    @Test
    fun `OidcConfig can have custom scopes`() {
        val config = OidcConfig(
            issuerUrl = "https://auth.example.com",
            clientId = "my-client-id",
            redirectUri = "com.lelloman.store://callback",
            scopes = listOf("openid", "offline_access")
        )

        assertThat(config.scopes).containsExactly("openid", "offline_access")
    }
}
