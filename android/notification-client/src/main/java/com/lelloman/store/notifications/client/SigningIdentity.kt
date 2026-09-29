package com.lelloman.store.notifications.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object SigningIdentity {
    @Suppress("DEPRECATION")
    fun certificates(context: Context, packageName: String): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
    fun requireCaller(context: Context, uid: Int, packageName: String, approved: Set<String>) {
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        require(packages.size == 1 && packages.single() == packageName) { "Unexpected notification caller" }
        val current = certificates(context, packageName)
        require(current.isNotEmpty() && current.all { it in approved }) { "Unapproved notification signing identity" }
    }
}
