package com.lelloman.store.ui.screen.login

import android.content.Intent

interface AuthIntentProvider {
    fun createAuthIntent(): Intent
}
