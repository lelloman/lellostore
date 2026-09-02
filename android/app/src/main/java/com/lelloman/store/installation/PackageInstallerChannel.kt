package com.lelloman.store.installation

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageInstallerChannel @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstallationChannel {

    override val metadata = InstallationChannelMetadata(
        id = "package-installer",
        displayName = "Android Package Installer",
        requiresUserInteraction = true,
        priority = 100,
    )

    override suspend fun install(request: InstallationRequest): ChannelInstallationResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return ChannelInstallationResult.PermissionRequired(
                "Install unknown apps permission is not granted"
            )
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            request.apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return ChannelInstallationResult.UserActionStarted
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
