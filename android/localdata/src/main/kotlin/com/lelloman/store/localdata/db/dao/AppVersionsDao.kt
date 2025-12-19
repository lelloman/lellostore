package com.lelloman.store.localdata.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lelloman.store.localdata.db.entity.CachedAppVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppVersionsDao {
    @Query("SELECT * FROM cached_app_versions WHERE package_name = :packageName ORDER BY version_code DESC")
    fun watchVersions(packageName: String): Flow<List<CachedAppVersionEntity>>

    @Query("SELECT * FROM cached_app_versions WHERE package_name = :packageName ORDER BY version_code DESC")
    suspend fun getVersions(packageName: String): List<CachedAppVersionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersions(versions: List<CachedAppVersionEntity>)

    @Query("DELETE FROM cached_app_versions WHERE package_name = :packageName")
    suspend fun deleteVersions(packageName: String)
}
