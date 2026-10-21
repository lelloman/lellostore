package com.lelloman.store.enrollment

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.domain.preferences.UserPreferencesStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EnrolledDeviceSetupTest {

    private val interval = MutableStateFlow(UpdateCheckInterval.Hours12)
    private val preferences = mockk<UserPreferencesStore>(relaxed = true) {
        every { updateCheckInterval } returns interval
    }

    @Test
    fun `turns on automatic updates and the update connection, keeping a chosen interval`() = runTest {
        val applied = EnrolledDeviceSetup(preferences, mockk(relaxed = true)).apply()

        assertThat(applied.updateCheckInterval).isEqualTo(UpdateCheckInterval.Hours12)
        coVerify { preferences.setAutoUpdateDefault(true) }
        coVerify { preferences.setKeepUpdateConnection(true) }
        coVerify(exactly = 0) { preferences.setUpdateCheckInterval(any()) }
    }

    @Test
    fun `manual checks become daily so that updates actually run`() = runTest {
        interval.value = UpdateCheckInterval.Manual

        val applied = EnrolledDeviceSetup(preferences, mockk(relaxed = true)).apply()

        assertThat(applied.updateCheckInterval).isEqualTo(UpdateCheckInterval.Hours24)
        coVerify { preferences.setUpdateCheckInterval(UpdateCheckInterval.Hours24) }
    }
}
