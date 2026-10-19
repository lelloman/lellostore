package com.lelloman.store.installation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.logger.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime registration of the package add/replace/remove broadcasts. Android 8+ no longer
 * delivers these implicit broadcasts to manifest receivers, so while the process is alive
 * this is what keeps the installed-app snapshot fresh after interactive installs.
 */
@Singleton
class PackageChangeMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedAppsRepository: InstalledAppsRepository,
    private val logger: Logger,
) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                runCatching {
                    handlePackageChangedIntent(context, intent, installedAppsRepository, logger)
                }.onFailure { logger.w(TAG, "Package snapshot refresh failed", it) }
            }
        }
    }

    fun start() {
        // Below API 26 the manifest-declared PackageChangedReceiver still receives these
        // broadcasts; registering here too would refresh every change twice.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!started.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            PACKAGE_CHANGE_ACTIONS.forEach(::addAction)
            addDataScheme("package")
        }
        // RECEIVER_NOT_EXPORTED: per the Context.registerReceiver documentation a non-exported
        // receiver still gets broadcasts sent by the system, and PACKAGE_ADDED/REPLACED/REMOVED
        // are protected broadcasts only the system can send. Not exporting it keeps other apps
        // from sending anything to this receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private companion object {
        const val TAG = "PackageChangeMonitor"
    }
}
