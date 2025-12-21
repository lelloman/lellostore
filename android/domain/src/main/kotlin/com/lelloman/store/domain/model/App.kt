package com.lelloman.store.domain.model

import kotlinx.datetime.Instant

data class App(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val latestVersion: AppVersion,
)

data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val sha256: String?,
    val minSdk: Int,
    val uploadedAt: Instant,
)

data class AppDetail(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val versions: List<AppVersion>,
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
