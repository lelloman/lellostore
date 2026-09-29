package com.lelloman.store.e2e

import androidx.test.platform.app.InstrumentationRegistry
import com.lelloman.store.domain.apps.*
import com.lelloman.store.domain.model.*
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.logger.Logger
import com.lelloman.store.recovery.SelfUpdateGate
import com.lelloman.store.updates.*
import com.lelloman.store.worker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Instant
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Explicitly opted-in fixture test; never targets a user's installed applications. */
class ParavoidTriggerDeviceTest {
    @Test fun forwardThroughProductionRelay() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("localTriggers") == "true")
        val packages = requireNotNull(args.getString("triggerPackages")).split(',')
        require(packages.all { it.startsWith("com.lelloman.paravoidfixture.trigger.") })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val results = CopyOnWriteArrayList<String>()
        val logger = object : Logger {
            override fun d(tag: String, message: String) {}
            override fun i(tag: String, message: String) { results += message }
            override fun w(tag: String, message: String, throwable: Throwable?) { results += message }
            override fun e(tag: String, message: String, throwable: Throwable?) { results += message }
        }
        val apps = object : AppsRepository {
            override fun watchApps() = flowOf(packages.map { App(it,it,null,"",AppVersion(1,"1",0,null,30,Instant.fromEpochMilliseconds(0))) })
            override fun watchApp(packageName: String): Flow<AppDetail?> = flowOf(null)
            override suspend fun refreshApps() = Result.success(Unit)
            override suspend fun refreshApp(packageName: String): Result<AppDetail> = error("No APK updates in this test")
        }
        val relay = LocalUpdateRelay(context,apps,logger)
        val prefs = java.lang.reflect.Proxy.newProxyInstance(UserPreferencesStore::class.java.classLoader,
            arrayOf(UserPreferencesStore::class.java)) { _, _, _ -> error("APK preferences must not gate VPK hints") } as UserPreferencesStore
        val mode = args.getString("triggerMode") ?: "poll"
        if (mode == "poll") {
            val installed = object : InstalledAppsRepository {
                override fun watchInstalledApps() = flowOf(emptyList<InstalledApp>())
                override suspend fun refreshInstalledApps() {}
                override suspend fun refreshInstalledApp(packageName: String) {}
                override fun isInstalled(packageName: String) = flowOf(true)
                override fun getInstalledVersion(packageName: String): Flow<InstalledApp?> = flowOf(null)
            }
            assertTrue(UpdateCheckerImpl(apps,installed,prefs,SelfUpdateGate(context),relay).checkForUpdates().getOrThrow().isEmpty())
        } else {
            require(mode == "event")
            // Exercise the production WebSocket listener and relay independently of APK checking.
            runCatching { androidx.work.WorkManager.initialize(context,androidx.work.Configuration.Builder().build()) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val initializer = WorkManagerInitializer(context,prefs,scope)
            val server = MockWebServer()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("{\"type\":\"catalog_changed\"}")
                }
            }))
            server.start()
            val connection = ForegroundCatalogEventConnection(OkHttpClient(),initializer,relay,logger,scope)
            try {
                connection.start(server.url("/").toString())
                withTimeout(20_000) { while(!packages.all { pkg -> results.any { it.startsWith("$pkg: hint result=") } }) delay(100) }
            } finally { connection.stop(); scope.cancel(); server.shutdown() }
        }
        for (pkg in packages) assertTrue("Missing acknowledgement: $results", results.any { it.startsWith("$pkg: hint result=") })
        android.util.Log.i("ParavoidTriggerFixture",results.joinToString(";"))
        Unit
    }
}
