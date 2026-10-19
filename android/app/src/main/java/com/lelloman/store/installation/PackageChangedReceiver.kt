package com.lelloman.store.installation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.logger.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the persistent installed-app snapshot synchronized with Android package manager events.
 *
 * Manifest-declared receivers stop getting these implicit broadcasts on Android 8+, so this
 * receiver only matters below API 26; [PackageChangeMonitor] registers the same handling at
 * runtime on newer versions.
 */
@AndroidEntryPoint
class PackageChangedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var installedAppsRepository: InstalledAppsRepository

    @Inject
    lateinit var logger: Logger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in PACKAGE_CHANGE_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handlePackageChangedIntent(context, intent, installedAppsRepository, logger)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal val PACKAGE_CHANGE_ACTIONS = setOf(
    Intent.ACTION_PACKAGE_ADDED,
    Intent.ACTION_PACKAGE_REPLACED,
    Intent.ACTION_PACKAGE_REMOVED,
)

internal suspend fun handlePackageChangedIntent(
    context: Context,
    intent: Intent,
    installedAppsRepository: InstalledAppsRepository,
    logger: Logger,
) = handlePackageChange(
    action = intent.action,
    packageName = intent.data?.schemeSpecificPart,
    replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
    installedAppsRepository = installedAppsRepository,
    logger = logger,
    installedVersionCode = { installedVersionCode(context, it) },
)

/**
 * Shared by [PackageChangedReceiver] and [PackageChangeMonitor]. The REMOVED half of an
 * update (EXTRA_REPLACING) is skipped because the following ADDED/REPLACED refreshes it.
 */
internal suspend fun handlePackageChange(
    action: String?,
    packageName: String?,
    replacing: Boolean,
    installedAppsRepository: InstalledAppsRepository,
    logger: Logger,
    installedVersionCode: (String) -> Long,
) {
    if (action !in PACKAGE_CHANGE_ACTIONS) return
    if (action == Intent.ACTION_PACKAGE_REMOVED && replacing) return
    if (packageName.isNullOrEmpty()) return
    logger.audit("package.changed", mapOf("package" to packageName, "action" to action))
    installedAppsRepository.refreshInstalledApp(packageName)
    logger.audit(
        "package.snapshot_refreshed",
        mapOf(
            "package" to packageName,
            "installed_version_code" to installedVersionCode(packageName),
        ),
    )
}
