package com.lelloman.store.domain.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserPreferencesTest {

    @Test
    fun `ThemeMode has all expected modes`() {
        val modes = ThemeMode.entries

        assertThat(modes).containsExactly(
            ThemeMode.System,
            ThemeMode.Light,
            ThemeMode.Dark
        )
    }

    @Test
    fun `UpdateCheckInterval has all expected intervals`() {
        val intervals = UpdateCheckInterval.entries

        assertThat(intervals).containsExactly(
            UpdateCheckInterval.Hours6,
            UpdateCheckInterval.Hours12,
            UpdateCheckInterval.Hours24,
            UpdateCheckInterval.Manual
        )
    }

    @Test
    fun `inherited values follow global defaults`() {
        val stable = AppUpdatePolicyResolver.resolve(
            autoUpdateDefault = true,
            releaseChannelDefault = ReleaseChannel.Stable,
            autoUpdateOverride = AutoUpdateOverride.Inherit,
            releaseChannelOverride = ReleaseChannelOverride.Inherit,
            accessLevel = AppAccessLevel.Beta,
            hasBetaRelease = true,
        )
        val beta = AppUpdatePolicyResolver.resolve(
            autoUpdateDefault = false,
            releaseChannelDefault = ReleaseChannel.Beta,
            autoUpdateOverride = AutoUpdateOverride.Inherit,
            releaseChannelOverride = ReleaseChannelOverride.Inherit,
            accessLevel = AppAccessLevel.Beta,
            hasBetaRelease = true,
        )

        assertThat(stable.autoUpdateEnabled).isTrue()
        assertThat(stable.effectiveChannel).isEqualTo(ReleaseChannel.Stable)
        assertThat(beta.autoUpdateEnabled).isFalse()
        assertThat(beta.effectiveChannel).isEqualTo(ReleaseChannel.Beta)
    }

    @Test
    fun `explicit overrides ignore global defaults`() {
        val policy = AppUpdatePolicyResolver.resolve(
            autoUpdateDefault = false,
            releaseChannelDefault = ReleaseChannel.Stable,
            autoUpdateOverride = AutoUpdateOverride.Enabled,
            releaseChannelOverride = ReleaseChannelOverride.Beta,
            accessLevel = AppAccessLevel.Beta,
            hasBetaRelease = true,
        )

        assertThat(policy.autoUpdateEnabled).isTrue()
        assertThat(policy.preferredChannel).isEqualTo(ReleaseChannel.Beta)
        assertThat(policy.effectiveChannel).isEqualTo(ReleaseChannel.Beta)
    }

    @Test
    fun `beta preference falls back without erasing preference`() {
        for ((access, hasBeta) in listOf(
            AppAccessLevel.Stable to true,
            AppAccessLevel.Beta to false,
            null to true,
        )) {
            val policy = AppUpdatePolicyResolver.resolve(
                autoUpdateDefault = true,
                releaseChannelDefault = ReleaseChannel.Beta,
                autoUpdateOverride = AutoUpdateOverride.Inherit,
                releaseChannelOverride = ReleaseChannelOverride.Inherit,
                accessLevel = access,
                hasBetaRelease = hasBeta,
            )

            assertThat(policy.preferredChannel).isEqualTo(ReleaseChannel.Beta)
            assertThat(policy.effectiveChannel).isEqualTo(ReleaseChannel.Stable)
            assertThat(policy.isAuthorized).isEqualTo(access != null)
        }
    }
}
