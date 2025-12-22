package com.lelloman.store.localdata.apps

import android.content.Context
import android.content.pm.PackageManager
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.model.InstalledApp
import com.lelloman.store.localdata.db.dao.AppsDao
import com.lelloman.store.localdata.db.dao.InstalledAppsDao
import com.lelloman.store.localdata.db.entity.InstalledAppEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class InstalledAppsRepositoryImpl(
    private val context: Context,
    private val installedAppsDao: InstalledAppsDao,
    private val appsDao: AppsDao,
) : InstalledAppsRepository {

    override fun watchInstalledApps(): Flow<List<InstalledApp>> {
        return installedAppsDao.watchAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun refreshInstalledApps() {
        val packageManager = context.packageManager
        val cachedApps = appsDao.watchApps()

        // Get all cached app package names
        val cachedPackages = mutableSetOf<String>()
        // We need to get the current value, so we'll query directly
        val apps = appsDao.watchApps()

        // For each cached app, check if it's installed
        val installedEntities = mutableListOf<InstalledAppEntity>()

        try {
            // Get all installed packages
            val installedPackages = packageManager.getInstalledPackages(0)

            // Get cached app package names from DB (we need a suspend query)
            // For simplicity, we'll check against known packages from the store
            installedPackages.forEach { packageInfo ->
                val packageName = packageInfo.packageName
                // Check if this package is from our store (exists in cached_apps)
                val cachedApp = appsDao.getApp(packageName)
                if (cachedApp != null) {
                    @Suppress("DEPRECATION")
                    val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        packageInfo.versionCode
                    }

                    installedEntities.add(
                        InstalledAppEntity(
                            packageName = packageName,
                            versionCode = versionCode,
                            versionName = packageInfo.versionName ?: "",
                            lastChecked = System.currentTimeMillis(),
                        )
                    )
                }
            }

            // Update the database
            installedAppsDao.deleteAll()
            installedAppsDao.insertAll(installedEntities)
        } catch (e: Exception) {
            // Log error but don't crash
        }
    }

    override suspend fun refreshInstalledApp(packageName: String) {
        val packageManager = context.packageManager
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }
            installedAppsDao.insert(
                InstalledAppEntity(
                    packageName = packageName,
                    versionCode = versionCode,
                    versionName = packageInfo.versionName ?: "",
                    lastChecked = System.currentTimeMillis(),
                )
            )
        } catch (e: PackageManager.NameNotFoundException) {
            // App is not installed, remove from database if present
            installedAppsDao.delete(packageName)
        }
    }

    override fun isInstalled(packageName: String): Flow<Boolean> {
        return installedAppsDao.watch(packageName).map { it != null }
    }

    override fun getInstalledVersion(packageName: String): Flow<InstalledApp?> {
        return installedAppsDao.watch(packageName).map { it?.toDomain() }
    }

    private fun InstalledAppEntity.toDomain(): InstalledApp = InstalledApp(
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
    )
}
