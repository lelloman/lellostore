package com.lelloman.store.ui.screen.login

import android.content.Intent

interface AuthIntentProvider {
    suspend fun createAuthIntent(): Intent
}
