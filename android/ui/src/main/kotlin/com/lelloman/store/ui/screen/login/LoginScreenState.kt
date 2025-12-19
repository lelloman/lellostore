package com.lelloman.store.ui.screen.login

data class LoginScreenState(
    val serverUrl: String = "",
    val serverUrlError: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)
