package com.lelloman.store.localdata.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lelloman.store.localdata.db.dao.AppVersionsDao
import com.lelloman.store.localdata.db.dao.AppsDao
import com.lelloman.store.localdata.db.dao.InstalledAppsDao
import com.lelloman.store.localdata.db.entity.CachedAppEntity
import com.lelloman.store.localdata.db.entity.CachedAppVersionEntity
import com.lelloman.store.localdata.db.entity.InstalledAppEntity

@Database(
    entities = [
        CachedAppEntity::class,
        CachedAppVersionEntity::class,
        InstalledAppEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LellostoreDatabase : RoomDatabase() {
    abstract fun appsDao(): AppsDao
    abstract fun appVersionsDao(): AppVersionsDao
    abstract fun installedAppsDao(): InstalledAppsDao
}
