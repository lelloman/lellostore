package com.lelloman.store.localdata.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_apps")
data class InstalledAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "version_code")
    val versionCode: Int,
    @ColumnInfo(name = "version_name")
    val versionName: String,
    @ColumnInfo(name = "last_checked")
    val lastChecked: Long,
)
