package com.lelloman.store.domain.apps

import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import kotlinx.coroutines.flow.Flow

interface AppsRepository {
    fun watchApps(): Flow<List<App>>
    fun watchApp(packageName: String): Flow<AppDetail?>
    suspend fun refreshApps(): Result<Unit>
    suspend fun refreshApp(packageName: String): Result<AppDetail>
}
