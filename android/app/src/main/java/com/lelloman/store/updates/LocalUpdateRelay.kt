package com.lelloman.store.updates

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.lelloman.paravoidandroid.updates.ipc.IUpdateTriggerCallbackV1
import com.lelloman.paravoidandroid.updates.ipc.IUpdateTriggerV1
import com.lelloman.paravoidandroid.updates.ipc.UpdateTriggerProtocol
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.logger.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** A distributor hint never transfers install authority or app credentials. */
@Singleton
class LocalUpdateRelay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appsRepository: AppsRepository,
    private val logger: Logger,
) {
    private val mutex = Mutex()
    private val requested = AtomicLong()
    private var completed = 0L

    suspend fun notifyInstalledApps() {
        val ticket = requested.incrementAndGet()
        mutex.withLock {
            if (completed >= ticket) return
            val generation = requested.get()
            try {
                relayPass()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.w(TAG, "Cannot discover local update services", error)
            }
            completed = generation
            // Requests arriving during this pass own its follow-up. This caller
            // can return to APK checking even under a continuous event stream.
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun relayPass() = coroutineScope {
        val knownPackages = appsRepository.watchApps().first().map { it.packageName }.toSet()
        val services = context.packageManager.queryIntentServices(Intent(UpdateTriggerProtocol.ACTION), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.exported && it.enabled && it.applicationInfo.enabled && it.packageName in knownPackages }
            .map { ComponentName(it.packageName, it.name) }.distinct()
        val slots = Semaphore(4)
        services.map { component -> async {
            slots.withPermit {
                try {
                    logger.audit("ipc.update_hint_started", mapOf("package" to component.packageName))
                    val result = notify(component)
                    logger.audit("ipc.update_hint_result", mapOf("package" to component.packageName, "result" to result))
                    logger.i(TAG, "${component.packageName}: hint result=$result")
                } catch (timeout: TimeoutCancellationException) {
                    logger.audit("ipc.update_hint_failed", mapOf("package" to component.packageName, "error_type" to "Timeout"))
                    logger.i(TAG, "${component.packageName}: hint timeout")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.audit("ipc.update_hint_failed", mapOf("package" to component.packageName, "error_type" to error.javaClass.simpleName))
                    logger.w(TAG, "${component.packageName}: hint unavailable", error)
                }
            }
        } }.awaitAll()
        Unit
    }

    private suspend fun notify(component: ComponentName): Int = withContext(Dispatchers.Main.immediate) {
        var connection: ServiceConnection? = null
        var bound = false
        try {
            withTimeout(5_000) {
                suspendCancellableCoroutine { continuation ->
                    val replied = AtomicBoolean()
                    fun finish(result: Int) {
                        if (replied.compareAndSet(false, true) && continuation.isActive) continuation.resume(result)
                    }
                    val service = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            try {
                                IUpdateTriggerV1.Stub.asInterface(binder).notifyUpdatesChanged(
                                    object : IUpdateTriggerCallbackV1.Stub() {
                                        override fun onResult(result: Int) = finish(result)
                                    })
                            } catch (error: Exception) { finish(UpdateTriggerProtocol.UNAVAILABLE) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) = finish(UpdateTriggerProtocol.UNAVAILABLE)
                        override fun onBindingDied(name: ComponentName) = finish(UpdateTriggerProtocol.UNAVAILABLE)
                        override fun onNullBinding(name: ComponentName) = finish(UpdateTriggerProtocol.UNAVAILABLE)
                    }
                    connection = service
                    bound = context.bindService(Intent(UpdateTriggerProtocol.ACTION).setComponent(component),
                        service, Context.BIND_AUTO_CREATE)
                    if (!bound) finish(UpdateTriggerProtocol.UNAVAILABLE)
                }
            }
        } finally {
            if (bound) connection?.let { context.unbindService(it) }
        }
    }

    private companion object { const val TAG = "LocalUpdateRelay" }
}
