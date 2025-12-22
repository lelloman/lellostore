package com.lelloman.store.domain.apps

import com.lelloman.store.domain.model.InstalledApp
import kotlinx.coroutines.flow.Flow

interface InstalledAppsRepository {
    fun watchInstalledApps(): Flow<List<InstalledApp>>
    suspend fun refreshInstalledApps()
    suspend fun refreshInstalledApp(packageName: String)
    fun isInstalled(packageName: String): Flow<Boolean>
    fun getInstalledVersion(packageName: String): Flow<InstalledApp?>
}
