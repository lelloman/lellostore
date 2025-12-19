package com.lelloman.store.ui.screen.login

import android.content.Intent

sealed interface LoginScreenEvent {
    data class LaunchAuth(val intent: Intent) : LoginScreenEvent
    data object NavigateToMain : LoginScreenEvent
}
