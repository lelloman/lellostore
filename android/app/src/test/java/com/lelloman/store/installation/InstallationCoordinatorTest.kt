package com.lelloman.store.installation

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.logger.Logger
import com.lelloman.store.domain.download.InstallationMode
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallationCoordinatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logger = mockk<Logger>(relaxed = true)

    @Test
    fun `silent channel is selected before interactive channel`() = runTest {
        val interactive = fakeChannel("interactive", requiresUser = true, priority = 1)
        val silent = fakeChannel("silent", requiresUser = false, priority = 100)
        val coordinator = InstallationCoordinator(setOf(interactive, silent), logger)

        val result = coordinator.install(request())

        assertThat(result).isEqualTo(InstallationResult.Installed(silent.metadata))
        assertThat(silent.attempts).isEqualTo(1)
        assertThat(interactive.attempts).isEqualTo(0)
        assertThat(coordinator.channelMetadata.map { it.id })
            .containsExactly("silent", "interactive").inOrder()
    }

    @Test
    fun `background request excludes channels requiring user interaction`() = runTest {
        val interactive = fakeChannel("interactive", requiresUser = true)
        val coordinator = InstallationCoordinator(setOf(interactive), logger)

        val result = coordinator.install(request(InstallationMode.BACKGROUND))

        assertThat(result).isInstanceOf(InstallationResult.UserActionRequired::class.java)
        assertThat((result as InstallationResult.UserActionRequired).reasons)
            .containsExactly("No background installation channels are available")
        assertThat(interactive.attempts).isEqualTo(0)
    }

    @Test
    fun `foreground request falls back from unavailable silent channel to package installer`() = runTest {
        val silent = fakeChannel(
            id = "silent",
            requiresUser = false,
            result = ChannelInstallationResult.Unavailable("not connected"),
        )
        val interactive = fakeChannel(
            id = "interactive",
            requiresUser = true,
            result = ChannelInstallationResult.UserActionStarted,
        )
        val coordinator = InstallationCoordinator(setOf(interactive, silent), logger)

        val result = coordinator.install(request())

        assertThat(result).isEqualTo(InstallationResult.UserActionStarted(interactive.metadata))
        assertThat(silent.attempts).isEqualTo(1)
        assertThat(interactive.attempts).isEqualTo(1)
    }

    @Test
    fun `background request fails over between non-interactive channels only`() = runTest {
        val legacy = fakeChannel(
            id = "legacy-adb",
            requiresUser = false,
            priority = 10,
            result = ChannelInstallationResult.Unavailable("port closed"),
        )
        val wireless = fakeChannel(
            id = "wireless-tls-adb",
            requiresUser = false,
            priority = 20,
        )
        val interactive = fakeChannel("package-installer", requiresUser = true, priority = 100)
        val coordinator = InstallationCoordinator(setOf(interactive, wireless, legacy), logger)

        val result = coordinator.install(request(InstallationMode.BACKGROUND))

        assertThat(result).isEqualTo(InstallationResult.Installed(wireless.metadata))
        assertThat(legacy.attempts).isEqualTo(1)
        assertThat(wireless.attempts).isEqualTo(1)
        assertThat(interactive.attempts).isEqualTo(0)
    }

    @Test
    fun `definitive install failure does not try another channel`() = runTest {
        val first = fakeChannel(
            id = "first",
            requiresUser = false,
            priority = 1,
            result = ChannelInstallationResult.Failed("invalid APK"),
        )
        val second = fakeChannel("second", requiresUser = false, priority = 2)
        val coordinator = InstallationCoordinator(setOf(second, first), logger)

        val result = coordinator.install(request())

        assertThat(result).isInstanceOf(InstallationResult.Failed::class.java)
        assertThat(first.attempts).isEqualTo(1)
        assertThat(second.attempts).isEqualTo(0)
    }

    private fun request(mode: InstallationMode = InstallationMode.FOREGROUND) = InstallationRequest(
        apk = temporaryFolder.newFile(),
        packageName = "com.example.test",
        versionCode = 1,
        mode = mode,
    )

    private fun fakeChannel(
        id: String,
        requiresUser: Boolean,
        priority: Int = 1,
        result: ChannelInstallationResult = ChannelInstallationResult.Installed,
    ) = FakeInstallationChannel(
        metadata = InstallationChannelMetadata(
            id = id,
            displayName = id,
            requiresUserInteraction = requiresUser,
            priority = priority,
        ),
        result = result,
    )

    private class FakeInstallationChannel(
        override val metadata: InstallationChannelMetadata,
        private val result: ChannelInstallationResult,
    ) : InstallationChannel {
        var attempts = 0

        override suspend fun install(request: InstallationRequest): ChannelInstallationResult {
            attempts += 1
            return result
        }
    }
}
