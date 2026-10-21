package com.lelloman.store.ui.screen.detail

import androidx.annotation.StringRes
import com.lelloman.store.domain.download.DownloadFailureKind
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.isInProgress
import com.lelloman.store.ui.R
import com.lelloman.store.domain.preferences.AutoUpdateOverride
import com.lelloman.store.domain.preferences.ReleaseChannel
import com.lelloman.store.domain.preferences.ReleaseChannelOverride

data class AppDetailScreenState(
    val app: AppDetailUiModel? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val downloadState: DownloadState? = null,
    val downloadProgress: Float = 0f,
    val installationFailure: DownloadResult.Failed? = null,
)

data class AppDetailUiModel(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val latestVersion: AppVersionUiModel?,
    val versions: List<AppVersionUiModel>,
    val installedVersion: AppVersionUiModel?,
    val canInstall: Boolean,
    val canUpdate: Boolean,
    val canOpen: Boolean,
    val autoUpdateOverride: AutoUpdateOverride,
    val releaseChannelOverride: ReleaseChannelOverride,
    val effectiveAutoUpdate: Boolean,
    val effectiveReleaseChannel: ReleaseChannel,
    val hasBetaAccess: Boolean,
    val isPolicyConfigurable: Boolean,
    val canRepairAccess: Boolean = false,
)

data class AppVersionUiModel(
    val versionCode: Int,
    val versionName: String,
    val size: String,
    val uploadedAt: String,
)

/**
 * The card reporting the outcome of the last operation. Every card has a title and a message;
 * [technicalDetails] is the failure's own reason, when it has one.
 */
data class InstallationResultCard(
    val isError: Boolean,
    @StringRes val title: Int,
    @StringRes val message: Int,
    val technicalDetails: String?,
)

/**
 * What to report for [downloadState] and the last [failure], or `null` when there is nothing to
 * report: while an operation runs its progress section speaks for it, and a failure only
 * describes the package while no later outcome (or none at all) is shown.
 */
fun installationResultCard(downloadState: DownloadState?, failure: DownloadResult.Failed?): InstallationResultCard? =
    when {
        downloadState?.isInProgress == true -> null
        downloadState == DownloadState.COMPLETED ->
            InstallationResultCard(false, R.string.download_completed, R.string.download_completed_hint, null)
        downloadState == DownloadState.CANCELLED ->
            InstallationResultCard(false, R.string.download_cancelled, R.string.download_cancelled_hint, null)
        downloadState == DownloadState.AWAITING_CONFIRMATION -> InstallationResultCard(
            false,
            R.string.download_awaiting_confirmation,
            R.string.download_awaiting_confirmation_hint,
            null,
        )
        downloadState == DownloadState.FAILED || (downloadState == null && failure != null) -> {
            val incompatible = failure?.kind == DownloadFailureKind.INCOMPATIBLE_SIGNATURE
            InstallationResultCard(
                isError = true,
                title = if (incompatible) R.string.incompatible_signature_title else R.string.download_failed,
                message = if (incompatible) R.string.incompatible_signature_message else R.string.download_failed_hint,
                technicalDetails = failure?.reason?.takeIf { it.isNotBlank() },
            )
        }
        // PERMISSION_REQUIRED has its own section.
        else -> null
    }
