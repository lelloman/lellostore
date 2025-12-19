package com.lelloman.store.localdata.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_apps")
data class CachedAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "icon_url")
    val iconUrl: String,
    @ColumnInfo(name = "latest_version_code")
    val latestVersionCode: Int,
    @ColumnInfo(name = "latest_version_name")
    val latestVersionName: String,
    @ColumnInfo(name = "latest_version_size")
    val latestVersionSize: Long,
    @ColumnInfo(name = "latest_version_sha256")
    val latestVersionSha256: String,
    @ColumnInfo(name = "latest_version_min_sdk")
    val latestVersionMinSdk: Int,
    @ColumnInfo(name = "latest_version_uploaded_at")
    val latestVersionUploadedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
