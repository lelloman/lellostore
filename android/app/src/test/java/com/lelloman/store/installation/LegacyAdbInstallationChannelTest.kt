package com.lelloman.store.installation

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

class LegacyAdbInstallationChannelTest {
    private val channel = LegacyAdbInstallationChannel(mockk<Context>(relaxed = true))

    @Test
    fun `successful package manager response is installed`() {
        assertThat(channel.parseInstallResponse("Success"))
            .isEqualTo(ChannelInstallationResult.Installed)
    }

    @Test
    fun `package manager failure is definitive`() {
        val result = channel.parseInstallResponse("Failure [INSTALL_FAILED_INVALID_APK]")

        assertThat(result).isEqualTo(
            ChannelInstallationResult.Failed(
                reason = "Failure [INSTALL_FAILED_INVALID_APK]",
                canTryNextChannel = false,
            )
        )
    }

    @Test
    fun `empty package manager response is reported`() {
        assertThat(channel.parseInstallResponse(""))
            .isEqualTo(
                ChannelInstallationResult.Failed(
                    reason = "ADB package manager returned no result",
                    canTryNextChannel = false,
                )
            )
    }
}
