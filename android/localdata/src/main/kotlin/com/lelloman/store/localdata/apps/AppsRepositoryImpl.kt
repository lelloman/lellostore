package com.lelloman.store.localdata.apps

import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.localdata.db.dao.AppsDao
import com.lelloman.store.localdata.db.dao.AppVersionsDao
import com.lelloman.store.localdata.db.entity.CachedAppEntity
import com.lelloman.store.localdata.db.entity.CachedAppVersionEntity
import com.lelloman.store.domain.api.RemoteApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

internal class AppsRepositoryImpl(
    private val appsDao: AppsDao,
    private val appVersionsDao: AppVersionsDao,
    private val remoteApiClient: RemoteApiClient,
) : AppsRepository {

    override fun watchApps(): Flow<List<App>> {
        return appsDao.watchApps().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun watchApp(packageName: String): Flow<AppDetail?> {
        return combine(
            appsDao.watchApp(packageName),
            appVersionsDao.watchVersions(packageName),
        ) { app, versions ->
            app?.let {
                AppDetail(
                    packageName = it.packageName,
                    name = it.name,
                    description = it.description,
                    iconUrl = it.iconUrl,
                    versions = versions.map { v -> v.toDomain() },
                )
            }
        }
    }

    override suspend fun refreshApps(): Result<Unit> {
        return remoteApiClient.getApps().map { apps ->
            val entities = apps.map { it.toEntity() }
            appsDao.insertApps(entities)
        }
    }

    override suspend fun refreshApp(packageName: String): Result<AppDetail> {
        return remoteApiClient.getApp(packageName).map { appDetail ->
            val appEntity = CachedAppEntity(
                packageName = appDetail.packageName,
                name = appDetail.name,
                description = appDetail.description,
                iconUrl = appDetail.iconUrl,
                latestVersionCode = appDetail.versions.first().versionCode,
                latestVersionName = appDetail.versions.first().versionName,
                latestVersionSize = appDetail.versions.first().size,
                latestVersionSha256 = appDetail.versions.first().sha256,
                latestVersionMinSdk = appDetail.versions.first().minSdk,
                latestVersionUploadedAt = appDetail.versions.first().uploadedAt.toEpochMilliseconds(),
                updatedAt = System.currentTimeMillis(),
            )
            appsDao.insertApp(appEntity)

            val versionEntities = appDetail.versions.map { v ->
                CachedAppVersionEntity(
                    packageName = appDetail.packageName,
                    versionCode = v.versionCode,
                    versionName = v.versionName,
                    size = v.size,
                    sha256 = v.sha256,
                    minSdk = v.minSdk,
                    uploadedAt = v.uploadedAt.toEpochMilliseconds(),
                )
            }
            appVersionsDao.deleteVersions(packageName)
            appVersionsDao.insertVersions(versionEntities)

            appDetail
        }
    }

    private fun CachedAppEntity.toDomain(): App = App(
        packageName = packageName,
        name = name,
        description = description,
        iconUrl = iconUrl,
        latestVersion = AppVersion(
            versionCode = latestVersionCode,
            versionName = latestVersionName,
            size = latestVersionSize,
            sha256 = latestVersionSha256,
            minSdk = latestVersionMinSdk,
            uploadedAt = Instant.fromEpochMilliseconds(latestVersionUploadedAt),
        ),
    )

    private fun CachedAppVersionEntity.toDomain(): AppVersion = AppVersion(
        versionCode = versionCode,
        versionName = versionName,
        size = size,
        sha256 = sha256,
        minSdk = minSdk,
        uploadedAt = Instant.fromEpochMilliseconds(uploadedAt),
    )

    private fun App.toEntity(): CachedAppEntity = CachedAppEntity(
        packageName = packageName,
        name = name,
        description = description,
        iconUrl = iconUrl,
        latestVersionCode = latestVersion.versionCode,
        latestVersionName = latestVersion.versionName,
        latestVersionSize = latestVersion.size,
        latestVersionSha256 = latestVersion.sha256,
        latestVersionMinSdk = latestVersion.minSdk,
        latestVersionUploadedAt = latestVersion.uploadedAt.toEpochMilliseconds(),
        updatedAt = System.currentTimeMillis(),
    )
}
