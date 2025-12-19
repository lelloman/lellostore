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
}
