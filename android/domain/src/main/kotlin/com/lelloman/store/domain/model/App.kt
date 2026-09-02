package com.lelloman.store.domain.model

import com.lelloman.store.domain.preferences.AppAccessLevel
import kotlinx.datetime.Instant

data class App(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val latestVersion: AppVersion,
    val accessLevel: AppAccessLevel = AppAccessLevel.Stable,
)

data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val sha256: String?,
    val minSdk: Int,
    val uploadedAt: Instant,
    val isBeta: Boolean = false,
)

data class AppDetail(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val versions: List<AppVersion>,
    val accessLevel: AppAccessLevel = AppAccessLevel.Stable,
)

data class InstalledApp(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
)

data class AvailableUpdate(
    val app: App,
    val installedVersionCode: Int,
    val installedVersionName: String,
)
