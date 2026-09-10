package com.lelloman.store.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lelloman.store.di.ApplicationScope
import com.lelloman.store.domain.auth.AuthState
import com.lelloman.store.domain.auth.AuthStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WarmUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManagerInitializer: WorkManagerInitializer,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun start() {
        val now = SystemClock.elapsedRealtime()
        val generation = preferences.getLong(KEY_GENERATION, 0L) + 1L
        preferences.edit {
            putBoolean(KEY_ACTIVE, true)
            putLong(KEY_DEADLINE, now + WARM_PERIOD_MILLIS)
            putInt(KEY_NEXT_DELAY_MINUTES, 1)
            putLong(KEY_GENERATION, generation)
        }
        schedule(now + MINUTE_MILLIS, generation)
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent(preferences.getLong(KEY_GENERATION, 0L)))
        clearActiveState()
    }

    fun onAlarm(generation: Long) {
        if (!preferences.getBoolean(KEY_ACTIVE, false) ||
            generation != preferences.getLong(KEY_GENERATION, -1L)
        ) return

        val now = SystemClock.elapsedRealtime()
        val deadline = preferences.getLong(KEY_DEADLINE, 0L)
        if (now >= deadline) {
            cancel()
            return
        }

        workManagerInitializer.enqueueImmediateUpdateCheck()
        val nextDelay = preferences.getInt(KEY_NEXT_DELAY_MINUTES, 1) + 1
        val nextTrigger = WarmPollingSchedule.nextTrigger(now, deadline, nextDelay)
        if (nextTrigger == null) {
            clearActiveState()
        } else {
            preferences.edit { putInt(KEY_NEXT_DELAY_MINUTES, nextDelay) }
            schedule(nextTrigger, generation)
        }
    }

    private fun schedule(triggerAtMillis: Long, generation: Long) {
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMillis,
            pendingIntent(generation),
        )
    }

    private fun clearActiveState() {
        preferences.edit {
            remove(KEY_ACTIVE)
            remove(KEY_DEADLINE)
            remove(KEY_NEXT_DELAY_MINUTES)
        }
    }

    private fun pendingIntent(generation: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WarmUpdateAlarmReceiver::class.java).putExtra(EXTRA_GENERATION, generation),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val EXTRA_GENERATION = "generation"
        private const val PREFERENCES = "warm-update-polling"
        private const val KEY_ACTIVE = "active"
        private const val KEY_DEADLINE = "deadline"
        private const val KEY_NEXT_DELAY_MINUTES = "next-delay-minutes"
        private const val KEY_GENERATION = "generation"
        private const val REQUEST_CODE = 2001
        internal const val MINUTE_MILLIS = 60_000L
        internal const val WARM_PERIOD_MILLIS = 60 * MINUTE_MILLIS
    }
}

internal object WarmPollingSchedule {
    fun nextTrigger(now: Long, deadline: Long, delayMinutes: Int): Long? =
        (now + delayMinutes * WarmUpdateScheduler.MINUTE_MILLIS).takeIf { it <= deadline }
}

@AndroidEntryPoint
class WarmUpdateAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: WarmUpdateScheduler

    override fun onReceive(context: Context, intent: Intent) {
        scheduler.onAlarm(intent.getLongExtra(WarmUpdateScheduler.EXTRA_GENERATION, -1L))
    }
}

@Singleton
class WarmUpdateLifecycleObserver @Inject constructor(
    private val authStore: AuthStore,
    private val scheduler: WarmUpdateScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            authStore.authState.collectLatest { state ->
                if (state !is AuthState.Authenticated) scheduler.cancel()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        scheduler.cancel()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (authStore.authState.value is AuthState.Authenticated) scheduler.start()
    }
}
