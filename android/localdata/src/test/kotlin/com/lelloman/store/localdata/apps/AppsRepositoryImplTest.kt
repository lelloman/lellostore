package com.lelloman.store.localdata.apps

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.localdata.db.dao.AppVersionsDao
import com.lelloman.store.localdata.db.dao.AppsDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class AppsRepositoryImplTest {

    @Test
    fun `refreshApps atomically replaces cached catalog so removed apps disappear`() = runTest {
        val appsDao = mockk<AppsDao>(relaxed = true)
        val remoteApiClient = mockk<RemoteApiClient>()
        val repository = AppsRepositoryImpl(
            appsDao = appsDao,
            appVersionsDao = mockk<AppVersionsDao>(relaxed = true),
            remoteApiClient = remoteApiClient,
        )
        coEvery { remoteApiClient.getApps() } returns Result.success(listOf(serverApp()))

        repository.refreshApps().getOrThrow()

        coVerify(exactly = 1) {
            appsDao.replaceApps(match { apps ->
                apps.map { it.packageName } == listOf("com.example.current")
            })
        }
    }

    @Test
    fun `refreshApp clears stale cache when no release is visible`() = runTest {
        val appsDao = mockk<AppsDao>(relaxed = true)
        val versionsDao = mockk<AppVersionsDao>(relaxed = true)
        val remoteApiClient = mockk<RemoteApiClient>()
        val repository = AppsRepositoryImpl(appsDao, versionsDao, remoteApiClient)
        coEvery { remoteApiClient.getApp("com.example.beta-only") } returns Result.success(
            AppDetail(
                packageName = "com.example.beta-only",
                name = "Beta Only",
                description = null,
                iconUrl = "",
                versions = emptyList(),
            ),
        )

        val result = repository.refreshApp("com.example.beta-only")

        assertThat(result.isFailure).isTrue()
        coVerify { versionsDao.deleteVersions("com.example.beta-only") }
        coVerify { appsDao.deleteApp("com.example.beta-only") }
    }

    private fun serverApp() = App(
        packageName = "com.example.current",
        name = "Current App",
        description = null,
        iconUrl = "https://example.com/icon.png",
        latestVersion = AppVersion(
            versionCode = 1,
            versionName = "1.0",
            size = 100,
            sha256 = "hash",
            minSdk = 24,
            uploadedAt = Instant.parse("2024-01-01T00:00:00Z"),
        ),
    )
}
