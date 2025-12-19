package com.lelloman.store.domain.auth

data class OidcConfig(
    val issuerUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = listOf("openid", "profile", "email"),
)
