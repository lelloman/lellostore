package com.lelloman.store.domain.api

import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import java.io.InputStream

interface RemoteApiClient {
    suspend fun getApps(): Result<List<App>>
    suspend fun getApp(packageName: String): Result<AppDetail>
    suspend fun downloadApk(packageName: String, versionCode: Int): Result<InputStream>
}
