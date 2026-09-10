package com.lelloman.store.worker

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.random.Random

@Singleton
class ForegroundCatalogEventConnection @Inject constructor(
    okHttpClient: OkHttpClient,
    private val workManagerInitializer: WorkManagerInitializer,
    private val logger: Logger,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val client = okHttpClient.newBuilder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()
    private var connectionJob: Job? = null
    private var activeUrl: String? = null
    private val catalogChanges = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in catalogChanges) {
                delay(EVENT_COALESCE_MILLIS)
                if (isStarted()) workManagerInitializer.enqueueImmediateUpdateCheck()
            }
        }
    }

    @Synchronized
    fun start(serverUrl: String) {
        val eventsUrl = catalogEventsUrl(serverUrl) ?: return
        if (activeUrl == eventsUrl && connectionJob?.isActive == true) return
        stop()
        activeUrl = eventsUrl
        connectionJob = scope.launch {
            var retryIndex = 0
            while (true) {
                val connectedFor = connectOnce(eventsUrl)
                retryIndex = if (connectedFor >= STABLE_CONNECTION_MILLIS) 0 else retryIndex + 1
                val baseDelay = RETRY_DELAYS_MILLIS[(retryIndex - 1).coerceIn(0, RETRY_DELAYS_MILLIS.lastIndex)]
                val jitter = Random.nextLong(-baseDelay / 5, baseDelay / 5 + 1)
                delay(baseDelay + jitter)
            }
        }
    }

    @Synchronized
    fun stop() {
        activeUrl = null
        connectionJob?.cancel()
        connectionJob = null
    }

    @Synchronized
    private fun isStarted(): Boolean = activeUrl != null

    private suspend fun connectOnce(url: String): Long = suspendCancellableCoroutine { continuation ->
        val connectedAt = SystemClock.elapsedRealtime()
        var openedAt: Long? = null
        var completed = false
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedAt = SystemClock.elapsedRealtime()
                logger.i(TAG, "Connected to catalog event stream")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCatalogChangedEvent(text)) {
                    catalogChanges.trySend(Unit)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish()
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                logger.w(TAG, "Catalog event stream disconnected: ${error.message}", error)
                finish()
            }

            private fun finish() {
                if (completed || !continuation.isActive) return
                completed = true
                continuation.resume(SystemClock.elapsedRealtime() - (openedAt ?: connectedAt))
            }
        }
        val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        continuation.invokeOnCancellation { socket.cancel() }
    }

    internal companion object {
        private const val TAG = "ForegroundUpdateEvents"
        private const val PING_INTERVAL_SECONDS = 30L
        private const val STABLE_CONNECTION_MILLIS = 30_000L
        private const val EVENT_COALESCE_MILLIS = 500L
        private val RETRY_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

        fun catalogEventsUrl(serverUrl: String): String? {
            val base = serverUrl.trim().trimEnd('/')
            return when {
                base.startsWith("https://", ignoreCase = true) -> "wss://${base.substring(8)}/api/events"
                base.startsWith("http://", ignoreCase = true) -> "ws://${base.substring(7)}/api/events"
                else -> null
            }
        }

        fun isCatalogChangedEvent(payload: String): Boolean =
            Regex("""[\"']type[\"']\s*:\s*[\"']catalog_changed[\"']""").containsMatchIn(payload)
    }
}

@Singleton
class ForegroundUpdateLifecycleObserver @Inject constructor(
    private val authStore: AuthStore,
    private val configStore: ConfigStore,
    private val warmUpdateScheduler: WarmUpdateScheduler,
    private val connection: ForegroundCatalogEventConnection,
    private val workManagerInitializer: WorkManagerInitializer,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    private val foreground = MutableStateFlow(false)

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            combine(foreground, authStore.authState, configStore.serverUrl) { isForeground, auth, url ->
                ConnectionState(isForeground && auth is AuthState.Authenticated, url)
            }
                .distinctUntilChanged()
                .collect { state ->
                    if (state.active) {
                        warmUpdateScheduler.cancel()
                        workManagerInitializer.enqueueImmediateUpdateCheck()
                        connection.start(state.serverUrl)
                    } else {
                        connection.stop()
                    }
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground.value = false
    }

    private data class ConnectionState(val active: Boolean, val serverUrl: String)
}
