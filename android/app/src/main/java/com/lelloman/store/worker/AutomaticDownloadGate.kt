package com.lelloman.store.worker

import android.content.Context
import android.net.ConnectivityManager
import com.lelloman.store.domain.preferences.UserPreferencesStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the Wi‑Fi only preference to unattended update downloads. User-initiated
 * downloads are never gated: tapping install is an explicit choice to use the current network.
 */
@Singleton
class AutomaticDownloadGate @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: UserPreferencesStore,
    private val workManagerInitializer: WorkManagerInitializer,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Returns true when automatic downloads must wait; a check is then queued for an unmetered network. */
    fun deferUntilUnmetered(): Boolean {
        if (!preferences.wifiOnlyDownloads.value || !connectivity.isActiveNetworkMetered) return false
        workManagerInitializer.enqueueUnmeteredUpdateCheck()
        return true
    }
}
