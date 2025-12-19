package com.lelloman.store.localdata.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lelloman.store.localdata.db.entity.InstalledAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledAppsDao {
    @Query("SELECT * FROM installed_apps ORDER BY package_name ASC")
    fun watchAll(): Flow<List<InstalledAppEntity>>

    @Query("SELECT * FROM installed_apps WHERE package_name = :packageName")
    fun watch(packageName: String): Flow<InstalledAppEntity?>

    @Query("SELECT * FROM installed_apps WHERE package_name = :packageName")
    suspend fun get(packageName: String): InstalledAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: InstalledAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<InstalledAppEntity>)

    @Query("DELETE FROM installed_apps WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM installed_apps")
    suspend fun deleteAll()
}
