package com.lelloman.store.installation

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.logger.Logger
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PackageChangeHandlingTest {

    private val repository = mockk<InstalledAppsRepository>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)

    @Test
    fun `added, replaced and removed refresh the snapshot and audit`() = runTest {
        listOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED,
        ).forEach { action ->
            handle(action = action, packageName = "com.example.$action")
            coVerify(exactly = 1) { repository.refreshInstalledApp("com.example.$action") }
            verify {
                logger.audit("package.changed", mapOf("package" to "com.example.$action", "action" to action))
                logger.audit(
                    "package.snapshot_refreshed",
                    mapOf("package" to "com.example.$action", "installed_version_code" to 7L),
                )
            }
        }
    }

    @Test
    fun `removed half of an update is skipped`() = runTest {
        handle(action = Intent.ACTION_PACKAGE_REMOVED, replacing = true)
        coVerify(exactly = 0) { repository.refreshInstalledApp(any()) }
        verify(exactly = 0) { logger.audit(any(), any()) }
    }

    @Test
    fun `replacing flag does not skip added or replaced`() = runTest {
        handle(action = Intent.ACTION_PACKAGE_REPLACED, replacing = true)
        coVerify(exactly = 1) { repository.refreshInstalledApp("com.example.app") }
    }

    @Test
    fun `unsupported action or missing package is ignored`() = runTest {
        handle(action = Intent.ACTION_PACKAGE_CHANGED)
        handle(action = null)
        handle(action = Intent.ACTION_PACKAGE_ADDED, packageName = null)
        handle(action = Intent.ACTION_PACKAGE_ADDED, packageName = "")
        coVerify(exactly = 0) { repository.refreshInstalledApp(any()) }
        assertThat(PACKAGE_CHANGE_ACTIONS).hasSize(3)
    }

    private suspend fun handle(
        action: String?,
        packageName: String? = "com.example.app",
        replacing: Boolean = false,
    ) = handlePackageChange(action, packageName, replacing, repository, logger) { 7L }
}
