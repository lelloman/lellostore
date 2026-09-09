package com.lelloman.store.interactor

import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.worker.WorkManagerInitializer
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CatalogInteractorImplTest {

    private val appsRepository = mockk<AppsRepository>()
    private val workManagerInitializer = mockk<WorkManagerInitializer>(relaxed = true)
    private val interactor = CatalogInteractorImpl(
        appsRepository = appsRepository,
        installedAppsRepository = mockk<InstalledAppsRepository>(),
        downloadManager = mockk<DownloadManager>(),
        workManagerInitializer = workManagerInitializer,
    )

    @Test
    fun `successful catalog refresh triggers immediate update check`() = runTest {
        coEvery { appsRepository.refreshApps() } returns Result.success(Unit)

        interactor.refreshApps()

        verify(exactly = 1) { workManagerInitializer.enqueueImmediateUpdateCheck() }
    }

    @Test
    fun `failed catalog refresh does not trigger immediate update check`() = runTest {
        coEvery { appsRepository.refreshApps() } returns Result.failure(IllegalStateException("offline"))

        interactor.refreshApps()

        verify(exactly = 0) { workManagerInitializer.enqueueImmediateUpdateCheck() }
    }
}
