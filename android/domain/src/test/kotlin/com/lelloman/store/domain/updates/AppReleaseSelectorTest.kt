package com.lelloman.store.domain.updates

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.model.AppVersion
import com.lelloman.store.domain.preferences.ReleaseChannel
import kotlinx.datetime.Instant
import org.junit.Test

class AppReleaseSelectorTest {
    @Test
    fun `stable ignores newer beta release`() {
        val selected = AppReleaseSelector.newestUpgrade(
            versions = listOf(version(4, beta = true), version(3), version(2, beta = true)),
            installedVersionCode = 1,
            channel = ReleaseChannel.Stable,
        )

        assertThat(selected?.versionCode).isEqualTo(3)
    }

    @Test
    fun `beta selects newest stable or beta release`() {
        val selected = AppReleaseSelector.newestUpgrade(
            versions = listOf(version(4), version(3, beta = true)),
            installedVersionCode = 2,
            channel = ReleaseChannel.Beta,
        )

        assertThat(selected?.versionCode).isEqualTo(4)
    }

    @Test
    fun `switching to stable never downgrades installed beta`() {
        val selected = AppReleaseSelector.newestUpgrade(
            versions = listOf(version(6, beta = true), version(5)),
            installedVersionCode = 6,
            channel = ReleaseChannel.Stable,
        )

        assertThat(selected).isNull()
    }

    private fun version(code: Int, beta: Boolean = false) = AppVersion(
        versionCode = code,
        versionName = code.toString(),
        size = 1,
        sha256 = "hash",
        minSdk = 21,
        uploadedAt = Instant.parse("2026-01-01T00:00:00Z"),
        isBeta = beta,
    )
}
