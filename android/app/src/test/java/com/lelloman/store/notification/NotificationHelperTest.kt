package com.lelloman.store.notification

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.preferences.ReleaseChannel
import org.junit.Test

class NotificationHelperTest {
    @Test
    fun `notification title identifies stable updates`() {
        assertThat(NotificationHelper.formatUpdateTitle(listOf(ReleaseChannel.Stable)))
            .isEqualTo("1 stable update available")
    }

    @Test
    fun `notification title identifies beta updates`() {
        assertThat(
            NotificationHelper.formatUpdateTitle(
                listOf(ReleaseChannel.Beta, ReleaseChannel.Beta),
            ),
        ).isEqualTo("2 beta updates available")
    }

    @Test
    fun `notification title identifies mixed release channels`() {
        assertThat(
            NotificationHelper.formatUpdateTitle(
                listOf(ReleaseChannel.Stable, ReleaseChannel.Beta),
            ),
        ).isEqualTo("2 stable and beta updates available")
    }
}
