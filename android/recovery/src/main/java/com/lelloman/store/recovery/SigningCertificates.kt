package com.lelloman.store.recovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal object SigningCertificates {
    fun sha256(context: Context, packageName: String): Set<String> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    fun callerIsTrustedStore(context: Context, uid: Int): Boolean {
        val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        if (RecoveryPackages.STORE !in packages) return false
        return runCatching {
            sha256(context, RecoveryPackages.STORE).intersect(sha256(context, context.packageName)).isNotEmpty()
        }.getOrDefault(false)
    }
}

internal object RecoveryPackages {
    const val STORE = "com.lelloman.store"
}
