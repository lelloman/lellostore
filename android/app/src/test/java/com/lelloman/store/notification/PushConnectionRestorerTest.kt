package com.lelloman.store.notification

import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.logger.Logger
import com.lelloman.store.worker.UpdateConnectionServiceController
import com.lelloman.store.worker.WorkManagerInitializer
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PushConnectionRestorerTest {
    private val preferences = mockk<UserPreferencesStore> {
        coEvery { readKeepUpdateConnection() } returns true
    }
    private val broker = mockk<NotificationBrokerRuntime> { every { hasBatteryExemption() } returns true }
    private val service = mockk<UpdateConnectionServiceController>(relaxed = true)
    private val work = mockk<WorkManagerInitializer>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)
    private val restorer = PushConnectionRestorer(preferences, broker, service, work, logger)

    @Test fun `replacement starts foreground protection without waiting for authentication`() = runTest {
        restorer.restore("replacement")
        verifyOrder { work.enqueuePushConnectionRecovery(); service.start() }
        coVerify(exactly = 0) { preferences.setKeepUpdateConnection(any()) }
    }

    @Test fun `preference timeout leaves a durable retry`() = runTest {
        coEvery { preferences.readKeepUpdateConnection() } coAnswers { delay(6000); true }
        restorer.restore("replacement")
        verify { work.enqueuePushConnectionRecovery() }
        verify(exactly = 0) { service.start() }
        verify { logger.audit("push.restore_deferred", match { it["reason"] == "preferences_timeout" }) }
    }

    @Test fun `rejected startup keeps opt in and retries`() = runTest {
        every { service.start() } throws IllegalStateException("blocked")
        restorer.restore("replacement")
        verify { work.enqueuePushConnectionRecovery(); logger.audit("push.restore_failed", any()) }
        coVerify(exactly = 0) { preferences.setKeepUpdateConnection(any()) }
    }

    @Test fun `disabled connection is not restarted`() = runTest {
        coEvery { preferences.readKeepUpdateConnection() } returns false
        restorer.restore("boot")
        verify(exactly = 0) { service.start() }
    }

    @Test fun `missing battery exemption does not start service`() = runTest {
        every { broker.hasBatteryExemption() } returns false
        restorer.restore("boot")
        verify(exactly = 0) { service.start() }
        verify { work.enqueuePushConnectionRecovery() }
    }
}
