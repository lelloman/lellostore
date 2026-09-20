package com.lelloman.store.remote

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.remote.RemoteConnectionPhase
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class)
class RemoteDeviceManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `USB failure before service startup never stops an unpromoted service`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val usb = mockk<UsbManager>()
        val device = mockk<UsbDevice>()
        every { context.getSystemService(UsbManager::class.java) } returns usb
        every { context.noBackupFilesDir } returns temporary.newFolder()
        every { context.packageName } returns "com.lelloman.store"
        every { usb.deviceList } returns hashMapOf("receiver" to device)
        every { usb.hasPermission(device) } returns true
        // Disappearing ADB interface fails synchronously on the connection worker.
        every { device.interfaceCount } returns 0
        val manager = RemoteDeviceManager(context, mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true))
        manager.connect("receiver")
        withTimeout(5000) { manager.state.first { it.phase == RemoteConnectionPhase.ERROR } }
        verify(exactly = 1) { context.startForegroundService(any()) }
        verify(exactly = 0) { context.stopService(any()) }
        assertThat(manager.stopServiceIfIdle { error("Stopped before foreground promotion") }).isFalse()
        manager.serviceStartDelivered()
        var stopped = false
        assertThat(manager.stopServiceIfIdle { stopped = true }).isTrue()
        assertThat(stopped).isTrue()
        manager.disconnect()
        verify(exactly = 0) { context.stopService(any()) }
    }
}
