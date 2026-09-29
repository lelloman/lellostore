package com.lelloman.store.updates

import android.content.*
import android.content.pm.*
import com.lelloman.paravoidandroid.updates.ipc.*
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.google.common.truth.Truth.assertThat

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [30], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LocalUpdateRelayTest {
    @Test fun `isolates timeout and rejection and unbinds every target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val names = listOf("com.one", "com.two", "com.timeout")
            val pm = mockk<PackageManager>()
            every { pm.queryIntentServices(any<Intent>(), any<Int>()) } returns
                (names + "com.unknown").map { name -> ResolveInfo().apply {
                    serviceInfo = ServiceInfo().apply {
                        packageName = name; this.name = "fixture.Trigger"
                        exported = true; enabled = true
                        applicationInfo = ApplicationInfo().apply { enabled = true }
                    }
                } }
            val context = mockk<Context>()
            every { context.packageManager } returns pm
            val bound = mutableListOf<String>()
            every { context.bindService(any<Intent>(), any(), any<Int>()) } answers {
                val component = firstArg<Intent>().component!!
                bound += component.packageName
                if (component.packageName != "com.timeout") {
                    secondArg<ServiceConnection>().onServiceConnected(component, object : IUpdateTriggerV1.Stub() {
                        override fun notifyUpdatesChanged(callback: IUpdateTriggerCallbackV1) {
                            callback.onResult(if (component.packageName == "com.one") UpdateTriggerProtocol.QUEUED else UpdateTriggerProtocol.REJECTED)
                        }
                    })
                }
                true
            }
            every { context.unbindService(any()) } just Runs
            val apps = mockk<AppsRepository>()
            every { apps.watchApps() } returns flowOf(names.map { name ->
                App(name, name, null, "", AppVersion(1, "1", 0, null, 30, Instant.fromEpochMilliseconds(0)))
            })
            LocalUpdateRelay(context, apps, mockk(relaxed = true)).notifyInstalledApps()
            assertThat(bound).containsExactlyElementsIn(names)
            verify(exactly = 3) { context.unbindService(any()) }
            assertThat(testScheduler.currentTime).isEqualTo(5_000)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `concurrent hints coalesce without holding an earlier poll open`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val context = mockk<Context>()
            val pm = mockk<PackageManager>()
            every { context.packageManager } returns pm
            every { pm.queryIntentServices(any<Intent>(), any<Int>()) } returns listOf(ResolveInfo().apply {
                serviceInfo = ServiceInfo().apply {
                    packageName = "com.one"; name = "fixture.Trigger"; exported = true; enabled = true
                    applicationInfo = ApplicationInfo().apply { enabled = true }
                }
            })
            val callbacks = mutableListOf<IUpdateTriggerCallbackV1>()
            every { context.bindService(any<Intent>(), any(), any<Int>()) } answers {
                secondArg<ServiceConnection>().onServiceConnected(firstArg<Intent>().component!!, object : IUpdateTriggerV1.Stub() {
                    override fun notifyUpdatesChanged(callback: IUpdateTriggerCallbackV1) { callbacks += callback }
                })
                true
            }
            every { context.unbindService(any()) } just Runs
            val apps = mockk<AppsRepository>()
            every { apps.watchApps() } returns flowOf(listOf(App("com.one", "One", null, "",
                AppVersion(1, "1", 0, null, 30, Instant.fromEpochMilliseconds(0)))))
            val relay = LocalUpdateRelay(context, apps, mockk(relaxed = true))
            val first = async { relay.notifyInstalledApps() }
            runCurrent()
            val second = async { relay.notifyInstalledApps() }
            val third = async { relay.notifyInstalledApps() }
            runCurrent()
            callbacks.removeAt(0).onResult(UpdateTriggerProtocol.QUEUED)
            runCurrent()
            assertThat(first.isCompleted).isTrue()
            assertThat(second.isCompleted).isFalse()
            callbacks.removeAt(0).onResult(UpdateTriggerProtocol.COALESCED)
            awaitAll(first, second, third)
            verify(exactly = 2) { context.bindService(any<Intent>(), any(), any<Int>()) }
            verify(exactly = 2) { context.unbindService(any()) }
        } finally { Dispatchers.resetMain() }
    }

}
