package com.lelloman.store.installation

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.logger.Logger
import com.lelloman.store.notification.NotificationHelper
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SelfUpdateTrackerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val notifications = mockk<NotificationHelper>(relaxed = true)
    private val installedApps = mockk<InstalledAppsRepository>(relaxed = true)

    /** A new instance per call stands for a new process reading the same storage. */
    private fun process() = SelfUpdateTracker(
        context,
        notifications,
        installedApps,
        mockk<Logger>(relaxed = true),
        CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `the next process start reports a self-update the old process could not finish`() {
        process().installing(targetVersionCode = 42)

        val next = process()
        val outcome = next.onProcessStart(runningVersionCode = 42, runningVersionName = "1.4.2")

        assertThat(outcome).isEqualTo(SelfUpdateOutcome.Updated("1.4.2"))
        assertThat(next.announcement.value).isEqualTo("1.4.2")
        verify { notifications.showSelfUpdatedNotification("1.4.2") }
        verify { notifications.cancelOperationNotification(NotificationHelper.DOWNLOAD_SERVICE_NOTIFICATION_ID) }
        verify { notifications.cancelOperationNotification(NotificationHelper.BACKGROUND_UPDATE_NOTIFICATION_ID) }
        coVerify { installedApps.refreshInstalledApp(context.packageName) }
    }

    @Test
    fun `the report is shown once`() {
        process().installing(targetVersionCode = 42)
        val next = process()
        next.onProcessStart(42, "1.4.2")

        next.announced()

        assertThat(next.announcement.value).isNull()
        verify { notifications.cancelSelfUpdatedNotification() }
        assertThat(process().onProcessStart(42, "1.4.2")).isEqualTo(SelfUpdateOutcome.None)
    }

    @Test
    fun `a self-update that did not replace the app is dropped silently`() {
        process().installing(targetVersionCode = 42)

        val next = process()
        assertThat(next.onProcessStart(41, "1.4.1")).isEqualTo(SelfUpdateOutcome.NotApplied)

        assertThat(next.announcement.value).isNull()
        verify(exactly = 0) { notifications.showSelfUpdatedNotification(any()) }
        assertThat(process().onProcessStart(42, "1.4.2")).isEqualTo(SelfUpdateOutcome.None)
    }

    @Test
    fun `an abandoned self-update is not reported`() {
        val tracker = process()
        tracker.installing(targetVersionCode = 42)
        tracker.abandoned()

        assertThat(process().onProcessStart(42, "1.4.2")).isEqualTo(SelfUpdateOutcome.None)
        verify(exactly = 0) { notifications.showSelfUpdatedNotification(any()) }
        verify(exactly = 0) { notifications.cancelOperationNotification(any()) }
    }
}
