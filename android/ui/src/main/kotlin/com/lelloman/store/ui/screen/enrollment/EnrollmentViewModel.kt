package com.lelloman.store.ui.screen.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lelloman.store.domain.enrollment.EnrollmentPollResult
import com.lelloman.store.domain.enrollment.EnrollmentStartResult
import com.lelloman.store.domain.enrollment.StartedEnrollment
import com.lelloman.store.ui.screen.credential.IssueCredentialViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject

/**
 * Device-code enrollment of this device (docs/DEVICE_ENROLLMENT.md): get a user code, show it, and
 * poll until an administrator approves or denies it.
 *
 * Polling runs only while the screen is visible ([onVisibilityChanged]); the code and its expiry
 * survive a pause. It follows the server's `interval`, adds 5 s on `slow_down`, backs off on
 * network errors, and gets a new code by itself when one expires, up to [MAX_EXPIRED_CODES].
 */
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel() {

    private val mutableState = MutableStateFlow<EnrollmentScreenState>(
        EnrollmentScreenState.Naming(interactor.defaultDeviceName().take(IssueCredentialViewModel.MAX_SUBJECT_NAME_LENGTH)),
    )
    val state: StateFlow<EnrollmentScreenState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<EnrollmentScreenEvent>()
    val events: SharedFlow<EnrollmentScreenEvent> = mutableEvents.asSharedFlow()

    private var visible = false
    private var job: Job? = null

    // The running enrollment; kept while the screen is hidden.
    private var serverUrl: String? = null
    private var active: ActiveCode? = null
    private var expiredCodes = 0

    private class ActiveCode(
        val enrollment: StartedEnrollment,
        val expiresAtMillis: Long,
        var intervalMillis: Long,
        var lastPollMillis: Long,
    )

    fun onNameChanged(name: String) {
        val current = mutableState.value as? EnrollmentScreenState.Naming ?: return
        mutableState.value = current.copy(name = name, invalidName = false)
    }

    /** Starts the enrollment with the chosen name. */
    fun onStart() {
        val current = mutableState.value as? EnrollmentScreenState.Naming ?: return
        val name = current.name.trim()
        if (!IssueCredentialViewModel.isValidSubjectName(name)) {
            mutableState.value = current.copy(invalidName = true)
            return
        }
        begin(name)
    }

    /** After a stop: start again with a fresh code and expiry count. */
    fun onRetry() {
        val current = mutableState.value as? EnrollmentScreenState.Stopped ?: return
        begin(current.name)
    }

    /** Back to choosing the name; the running enrollment is abandoned (it expires on the server). */
    fun onChangeName() {
        val name = when (val current = mutableState.value) {
            is EnrollmentScreenState.Waiting -> current.name
            is EnrollmentScreenState.Stopped -> current.name
            else -> return
        }
        stopPolling()
        active = null
        mutableState.value = EnrollmentScreenState.Naming(name)
    }

    fun onContinue() {
        if (mutableState.value !is EnrollmentScreenState.Completed) return
        viewModelScope.launch { mutableEvents.emit(EnrollmentScreenEvent.NavigateToMain) }
    }

    /** Polling runs only while the screen is visible (started); it resumes where it was. */
    fun onVisibilityChanged(visible: Boolean) {
        this.visible = visible
        if (visible) resumePolling() else stopPolling()
    }

    private fun begin(name: String) {
        stopPolling()
        val server = interactor.serverUrl()
        serverUrl = server
        active = null
        expiredCodes = 0
        mutableState.value = EnrollmentScreenState.Waiting(name = name, storeHost = hostOf(server))
        resumePolling()
    }

    private fun stopPolling() {
        job?.cancel()
        job = null
    }

    private fun resumePolling() {
        val waiting = mutableState.value as? EnrollmentScreenState.Waiting ?: return
        if (!visible || job?.isActive == true) return
        job = viewModelScope.launch { run(waiting.name) }
    }

    private suspend fun run(name: String) {
        val server = serverUrl ?: return
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val code = active?.takeIf { now() < it.expiresAtMillis }
            if (code == null) {
                if (active != null) {
                    // Expired locally before the server said so.
                    if (!onCodeExpired(name)) return
                    continue
                }
                when (val started = attempt { interactor.start(server, name) }) {
                    null -> {
                        showOffline(name)
                        delay(backoff(++failures))
                    }
                    is EnrollmentStartResult.Started -> {
                        failures = 0
                        val enrollment = started.enrollment
                        val startedAt = now()
                        active = ActiveCode(
                            enrollment = enrollment,
                            expiresAtMillis = startedAt + enrollment.expiresInSeconds.coerceAtLeast(1) * 1000,
                            intervalMillis = enrollment.intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS) * 1000,
                            lastPollMillis = startedAt,
                        )
                        showCode(name, offline = false)
                    }
                    EnrollmentStartResult.Blocked -> return stop(name, StopReason.BLOCKED)
                    EnrollmentStartResult.RateLimited -> return stop(name, StopReason.RATE_LIMITED)
                    is EnrollmentStartResult.Refused -> return stop(name, StopReason.REFUSED)
                    EnrollmentStartResult.Unavailable -> return stop(name, StopReason.UNAVAILABLE)
                }
                continue
            }

            // Wait for the interval (or the backoff) since the last poll, counting down meanwhile.
            val wait = if (failures > 0) backoff(failures) else code.intervalMillis
            waitUntil(name, minOf(code.lastPollMillis + wait, code.expiresAtMillis))
            if (now() >= code.expiresAtMillis) continue
            code.lastPollMillis = now()
            when (val result = attempt { interactor.poll(server, code.enrollment.deviceCode) }) {
                null -> {
                    failures++
                    showCode(name, offline = true)
                }
                EnrollmentPollResult.Pending -> {
                    failures = 0
                    showCode(name, offline = false)
                }
                EnrollmentPollResult.SlowDown -> {
                    failures = 0
                    code.intervalMillis = (code.intervalMillis + SLOW_DOWN_MILLIS).coerceAtMost(MAX_INTERVAL_SECONDS * 1000)
                }
                EnrollmentPollResult.Expired -> if (!onCodeExpired(name)) return
                EnrollmentPollResult.Denied -> return stop(name, StopReason.DENIED)
                EnrollmentPollResult.InvalidGrant -> return stop(name, StopReason.REFUSED)
                is EnrollmentPollResult.Issued -> return complete(name, server, result.credential)
            }
        }
    }

    /** Counts an expired code; returns false once [MAX_EXPIRED_CODES] have expired (and stops). */
    private fun onCodeExpired(name: String): Boolean {
        active = null
        expiredCodes++
        if (expiredCodes >= MAX_EXPIRED_CODES) {
            stop(name, StopReason.EXPIRED)
            return false
        }
        mutableState.value = waitingState(name).copy(code = null, remainingSeconds = 0, offline = false)
        return true
    }

    private suspend fun waitUntil(name: String, targetMillis: Long) {
        while (true) {
            val remaining = targetMillis - now()
            if (remaining <= 0) return
            showCode(name, offline = (mutableState.value as? EnrollmentScreenState.Waiting)?.offline == true)
            delay(minOf(remaining, TICK_MILLIS))
        }
    }

    private suspend fun complete(name: String, server: String, credential: String) {
        active = null
        mutableState.value = EnrollmentScreenState.Completing
        // The credential is delivered only once: hiding the screen must not lose it.
        val completion = withContext(NonCancellable) {
            try {
                interactor.complete(server, credential)
            } catch (_: Exception) {
                null
            }
        }
        mutableState.value = completion?.let(EnrollmentScreenState::Completed)
            ?: EnrollmentScreenState.Stopped(name, StopReason.NOT_STORED)
    }

    private fun stop(name: String, reason: StopReason) {
        active = null
        mutableState.value = EnrollmentScreenState.Stopped(name, reason)
    }

    private fun showOffline(name: String) {
        mutableState.value = waitingState(name).copy(offline = true)
    }

    private fun showCode(name: String, offline: Boolean) {
        val code = active ?: return
        mutableState.value = waitingState(name).copy(
            code = EnrollmentCode(
                userCode = code.enrollment.userCode,
                verificationUri = code.enrollment.verificationUri,
                verificationUriComplete = code.enrollment.verificationUriComplete,
            ),
            remainingSeconds = ((code.expiresAtMillis - now()).coerceAtLeast(0) + 999) / 1000,
            offline = offline,
        )
    }

    private fun waitingState(name: String): EnrollmentScreenState.Waiting {
        val server = serverUrl.orEmpty()
        return (mutableState.value as? EnrollmentScreenState.Waiting)
            ?.copy(expiredCodes = expiredCodes)
            ?: EnrollmentScreenState.Waiting(name = name, storeHost = hostOf(server), expiredCodes = expiredCodes)
    }

    /** Null when the request failed in transport (or with an unexpected answer). */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun now(): Long = interactor.elapsedRealtimeMillis()

    private fun hostOf(serverUrl: String): String = runCatching { URI(serverUrl).host }.getOrNull() ?: serverUrl

    interface Interactor {
        /** The selected store, which the enrollment is bound to. */
        fun serverUrl(): String

        /** The device name from the system settings, or its model. */
        fun defaultDeviceName(): String

        /** Starts an enrollment named [name], with this device's model, Android and app versions. */
        suspend fun start(serverUrl: String, name: String): EnrollmentStartResult
        suspend fun poll(serverUrl: String, deviceCode: String): EnrollmentPollResult

        /**
         * Stores the approved [credential] for [serverUrl] and signs in with it, then turns on
         * automatic updates and the update connection. Throws when the credential is unusable.
         */
        suspend fun complete(serverUrl: String, credential: String): EnrollmentCompletion

        /** A monotonic clock (SystemClock.elapsedRealtime). */
        fun elapsedRealtimeMillis(): Long
    }

    internal companion object {
        const val MAX_EXPIRED_CODES = 3
        const val SLOW_DOWN_MILLIS = 5_000L
        const val MIN_INTERVAL_SECONDS = 1L
        const val MAX_INTERVAL_SECONDS = 60L
        const val TICK_MILLIS = 1_000L
        private val BACKOFF_MILLIS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000)

        fun backoff(failures: Int): Long = BACKOFF_MILLIS[(failures - 1).coerceIn(0, BACKOFF_MILLIS.lastIndex)]
    }
}
