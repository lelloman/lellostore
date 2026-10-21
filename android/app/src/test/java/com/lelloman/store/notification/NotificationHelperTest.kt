package com.lelloman.store.notification

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.preferences.ReleaseChannel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class NotificationHelperTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `notification title identifies stable updates`() {
        assertThat(NotificationHelper.formatUpdateTitle(resources, listOf(ReleaseChannel.Stable)))
            .isEqualTo("1 stable update available")
    }

    @Test
    fun `notification title identifies beta updates`() {
        assertThat(
            NotificationHelper.formatUpdateTitle(
                resources,
                listOf(ReleaseChannel.Beta, ReleaseChannel.Beta),
            ),
        ).isEqualTo("2 beta updates available")
    }

    @Test
    fun `notification title identifies mixed release channels`() {
        assertThat(
            NotificationHelper.formatUpdateTitle(
                resources,
                listOf(ReleaseChannel.Stable, ReleaseChannel.Beta),
            ),
        ).isEqualTo("2 stable and beta updates available")
    }

    @Test
    fun `operation outcome ids are per package and avoid fixed ids`() {
        val first = NotificationHelper.operationOutcomeNotificationId("com.example.one")
        val second = NotificationHelper.operationOutcomeNotificationId("com.example.two")
        assertThat(first).isNotEqualTo(second)
        assertThat(first).isEqualTo(NotificationHelper.operationOutcomeNotificationId("com.example.one"))
        assertThat(first).isGreaterThan(NotificationHelper.DOWNLOAD_SERVICE_NOTIFICATION_ID)
    }
}
