package com.lelloman.store.installation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lelloman.store.domain.apps.InstalledAppsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps the persistent installed-app snapshot synchronized with Android package manager events. */
@AndroidEntryPoint
class PackageChangedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var installedAppsRepository: InstalledAppsRepository

    @Inject
    lateinit var logger: com.lelloman.store.logger.Logger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        ) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        logger.audit("package.changed", mapOf("package" to packageName, "action" to intent.action))
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                installedAppsRepository.refreshInstalledApp(packageName)
                logger.audit("package.snapshot_refreshed", mapOf("package" to packageName,
                    "installed_version_code" to installedVersionCode(context, packageName)))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED,
        )
    }
}
