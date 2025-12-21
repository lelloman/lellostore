package com.lelloman.store.localdata.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "cached_app_versions",
    primaryKeys = ["package_name", "version_code"],
    foreignKeys = [
        ForeignKey(
            entity = CachedAppEntity::class,
            parentColumns = ["package_name"],
            childColumns = ["package_name"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["package_name"])]
)
data class CachedAppVersionEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "version_code")
    val versionCode: Int,
    @ColumnInfo(name = "version_name")
    val versionName: String,
    val size: Long,
    val sha256: String?,
    @ColumnInfo(name = "min_sdk")
    val minSdk: Int,
    @ColumnInfo(name = "uploaded_at")
    val uploadedAt: Long,
)
