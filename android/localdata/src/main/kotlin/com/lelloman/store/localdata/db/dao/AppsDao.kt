package com.lelloman.store.localdata.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lelloman.store.localdata.db.entity.CachedAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppsDao {
    @Query("SELECT * FROM cached_apps ORDER BY name ASC")
    fun watchApps(): Flow<List<CachedAppEntity>>

    @Query("SELECT * FROM cached_apps WHERE package_name = :packageName")
    fun watchApp(packageName: String): Flow<CachedAppEntity?>

    @Query("SELECT * FROM cached_apps WHERE package_name = :packageName")
    suspend fun getApp(packageName: String): CachedAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<CachedAppEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: CachedAppEntity)

    @Query("DELETE FROM cached_apps")
    suspend fun deleteAll()

    @Query("DELETE FROM cached_apps WHERE package_name = :packageName")
    suspend fun deleteApp(packageName: String)
}
