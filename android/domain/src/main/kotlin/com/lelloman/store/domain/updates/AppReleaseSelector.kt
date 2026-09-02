package com.lelloman.store.domain.updates

import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.preferences.ReleaseChannel

object AppReleaseSelector {
    fun newestUpgrade(
        versions: List<AppVersion>,
        installedVersionCode: Int,
        channel: ReleaseChannel,
    ): AppVersion? = versions
        .asSequence()
        .filter { channel == ReleaseChannel.Beta || !it.isBeta }
        .filter { it.versionCode > installedVersionCode }
        .maxByOrNull { it.versionCode }
}

object ProtectedStorePackages {
    private val packageNames = setOf(
        "com.lelloman.store",
        "com.lelloman.store.recovery",
        "com.lelloman.store.companion",
    )

    fun contains(packageName: String): Boolean = packageName in packageNames
}
