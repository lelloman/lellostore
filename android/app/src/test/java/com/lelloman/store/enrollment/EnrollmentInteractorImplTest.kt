package com.lelloman.store.enrollment

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.enrollment.DeviceEnrollmentApi
import com.lelloman.store.domain.enrollment.EnrollmentRequest
import com.lelloman.store.domain.enrollment.EnrollmentStartResult
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.localdata.auth.DeviceToken
import com.lelloman.store.localdata.auth.DeviceTokenAuthStore
import com.lelloman.store.ui.screen.enrollment.EnrollmentCompletion
import com.lelloman.store.ui.screen.settings.UpdateCheckIntervalOption
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EnrollmentInteractorImplTest {

    private val api = mockk<DeviceEnrollmentApi>()
    private val deviceToken = mockk<DeviceTokenAuthStore>(relaxed = true)
    private val setup = mockk<EnrolledDeviceSetup> {
        coEvery { apply() } returns EnrolledDeviceSetup.Applied(UpdateCheckInterval.Hours24)
    }
    private val config = mockk<ConfigStore> { every { serverUrl } returns MutableStateFlow("https://store.example.com") }

    private fun interactor() = EnrollmentInteractorImpl(
        RuntimeEnvironment.getApplication(), config, api, deviceToken, setup, mockk(relaxed = true),
    )

    private fun credential(server: String = "https://store.example.com") =
        """{"version":1,"server_url":"$server","name":"Mi Box","token":"$SECRET"}"""

    @Test
    fun `start describes this device`() = runTest {
        val request = slot<EnrollmentRequest>()
        coEvery { api.start(any(), capture(request)) } returns EnrollmentStartResult.RateLimited

        interactor().start("https://store.example.com", "Mi Box")

        assertThat(request.captured.name).isEqualTo("Mi Box")
        assertThat(request.captured.androidSdk).isEqualTo(34)
        assertThat(request.captured.clientVersion).isNotEmpty()
    }

    @Test
    fun `completion stores the credential, signs in and turns updates on`() = runTest {
        val completion = interactor().complete("https://store.example.com", credential())

        coVerify { deviceToken.provision(DeviceToken("Mi Box", "https://store.example.com", SECRET)) }
        coVerify { setup.apply() }
        assertThat(completion).isEqualTo(EnrollmentCompletion("Mi Box", true, UpdateCheckIntervalOption.Hours24, true))
    }

    @Test
    fun `the token is bound to the store the device enrolled with`() = runTest {
        interactor().complete("https://store.example.com", credential(server = "https://internal.example.com"))

        coVerify { deviceToken.provision(DeviceToken("Mi Box", "https://store.example.com", SECRET)) }
    }

    @Test
    fun `an unusable credential is refused and nothing is turned on`() = runTest {
        val result = runCatching { interactor().complete("https://store.example.com", """{"version":1}""") }

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { deviceToken.provision(any()) }
        coVerify(exactly = 0) { setup.apply() }
    }

    private companion object {
        val SECRET = "lellostore_dev_" + "c".repeat(64)
    }
}
