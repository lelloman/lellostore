package com.lelloman.store.domain.api

import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.ApkAcquisition
import java.io.InputStream

interface RemoteApiClient {
    suspend fun getApps(): Result<List<App>>
    suspend fun getApp(packageName: String): Result<AppDetail>
    suspend fun downloadApk(packageName: String, versionCode: Int): Result<InputStream>
    suspend fun acquireApk(packageName: String, versionCode: Int, idempotencyKey: String, purpose: com.lelloman.store.domain.model.AcquisitionPurpose = com.lelloman.store.domain.model.AcquisitionPurpose.INSTALL): Result<ApkAcquisition>
    suspend fun downloadAcquisition(acquisitionId: String): Result<InputStream>
}
