package com.lelloman.store.installation

import android.content.Context
import androidx.core.content.edit
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.logger.Logger
import com.lelloman.store.notification.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a self-update across the process death it causes. Android kills LelloStore when its
 * own package is replaced, so the operation that installed the update never reports completion:
 * operation state lives in memory and dies with the old process. A marker is written
 * synchronously before the APK goes to the installer, and the next process start, whether
 * started by `MY_PACKAGE_REPLACED` or by the user, compares it with the running version.
 *
 * Every process start consumes the marker: no operation outlives its process, so whatever the
 * marker describes has ended by then.
 */
@Singleton
class SelfUpdateTracker @Inject constructor(
    @ApplicationContext context: Context,
    private val notificationHelper: NotificationHelper,
    private val installedAppsRepository: InstalledAppsRepository,
    private val logger: Logger,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val packageName = context.packageName
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableAnnouncement = MutableStateFlow<String?>(null)

    /** The version a self-update just installed, until the UI has shown it once. */
    val announcement: StateFlow<String?> = mutableAnnouncement.asStateFlow()

    /** The APK of [targetVersionCode] is about to replace the running app. */
    fun installing(targetVersionCode: Int) {
        // commit, not apply: the process can be killed before an asynchronous write lands.
        preferences.edit(commit = true) { putLong(KEY_TARGET_VERSION_CODE, targetVersionCode.toLong()) }
    }

    /** The self-update ended in this process without installing anything. */
    fun abandoned() {
        preferences.edit { remove(KEY_TARGET_VERSION_CODE) }
    }

    /** Called once per process start with the version that is running. */
    fun onProcessStart(runningVersionCode: Long, runningVersionName: String): SelfUpdateOutcome {
        if (!preferences.contains(KEY_TARGET_VERSION_CODE)) return SelfUpdateOutcome.None
        val target = preferences.getLong(KEY_TARGET_VERSION_CODE, 0)
        preferences.edit(commit = true) { remove(KEY_TARGET_VERSION_CODE) }
        // The old process may have left its ongoing operation notification behind.
        notificationHelper.cancelOperationNotification(NotificationHelper.DOWNLOAD_SERVICE_NOTIFICATION_ID)
        notificationHelper.cancelOperationNotification(NotificationHelper.BACKGROUND_UPDATE_NOTIFICATION_ID)
        val fields = mapOf("target_version_code" to target, "version_code" to runningVersionCode)
        if (runningVersionCode < target) {
            logger.audit("self_update.not_applied", fields)
            return SelfUpdateOutcome.NotApplied
        }
        logger.audit("self_update.completed", fields)
        // The package broadcast for our own replacement reached no one: the process was dead.
        scope.launch { installedAppsRepository.refreshInstalledApp(packageName) }
        mutableAnnouncement.value = runningVersionName
        notificationHelper.showSelfUpdatedNotification(runningVersionName)
        return SelfUpdateOutcome.Updated(runningVersionName)
    }

    /** The UI showed [announcement]; the notification saying the same is no longer needed. */
    fun announced() {
        mutableAnnouncement.value = null
        notificationHelper.cancelSelfUpdatedNotification()
    }

    private companion object {
        const val PREFERENCES = "self-update-tracker"
        const val KEY_TARGET_VERSION_CODE = "target_version_code"
    }
}

sealed interface SelfUpdateOutcome {
    /** No self-update was pending. */
    data object None : SelfUpdateOutcome

    /** The pending self-update ended without replacing the app. */
    data object NotApplied : SelfUpdateOutcome

    data class Updated(val versionName: String) : SelfUpdateOutcome
}
