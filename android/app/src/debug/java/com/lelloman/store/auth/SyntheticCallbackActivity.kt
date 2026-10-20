package com.lelloman.store.auth

/** Debug-only receiver for synthetic local-server qualification. */
class SyntheticCallbackActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        lastCallback = intent.dataString
        finish()
    }
    companion object { @Volatile var lastCallback: String? = null }
}
