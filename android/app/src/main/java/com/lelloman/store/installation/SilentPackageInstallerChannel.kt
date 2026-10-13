package com.lelloman.store.installation

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs through PackageInstaller without a confirmation prompt (Android 12+). Android only
 * skips the prompt for updates of packages LelloStore installed itself, or of LelloStore, so
 * other installs are reported unavailable before a session is created.
 */
@Singleton
class SilentPackageInstallerChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstallationChannel {

    override val metadata = InstallationChannelMetadata(
        id = ID,
        displayName = "Android package installer (silent updates)",
        requiresUserInteraction = false,
        // After the ADB channels: Play Protect skips ADB installs (unless "Verify apps over
        // USB" is on) but asks to scan every new APK installed through PackageInstaller.
        priority = 30,
    )

    override suspend fun install(request: InstallationRequest): ChannelInstallationResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return@withContext ChannelInstallationResult.Unavailable("Requires Android 12 or newer")
            }
            val ineligibility = silentInstallIneligibility(
                sdkInt = Build.VERSION.SDK_INT,
                canRequestInstalls = context.packageManager.canRequestPackageInstalls(),
                ownPackage = context.packageName,
                targetPackage = request.packageName,
                installedVersionCode = installedVersionCode(context, request.packageName),
                installerOfRecord = installerOfRecord(request.packageName),
                apkTargetSdk = apkTargetSdk(request),
            )
            if (ineligibility != null) {
                request.audit("silent_install.ineligible", mapOf("reason" to ineligibility))
                return@withContext ChannelInstallationResult.Unavailable(ineligibility)
            }
            installSilently(request)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun installSilently(request: InstallationRequest): ChannelInstallationResult {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(request.packageName)
            setSize(request.apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId: Int
        val session: PackageInstaller.Session
        try {
            sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
        } catch (error: IOException) {
            return ChannelInstallationResult.Unavailable("Cannot create an install session: ${error.message}")
        } catch (error: SecurityException) {
            return ChannelInstallationResult.Unavailable("Cannot create an install session: ${error.message}")
        }
        request.audit("silent_install.session_created", mapOf("session_id" to sessionId))

        session.use {
            // Nothing is committed until commit(), so any failure up to there is safe to retry elsewhere.
            try {
                request.apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, request.apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            } catch (error: Exception) {
                runCatching { session.abandon() }
                return ChannelInstallationResult.Unavailable("Cannot write the install session: ${error.message}")
            }
            request.audit("silent_install.committing", emptyMap())
            // Updating LelloStore itself kills this process once the package is replaced,
            // so no result arrives in that case; the replacement receiver takes over.
            val result = commitAndAwait(session, sessionId)
            return interpretResult(request, installer, sessionId, result)
        }
    }

    private fun interpretResult(
        request: InstallationRequest,
        installer: PackageInstaller,
        sessionId: Int,
        result: Intent?,
    ): ChannelInstallationResult {
        if (result == null) {
            request.audit("silent_install.timed_out", emptyMap())
            if (installedVersionCode(context, request.packageName) >= request.versionCode) {
                return ChannelInstallationResult.Installed
            }
            return ChannelInstallationResult.Failed(
                reason = "Timed out waiting for the package installer",
                canTryNextChannel = false,
            )
        }
        val status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        request.audit("silent_install.result", mapOf("status" to status, "message" to message))
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> ChannelInstallationResult.Installed
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android decided this install needs confirmation after all. Drop the session
                // so the next channel can install without leaving a prompt behind.
                runCatching { installer.abandonSession(sessionId) }
                ChannelInstallationResult.Unavailable("Android requires confirmation for this install")
            }
            else -> ChannelInstallationResult.Failed(
                reason = message ?: "Package installer failed with status $status",
                canTryNextChannel = status in RETRYABLE_FAILURES,
            )
        }
    }

    private suspend fun commitAndAwait(session: PackageInstaller.Session, sessionId: Int): Intent? {
        val action = "${context.packageName}.SILENT_INSTALL_RESULT.$sessionId"
        val result = CompletableDeferred<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result.complete(intent)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(action).setPackage(context.packageName),
                // The installer fills in the status extras, so the intent must be mutable.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
            return withTimeoutOrNull(COMMIT_TIMEOUT_MILLIS) { result.await() }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun installerOfRecord(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = context.packageManager.getInstallSourceInfo(packageName)
            val updateOwner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.updateOwnerPackageName
            } else null
            updateOwner ?: info.installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun apkTargetSdk(request: InstallationRequest): Int? =
        context.packageManager.getPackageArchiveInfo(request.apk.absolutePath, 0)
            ?.applicationInfo?.targetSdkVersion

    companion object {
        const val ID = "silent-package-installer"
        private const val COMMIT_TIMEOUT_MILLIS = 5 * 60_000L
        private val RETRYABLE_FAILURES = setOf(
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_STORAGE,
        )
    }
}

/**
 * Returns why Android would ask for confirmation, or null when a silent install should work.
 * Mirrors the documented conditions of SessionParams.setRequireUserAction.
 */
internal fun silentInstallIneligibility(
    sdkInt: Int,
    canRequestInstalls: Boolean,
    ownPackage: String,
    targetPackage: String,
    installedVersionCode: Long,
    installerOfRecord: String?,
    apkTargetSdk: Int?,
): String? {
    if (sdkInt < Build.VERSION_CODES.S) return "Requires Android 12 or newer"
    if (!canRequestInstalls) return "Install unknown apps permission is not granted"
    if (installedVersionCode < 0) return "First installs require confirmation"
    if (targetPackage != ownPackage && installerOfRecord != ownPackage) {
        return "LelloStore is not the installer of record (installer: ${installerOfRecord ?: "none"})"
    }
    val requiredTarget = minimumTargetSdkForSilentUpdate(sdkInt)
    if (apkTargetSdk == null) return "Cannot read the APK's target SDK"
    if (apkTargetSdk < requiredTarget) {
        return "APK targets API $apkTargetSdk; this Android version requires $requiredTarget for silent updates"
    }
    return null
}

/** Documented target SDK floors; newer Android versions may raise it, which yields a prompt we handle. */
internal fun minimumTargetSdkForSilentUpdate(sdkInt: Int): Int = when {
    sdkInt <= 32 -> 29
    sdkInt == 33 -> 30
    sdkInt == 34 -> 31
    sdkInt == 35 -> 33
    sdkInt == 36 -> 34
    else -> 35
}
