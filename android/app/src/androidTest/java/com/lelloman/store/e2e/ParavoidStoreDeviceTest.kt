package com.lelloman.store.e2e

import androidx.test.platform.app.InstrumentationRegistry
import com.lelloman.store.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/** Opt-in arguments are consumed only by the instrumentation test graph. */
internal object StoreDeviceArguments {
    private val arguments get() = InstrumentationRegistry.getArguments()
    val enabled get() = arguments.getString("storeDevice") == "true"
    val serverUrl: String? get() = if (enabled) {
        arguments.getString("storeServer").also {
            require(it == "http://127.0.0.1:18766") { "Only the isolated loopback Store is supported" }
        }
    } else null
    val token: String? get() = if (enabled) requireNotNull(arguments.getString("storeToken")) else null
}

@HiltAndroidTest
class ParavoidStoreDeviceTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    @Test fun installOrRepairThroughStore() {
        assumeTrue(StoreDeviceArguments.enabled)
        hiltRule.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // HiltTestApplication replaces the production Application's lazy initializer.
        androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder()
            .setWorkerFactory(workerFactory).build())
        val packageName = "com.lelloman.paravoidcompat.complete.paravoid"
        fun installedTime(): Long = runCatching {
            context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0)
        val previousInstall = installedTime()
        val finished = java.io.File(context.cacheDir, "store-ui-finished")
        finished.delete()
        // Use the real frame clock: host UIAutomator drives both Store and Android's
        // external installer. Only authentication/configuration come from the test graph.
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(
            android.content.Intent(context, MainActivity::class.java)
        ).use {
            val deadline = android.os.SystemClock.elapsedRealtime() + 150_000
            while (!finished.exists() && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
            }
            check(finished.exists()) { "Store UI automation did not finish" }
            check(installedTime() > previousInstall) { "Package manager did not record installation" }
        }
    }
}
