package com.lelloman.store.ui.screen.approve

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.AppGroupSummary
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.domain.enrollment.ApprovedEnrollment
import com.lelloman.store.domain.enrollment.EnrollmentApproval
import com.lelloman.store.domain.enrollment.PendingEnrollment
import com.lelloman.store.ui.model.AuthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

// Robolectric for org.json, which scanned payloads are parsed with.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ApproveDeviceViewModelTest {

    private val interactor = FakeApproveInteractor()

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(request: ApproveDeviceRequest = ApproveDeviceRequest()) =
        ApproveDeviceViewModel(interactor).also { it.start(request) }

    private val ApproveDeviceViewModel.review get() = state.value as ApproveDeviceScreenState.Review

    private fun external(code: String? = "WDJB-MJHT", name: String? = null) =
        ApproveDeviceRequest(callerPackage = "com.pezzottv", callerLabel = "PezzotTV", external = true, userCode = code, deviceName = name)

    @Test
    fun `a signed-out store asks for sign-in and reports it`() = runTest {
        interactor.auth.value = AuthState.NotAuthenticated
        val viewModel = viewModel(external())
        advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.NeedsLogin)

        viewModel.onClose()

        assertThat(viewModel.state.value)
            .isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.NOT_LOGGED_IN)))
    }

    @Test
    fun `device tokens and non-administrators cannot approve`() = runTest {
        interactor.auth.value = AuthState.Authenticated("Mi Box", deviceToken = true)
        val device = viewModel()
        advanceUntilIdle()
        assertThat(device.state.value).isEqualTo(ApproveDeviceScreenState.NotAdmin)
        assertThat(interactor.adminChecks).isEqualTo(0)

        interactor.auth.value = AuthState.Authenticated("user@example.com")
        interactor.admin = Result.success(false)
        val user = viewModel(external())
        advanceUntilIdle()
        assertThat(user.state.value).isEqualTo(ApproveDeviceScreenState.NotAdmin)
        user.onClose()
        assertThat(user.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.NOT_AUTHORIZED)))
        assertThat(interactor.lookups).isEmpty()
    }

    @Test
    fun `an external request must be started for result with a valid code`() = runTest {
        val noCaller = viewModel(external().copy(callerPackage = null))
        assertThat(noCaller.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.CALLER_UNKNOWN)))

        val badCode = viewModel(external(code = "../me"))
        assertThat(badCode.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.INVALID_REQUEST)))

        val badName = viewModel(external(name = "Line\nbreak"))
        assertThat(badName.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.INVALID_REQUEST)))
    }

    @Test
    fun `a typed code is looked up and its details shown with groups and tokens`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.EnterCode())

        viewModel.onCodeChanged("wdjb mjht")
        viewModel.onLookup()
        advanceUntilIdle()

        assertThat(interactor.lookups).containsExactly("WDJB-MJHT")
        val review = viewModel.review
        assertThat(review.pending).isEqualTo(PENDING)
        assertThat(review.name).isEqualTo("Mi Box (living room)")
        assertThat(review.mode).isEqualTo(ApprovalMode.NEW_DEVICE)
        assertThat(review.groups).containsExactly(
            GroupOption(1, "all", isAll = true, selected = true),
            GroupOption(3, "family", isAll = false, selected = false),
        ).inOrder()
        // Active tokens first, by name.
        assertThat(review.tokens!!.map { it.id }).containsExactly("t2", "t1", "t3").inOrder()
    }

    @Test
    fun `an invalid typed code is refused locally`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onCodeChanged("ABC")
        viewModel.onLookup()
        advanceUntilIdle()

        assertThat((viewModel.state.value as ApproveDeviceScreenState.EnterCode).error).isEqualTo(CodeError.INVALID)
        assertThat(interactor.lookups).isEmpty()
    }

    @Test
    fun `a scanned approval link is looked up, other stores are refused`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onScanned("https://other.example.com/admin/device-tokens/approve?code=WDJB-MJHT")
        assertThat((viewModel.state.value as ApproveDeviceScreenState.EnterCode).error).isEqualTo(CodeError.OTHER_STORE)

        viewModel.onScanned("https://store.example.com/admin/device-tokens/approve?code=WDJB-MJHT")
        advanceUntilIdle()
        assertThat(interactor.lookups).containsExactly("WDJB-MJHT")
        assertThat(viewModel.state.value).isInstanceOf(ApproveDeviceScreenState.Review::class.java)
    }

    @Test
    fun `lookup failures are explained and reported to an external caller`() = runTest {
        val answers = mapOf(404 to CodeError.NOT_FOUND, 409 to CodeError.NOT_PENDING, 429 to CodeError.TOO_MANY, 403 to CodeError.NOT_AUTHORIZED)
        for ((status, error) in answers) {
            interactor.lookupResult = Result.failure(AdminApiException(status))
            val viewModel = viewModel(external())
            advanceUntilIdle()
            assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.EnterCode("WDJB-MJHT", error = error))
        }
        interactor.lookupResult = Result.failure(IOException("offline"))
        val offline = viewModel(external())
        advanceUntilIdle()
        assertThat((offline.state.value as ApproveDeviceScreenState.EnterCode).error).isEqualTo(CodeError.NETWORK)

        interactor.lookupResult = Result.failure(AdminApiException(404))
        val unknown = viewModel(external())
        advanceUntilIdle()
        unknown.onClose()
        assertThat(unknown.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.UNKNOWN_CODE)))
    }

    @Test
    fun `approving a new device keeps the server's default groups unless changed`() = runTest {
        val viewModel = viewModel(external(name = "Kitchen TV"))
        advanceUntilIdle()
        assertThat(viewModel.review.name).isEqualTo("Kitchen TV")

        viewModel.onApprove()
        advanceUntilIdle()

        assertThat(interactor.approvals).containsExactly("WDJB-MJHT" to EnrollmentApproval.NewDevice("Kitchen TV", null))
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.Done(DoneResult.Approved("Kitchen TV", rekeyed = false)))
        viewModel.onClose()
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Approved("new", rekeyed = false)))
    }

    @Test
    fun `chosen groups are sent explicitly`() = runTest {
        val viewModel = viewModel(external())
        advanceUntilIdle()

        viewModel.onGroupToggled(1)
        viewModel.onGroupToggled(3)
        viewModel.onApprove()
        advanceUntilIdle()

        assertThat(interactor.approvals.single().second).isEqualTo(EnrollmentApproval.NewDevice("Mi Box (living room)", listOf(3L)))
    }

    @Test
    fun `replacing preselects the token with the requested name and rekeys it`() = runTest {
        interactor.approveResult = Result.success(ApprovedEnrollment(TOKENS[0], rekeyed = true))
        val viewModel = viewModel(external())
        advanceUntilIdle()

        viewModel.onModeChanged(ApprovalMode.REPLACE)
        assertThat(viewModel.review.replaceTokenId).isEqualTo("t1")
        assertThat(viewModel.review.name).isEqualTo("Mi Box (living room)")

        viewModel.onApprove()
        advanceUntilIdle()

        // The unchanged name is not sent: the token keeps its own.
        assertThat(interactor.approvals.single().second).isEqualTo(EnrollmentApproval.Replace("t1", null))
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.Done(DoneResult.Approved("Mi Box (living room)", rekeyed = true)))
    }

    @Test
    fun `replacing needs a chosen token and can rename it`() = runTest {
        interactor.pending = PENDING.copy(requestedName = "Unknown box")
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onCodeChanged("WDJB-MJHT")
        viewModel.onLookup()
        advanceUntilIdle()

        viewModel.onModeChanged(ApprovalMode.REPLACE)
        assertThat(viewModel.review.replaceTokenId).isNull()
        assertThat(viewModel.review.canApprove).isFalse()
        viewModel.onApprove()
        advanceUntilIdle()
        assertThat(interactor.approvals).isEmpty()

        viewModel.onReplaceTokenSelected("t2")
        assertThat(viewModel.review.name).isEqualTo("Bedroom TV")
        viewModel.onNameChanged("Bedroom TV 2")
        viewModel.onApprove()
        advanceUntilIdle()
        assertThat(interactor.approvals.single().second).isEqualTo(EnrollmentApproval.Replace("t2", "Bedroom TV 2"))
    }

    @Test
    fun `approval failures keep the review with the reason`() = runTest {
        interactor.approveResult = Result.failure(AdminApiException(409))
        val viewModel = viewModel(external())
        advanceUntilIdle()

        viewModel.onApprove()
        advanceUntilIdle()

        assertThat(viewModel.review.error).isEqualTo(ReviewError.NOT_PENDING)
        assertThat(viewModel.review.isSubmitting).isFalse()
        viewModel.onClose()
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.UNKNOWN_CODE)))
    }

    @Test
    fun `an invalid name is refused before approving`() = runTest {
        val viewModel = viewModel(external())
        advanceUntilIdle()

        viewModel.onNameChanged("   ")
        viewModel.onApprove()
        advanceUntilIdle()

        assertThat(viewModel.review.invalidName).isTrue()
        assertThat(interactor.approvals).isEmpty()
    }

    @Test
    fun `one approval at a time, and closing waits for it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        interactor.approveGate = gate
        val viewModel = viewModel(external())
        advanceUntilIdle()

        viewModel.onApprove()
        viewModel.onApprove()
        advanceUntilIdle()
        viewModel.onClose()
        viewModel.onBack()
        assertThat(viewModel.review.isSubmitting).isTrue()

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(interactor.approvals).hasSize(1)
        assertThat(viewModel.state.value).isInstanceOf(ApproveDeviceScreenState.Done::class.java)
    }

    @Test
    fun `denying does not block the address unless asked`() = runTest {
        val viewModel = viewModel(external())
        advanceUntilIdle()

        viewModel.onDeny(blockSource = false)
        advanceUntilIdle()

        assertThat(interactor.denials).containsExactly("WDJB-MJHT" to false)
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.Done(DoneResult.Denied(blocked = false)))
        viewModel.onClose()
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.DENIED)))
    }

    @Test
    fun `denying can block the address`() = runTest {
        val viewModel = viewModel(external())
        advanceUntilIdle()

        viewModel.onDeny(blockSource = true)
        advanceUntilIdle()

        assertThat(interactor.denials).containsExactly("WDJB-MJHT" to true)
    }

    @Test
    fun `back goes from the review to the code, then closes`() = runTest {
        val viewModel = viewModel(external())
        advanceUntilIdle()

        viewModel.onBack()
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.EnterCode("WDJB-MJHT"))
        viewModel.onBack()
        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(ApprovalError.CANCELLED)))
    }

    @Test
    fun `from Settings another device can be approved afterwards`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onScanned("WDJB-MJHT")
        advanceUntilIdle()
        viewModel.onApprove()
        advanceUntilIdle()

        viewModel.onApproveAnother()

        assertThat(viewModel.state.value).isEqualTo(ApproveDeviceScreenState.EnterCode())
    }

    @Test
    fun `an unreadable group list leaves the server default`() = runTest {
        interactor.groupsResult = Result.failure(IOException("offline"))
        val viewModel = viewModel(external())
        advanceUntilIdle()

        assertThat(viewModel.review.groupsUnavailable).isTrue()
        viewModel.onApprove()
        advanceUntilIdle()
        assertThat(interactor.approvals.single().second).isEqualTo(EnrollmentApproval.NewDevice("Mi Box (living room)", null))
    }

    private class FakeApproveInteractor : ApproveDeviceViewModel.Interactor {
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("admin@example.com"))
        override val authState: StateFlow<AuthState> = auth
        var admin: Result<Boolean> = Result.success(true)
        var adminChecks = 0
        var pending = PENDING
        var lookupResult: Result<PendingEnrollment>? = null
        var groupsResult: Result<List<AppGroupSummary>> = Result.success(
            listOf(AppGroupSummary(1, "all", "all"), AppGroupSummary(3, "family", null)),
        )
        var approveResult: Result<ApprovedEnrollment>? = null
        var approveGate: CompletableDeferred<Unit>? = null
        val lookups = mutableListOf<String>()
        val approvals = mutableListOf<Pair<String, EnrollmentApproval>>()
        val denials = mutableListOf<Pair<String, Boolean>>()

        override fun serverUrl() = "https://store.example.com"
        override suspend fun isAdmin(): Result<Boolean> {
            adminChecks++
            return admin
        }
        override suspend fun lookup(userCode: String): Result<PendingEnrollment> {
            lookups += userCode
            return lookupResult ?: Result.success(pending)
        }
        override suspend fun groups() = groupsResult
        override suspend fun deviceTokens() = Result.success(TOKENS)
        override suspend fun approve(userCode: String, approval: EnrollmentApproval): Result<ApprovedEnrollment> {
            approvals += userCode to approval
            approveGate?.await()
            return approveResult ?: Result.success(
                ApprovedEnrollment(
                    DeviceTokenSummary("new", (approval as? EnrollmentApproval.NewDevice)?.name ?: "x", "2026-10-21 10:01:00", null),
                    rekeyed = false,
                ),
            )
        }
        override suspend fun deny(userCode: String, blockSource: Boolean): Result<Unit> {
            denials += userCode to blockSource
            return Result.success(Unit)
        }
        override fun elapsedRealtimeMillis() = 0L
    }

    private companion object {
        val PENDING = PendingEnrollment(
            "WDJB-MJHT", "Mi Box (living room)", "MIBOX4", 28, "1.0.0", "198.51.100.7", "2026-10-21 10:00:00", 75, 525,
        )
        val TOKENS = listOf(
            DeviceTokenSummary("t1", "Mi Box (living room)", "2026-01-01 10:00:00", "2026-10-01 10:00:00"),
            DeviceTokenSummary("t2", "Bedroom TV", "2026-02-01 10:00:00", null),
            DeviceTokenSummary("t3", "Old box", "2025-02-01 10:00:00", "2026-03-01 10:00:00"),
        )
    }
}
