package com.lelloman.store.ui.screen.enrollment

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.enrollment.EnrollmentPollResult
import com.lelloman.store.domain.enrollment.EnrollmentStartResult
import com.lelloman.store.domain.enrollment.StartedEnrollment
import com.lelloman.store.ui.screen.settings.UpdateCheckIntervalOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The enrollment polling state machine, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val interactor = FakeEnrollmentInteractor(scheduler)

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.started(name: String = "Living room TV"): EnrollmentViewModel {
        interactor.defaultName = name
        val viewModel = EnrollmentViewModel(interactor)
        viewModel.onVisibilityChanged(true)
        viewModel.onStart()
        runCurrent()
        return viewModel
    }

    private val EnrollmentViewModel.waiting get() = state.value as EnrollmentScreenState.Waiting

    @Test
    fun `starts from the device name, trimmed and limited`() = runTest(scheduler) {
        interactor.defaultName = "x".repeat(80)

        val viewModel = EnrollmentViewModel(interactor)

        assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Naming("x".repeat(64)))
    }

    @Test
    fun `an invalid name is refused before any request`() = runTest(scheduler) {
        val viewModel = EnrollmentViewModel(interactor)
        viewModel.onVisibilityChanged(true)
        viewModel.onNameChanged("  \n ")

        viewModel.onStart()
        runCurrent()

        assertThat((viewModel.state.value as EnrollmentScreenState.Naming).invalidName).isTrue()
        assertThat(interactor.starts).isEmpty()
    }

    @Test
    fun `shows the code, polls at the interval and completes on approval`() = runTest(scheduler) {
        interactor.polls += listOf(EnrollmentPollResult.Pending, EnrollmentPollResult.Issued(CREDENTIAL))
        val viewModel = started("  Mi Box  ")

        assertThat(interactor.starts).containsExactly("https://store.example.com" to "Mi Box")
        val waiting = viewModel.waiting
        assertThat(waiting.code).isEqualTo(
            EnrollmentCode("WDJB-MJHT", "https://store.example.com/admin/device-tokens/approve", "$APPROVE?code=WDJB-MJHT"),
        )
        assertThat(waiting.storeHost).isEqualTo("store.example.com")
        assertThat(waiting.remainingSeconds).isEqualTo(600)

        advanceTimeBy(4_999)
        assertThat(interactor.pollTimes).isEmpty()
        advanceTimeBy(2)
        assertThat(interactor.pollTimes).containsExactly(5_000L)
        assertThat(viewModel.waiting.remainingSeconds).isEqualTo(595)

        advanceTimeBy(5_000)
        runCurrent()
        assertThat(interactor.pollTimes).containsExactly(5_000L, 10_000L).inOrder()
        assertThat(interactor.completed).containsExactly("https://store.example.com" to CREDENTIAL)
        assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Completed(COMPLETION))

        viewModel.events.test {
            viewModel.onContinue()
            runCurrent()
            assertThat(awaitItem()).isEqualTo(EnrollmentScreenEvent.NavigateToMain)
        }
        advanceTimeBy(60_000)
        assertThat(interactor.pollTimes).hasSize(2)
    }

    @Test
    fun `slow_down adds five seconds to the interval`() = runTest(scheduler) {
        interactor.polls += listOf(EnrollmentPollResult.SlowDown, EnrollmentPollResult.SlowDown, EnrollmentPollResult.Pending)
        started()

        advanceTimeBy(35_001)

        // 5 s, then 10 s later, then 15 s later.
        assertThat(interactor.pollTimes).containsExactly(5_000L, 15_000L, 30_000L).inOrder()
    }

    @Test
    fun `an expired code is replaced by a new one, three times at most`() = runTest(scheduler) {
        interactor.polls += listOf(EnrollmentPollResult.Expired)
        val viewModel = started()

        advanceTimeBy(5_001)
        assertThat(interactor.starts).hasSize(2)
        assertThat(viewModel.waiting.expiredCodes).isEqualTo(1)
        assertThat(viewModel.waiting.code).isNotNull()

        interactor.polls += listOf(EnrollmentPollResult.Expired)
        advanceTimeBy(5_000)
        assertThat(interactor.starts).hasSize(3)
        assertThat(viewModel.waiting.expiredCodes).isEqualTo(2)

        interactor.polls += listOf(EnrollmentPollResult.Expired)
        advanceTimeBy(5_000)
        assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Stopped("Living room TV", StopReason.EXPIRED))
        assertThat(interactor.starts).hasSize(3)

        advanceTimeBy(60_000)
        assertThat(interactor.pollTimes).hasSize(3)

        viewModel.onRetry()
        runCurrent()
        assertThat(interactor.starts).hasSize(4)
        assertThat(viewModel.waiting.expiredCodes).isEqualTo(0)
    }

    @Test
    fun `a code that runs out locally counts as expired`() = runTest(scheduler) {
        interactor.expiresIn = 12
        val viewModel = started()

        advanceTimeBy(12_001)

        assertThat(interactor.pollTimes).containsExactly(5_000L, 10_000L).inOrder()
        assertThat(interactor.starts).hasSize(2)
        assertThat(viewModel.waiting.expiredCodes).isEqualTo(1)
    }

    @Test
    fun `a denial stops with a clear reason`() = runTest(scheduler) {
        interactor.polls += listOf(EnrollmentPollResult.Denied)
        val viewModel = started()

        advanceTimeBy(5_001)

        assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Stopped("Living room TV", StopReason.DENIED))
        advanceTimeBy(60_000)
        assertThat(interactor.pollTimes).hasSize(1)
    }

    @Test
    fun `an unknown or used code stops`() = runTest(scheduler) {
        interactor.polls += listOf(EnrollmentPollResult.InvalidGrant)
        val viewModel = started()

        advanceTimeBy(5_001)

        assertThat((viewModel.state.value as EnrollmentScreenState.Stopped).reason).isEqualTo(StopReason.REFUSED)
    }

    @Test
    fun `start refusals stop with their reason`() = runTest(scheduler) {
        val answers = mapOf(
            EnrollmentStartResult.Blocked to StopReason.BLOCKED,
            EnrollmentStartResult.RateLimited to StopReason.RATE_LIMITED,
            EnrollmentStartResult.Refused("invalid_request") to StopReason.REFUSED,
            EnrollmentStartResult.Unavailable to StopReason.UNAVAILABLE,
        )
        for ((answer, reason) in answers) {
            interactor.startAnswers += answer
            val viewModel = started()
            assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Stopped("Living room TV", reason))
            viewModel.onVisibilityChanged(false)
        }
    }

    @Test
    fun `network errors are retried with backoff and shown`() = runTest(scheduler) {
        interactor.startFailures = 1
        val viewModel = started()

        assertThat(viewModel.waiting.offline).isTrue()
        assertThat(viewModel.waiting.code).isNull()
        advanceTimeBy(2_001)
        assertThat(viewModel.waiting.code).isNotNull()
        assertThat(viewModel.waiting.offline).isFalse()
        val startedAt = 2_000L

        interactor.pollFailures = 2
        advanceTimeBy(5_000)
        assertThat(interactor.pollTimes).containsExactly(startedAt + 5_000)
        assertThat(viewModel.waiting.offline).isTrue()
        // Backoff: 2 s, then 5 s, then back to the interval once a poll succeeds.
        advanceTimeBy(2_000)
        advanceTimeBy(5_000)
        assertThat(interactor.pollTimes).containsExactly(startedAt + 5_000, startedAt + 7_000, startedAt + 12_000).inOrder()
        assertThat(viewModel.waiting.offline).isFalse()
        advanceTimeBy(5_000)
        assertThat(interactor.pollTimes.last()).isEqualTo(startedAt + 17_000)
    }

    @Test
    fun `polling pauses while the screen is hidden and resumes with the same code`() = runTest(scheduler) {
        val viewModel = started()
        advanceTimeBy(5_001)
        assertThat(interactor.pollTimes).hasSize(1)

        viewModel.onVisibilityChanged(false)
        advanceTimeBy(60_000)
        assertThat(interactor.pollTimes).hasSize(1)

        viewModel.onVisibilityChanged(true)
        runCurrent()
        // The interval has long passed: poll at once, with the code it already had.
        assertThat(interactor.pollTimes.last()).isEqualTo(65_001L)
        assertThat(interactor.starts).hasSize(1)
        assertThat(viewModel.waiting.remainingSeconds).isEqualTo(535)
    }

    @Test
    fun `a code that expired while hidden is replaced on return`() = runTest(scheduler) {
        val viewModel = started()
        viewModel.onVisibilityChanged(false)
        advanceTimeBy(700_000)

        viewModel.onVisibilityChanged(true)
        runCurrent()

        assertThat(interactor.starts).hasSize(2)
        assertThat(viewModel.waiting.expiredCodes).isEqualTo(1)
    }

    @Test
    fun `a credential that cannot be stored stops`() = runTest(scheduler) {
        interactor.polls += listOf(EnrollmentPollResult.Issued(CREDENTIAL))
        interactor.completeError = IllegalStateException("Keystore unavailable")
        val viewModel = started()

        advanceTimeBy(5_001)

        assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Stopped("Living room TV", StopReason.NOT_STORED))
    }

    @Test
    fun `hiding the screen does not lose an approved credential`() = runTest(scheduler) {
        interactor.polls += listOf(EnrollmentPollResult.Issued(CREDENTIAL))
        val gate = CompletableDeferred<Unit>()
        interactor.completeGate = gate
        val viewModel = started()
        advanceTimeBy(5_001)
        assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Completing)

        viewModel.onVisibilityChanged(false)
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Completed(COMPLETION))
    }

    @Test
    fun `changing the name abandons the code`() = runTest(scheduler) {
        val viewModel = started()

        viewModel.onChangeName()
        advanceTimeBy(60_000)

        assertThat(viewModel.state.value).isEqualTo(EnrollmentScreenState.Naming("Living room TV"))
        assertThat(interactor.pollTimes).isEmpty()
    }

    private class FakeEnrollmentInteractor(private val scheduler: TestCoroutineScheduler) : EnrollmentViewModel.Interactor {
        var defaultName = "Living room TV"
        var expiresIn = 600L
        val startAnswers = ArrayDeque<EnrollmentStartResult>()
        val polls = ArrayDeque<EnrollmentPollResult>()
        var startFailures = 0
        var pollFailures = 0
        var completeError: Exception? = null
        var completeGate: CompletableDeferred<Unit>? = null
        val starts = mutableListOf<Pair<String, String>>()
        val pollTimes = mutableListOf<Long>()
        val completed = mutableListOf<Pair<String, String>>()

        override fun serverUrl() = "https://store.example.com"
        override fun defaultDeviceName() = defaultName

        override suspend fun start(serverUrl: String, name: String): EnrollmentStartResult {
            starts += serverUrl to name
            if (startFailures > 0) {
                startFailures--
                throw IOException("offline")
            }
            return startAnswers.removeFirstOrNull() ?: EnrollmentStartResult.Started(
                StartedEnrollment(
                    deviceCode = "lellostore_enr_${starts.size}",
                    userCode = "WDJB-MJHT",
                    verificationUri = "https://store.example.com/admin/device-tokens/approve",
                    verificationUriComplete = "$APPROVE?code=WDJB-MJHT",
                    expiresInSeconds = expiresIn,
                    intervalSeconds = 5,
                ),
            )
        }

        override suspend fun poll(serverUrl: String, deviceCode: String): EnrollmentPollResult {
            pollTimes += scheduler.currentTime
            if (pollFailures > 0) {
                pollFailures--
                throw IOException("offline")
            }
            return polls.removeFirstOrNull() ?: EnrollmentPollResult.Pending
        }

        override suspend fun complete(serverUrl: String, credential: String): EnrollmentCompletion {
            completeGate?.await()
            completeError?.let { throw it }
            completed += serverUrl to credential
            return COMPLETION
        }

        override fun elapsedRealtimeMillis(): Long = scheduler.currentTime
    }

    private companion object {
        const val APPROVE = "https://store.example.com/admin/device-tokens/approve"
        const val CREDENTIAL = """{"version":1,"server_url":"https://store.example.com","name":"Mi Box","token":"lellostore_dev_x"}"""
        val COMPLETION = EnrollmentCompletion("Mi Box", true, UpdateCheckIntervalOption.Hours24, true)
    }
}
