package com.lelloman.store.ui.screen.approve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.AppGroupSummary
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.domain.enrollment.ApprovedEnrollment
import com.lelloman.store.domain.enrollment.EnrollmentApproval
import com.lelloman.store.domain.enrollment.PendingEnrollment
import com.lelloman.store.domain.enrollment.UserCode
import com.lelloman.store.ui.model.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Approves or denies another device's enrollment as an administrator: find the request by its
 * user code (typed or scanned), review what the device reported, then approve it as a new device
 * (name, groups) or as a replacement of an existing device token (rekeyed: it keeps its groups and
 * grants), or deny it.
 *
 * Started from Settings or, through `APPROVE_DEVICE`, by another app for result; nothing secret
 * is ever returned or shown.
 */
@HiltViewModel
class ApproveDeviceViewModel @Inject constructor(
    private val interactor: Interactor,
) : ViewModel() {

    private val mutableState = MutableStateFlow<ApproveDeviceScreenState>(ApproveDeviceScreenState.Loading)
    val state: StateFlow<ApproveDeviceScreenState> = mutableState.asStateFlow()

    private var started = false
    private var request = ApproveDeviceRequest()
    private var authJob: Job? = null
    private var groupsTouched = false
    private var outcome: ApprovalOutcome? = null

    // What an abandoned external request reports.
    private var lastError: ApprovalError? = null

    /** Called on every activity creation; only the first call per view model has an effect. */
    fun start(incoming: ApproveDeviceRequest) {
        if (started) return
        started = true
        request = incoming
        if (incoming.external) {
            if (incoming.callerPackage == null) return finish(ApprovalError.CALLER_UNKNOWN)
            if (incoming.malformed) return finish(ApprovalError.INVALID_REQUEST)
            if (incoming.userCode != null && UserCode.normalize(incoming.userCode) == null) {
                return finish(ApprovalError.INVALID_REQUEST)
            }
            if (incoming.deviceName != null && !isValidName(incoming.deviceName.trim())) {
                return finish(ApprovalError.INVALID_REQUEST)
            }
        }
        authJob = viewModelScope.launch {
            interactor.authState.collect { auth ->
                val current = mutableState.value
                // Past the sign-in gate, a later sign-out surfaces as a refused request instead.
                if (current !is ApproveDeviceScreenState.Loading && current !is ApproveDeviceScreenState.NeedsLogin &&
                    current !is ApproveDeviceScreenState.NotAdmin
                ) return@collect
                when (auth) {
                    AuthState.Loading -> mutableState.value = ApproveDeviceScreenState.Loading
                    AuthState.NotAuthenticated -> {
                        lastError = ApprovalError.NOT_LOGGED_IN
                        mutableState.value = ApproveDeviceScreenState.NeedsLogin
                    }
                    is AuthState.Authenticated -> if (auth.deviceToken) notAdmin() else checkAdmin()
                }
            }
        }
    }

    private suspend fun checkAdmin() {
        mutableState.value = ApproveDeviceScreenState.Loading
        // When this cannot be checked (offline) continue: the server refuses non-administrators.
        if (interactor.isAdmin().getOrNull() == false) return notAdmin()
        lastError = null
        val code = request.userCode?.let(UserCode::normalize)
        mutableState.value = ApproveDeviceScreenState.EnterCode(code.orEmpty())
        if (code != null) lookup(code)
    }

    private fun notAdmin() {
        lastError = ApprovalError.NOT_AUTHORIZED
        mutableState.value = ApproveDeviceScreenState.NotAdmin
    }

    fun onCodeChanged(code: String) {
        val current = mutableState.value as? ApproveDeviceScreenState.EnterCode ?: return
        if (current.isLookingUp) return
        mutableState.value = current.copy(code = code.take(MAX_CODE_INPUT), error = null)
    }

    fun onScanned(contents: String) {
        val current = mutableState.value as? ApproveDeviceScreenState.EnterCode ?: return
        if (current.isLookingUp) return
        when (val scanned = EnrollmentQr.parse(contents, interactor.serverUrl())) {
            is ScannedEnrollment.Code -> {
                mutableState.value = current.copy(code = scanned.userCode, error = null)
                viewModelScope.launch { lookup(scanned.userCode) }
            }
            is ScannedEnrollment.OtherStore -> mutableState.value = current.copy(error = CodeError.OTHER_STORE)
            ScannedEnrollment.Invalid -> mutableState.value = current.copy(error = CodeError.INVALID)
        }
    }

    fun onLookup() {
        val current = mutableState.value as? ApproveDeviceScreenState.EnterCode ?: return
        if (current.isLookingUp) return
        val code = UserCode.normalize(current.code)
            ?: return run { mutableState.value = current.copy(error = CodeError.INVALID) }
        viewModelScope.launch { lookup(code) }
    }

    private suspend fun lookup(code: String) {
        mutableState.value = ApproveDeviceScreenState.EnterCode(code, isLookingUp = true)
        interactor.lookup(code).fold(
            onSuccess = { pending -> review(pending) },
            onFailure = { error ->
                rethrowCancellation(error)
                val codeError = when ((error as? AdminApiException)?.status) {
                    404 -> CodeError.NOT_FOUND
                    409 -> CodeError.NOT_PENDING
                    429 -> CodeError.TOO_MANY
                    401, 403 -> CodeError.NOT_AUTHORIZED
                    else -> CodeError.NETWORK
                }
                lastError = when (codeError) {
                    CodeError.NOT_FOUND, CodeError.NOT_PENDING -> ApprovalError.UNKNOWN_CODE
                    CodeError.NOT_AUTHORIZED -> ApprovalError.NOT_AUTHORIZED
                    else -> ApprovalError.NETWORK
                }
                mutableState.value = ApproveDeviceScreenState.EnterCode(code, error = codeError)
            },
        )
    }

    private fun review(pending: PendingEnrollment) {
        groupsTouched = false
        val suggested = request.deviceName?.trim()?.takeIf(::isValidName)
        mutableState.value = ApproveDeviceScreenState.Review(
            pending = pending,
            lookedUpAtMillis = interactor.elapsedRealtimeMillis(),
            name = suggested ?: pending.requestedName,
        )
        viewModelScope.launch {
            val groups = interactor.groups()
            updateReview { review ->
                review.copy(
                    groups = groups.getOrNull()?.map { it.toOption() },
                    groupsUnavailable = groups.isFailure,
                )
            }
        }
        viewModelScope.launch {
            val tokens = interactor.deviceTokens()
            updateReview { review ->
                review.copy(
                    tokens = tokens.getOrNull()?.sortedWith(compareBy({ it.revokedAt != null }, { it.name.lowercase() })),
                    tokensUnavailable = tokens.isFailure,
                )
            }
        }
    }

    fun onModeChanged(mode: ApprovalMode) {
        updateReview { review ->
            if (review.isSubmitting || review.mode == mode) return@updateReview review
            when (mode) {
                ApprovalMode.NEW_DEVICE -> review.copy(
                    mode = mode,
                    name = request.deviceName?.trim()?.takeIf(::isValidName) ?: review.pending.requestedName,
                    invalidName = false,
                    error = null,
                )
                ApprovalMode.REPLACE -> {
                    // A reset box usually asks under its old name: preselect that token.
                    val match = review.tokens?.firstOrNull { it.name == review.pending.requestedName }
                    val selected = review.tokens?.firstOrNull { it.id == review.replaceTokenId } ?: match
                    review.copy(
                        mode = mode,
                        replaceTokenId = selected?.id,
                        name = selected?.name ?: review.name,
                        invalidName = false,
                        error = null,
                    )
                }
            }
        }
    }

    fun onReplaceTokenSelected(id: String) {
        updateReview { review ->
            val token = review.tokens?.firstOrNull { it.id == id } ?: return@updateReview review
            if (review.isSubmitting) return@updateReview review
            review.copy(replaceTokenId = token.id, name = token.name, invalidName = false, error = null)
        }
    }

    fun onGroupToggled(id: Long) {
        updateReview { review ->
            if (review.isSubmitting) return@updateReview review
            groupsTouched = true
            review.copy(groups = review.groups?.map { if (it.id == id) it.copy(selected = !it.selected) else it })
        }
    }

    fun onNameChanged(name: String) {
        updateReview { review ->
            if (review.isSubmitting) review else review.copy(name = name.take(MAX_NAME_LENGTH * 2), invalidName = false)
        }
    }

    fun onApprove() {
        val review = mutableState.value as? ApproveDeviceScreenState.Review ?: return
        if (!review.canApprove) return
        val name = review.name.trim()
        if (!isValidName(name)) {
            mutableState.value = review.copy(invalidName = true)
            return
        }
        val approval = when (review.mode) {
            ApprovalMode.NEW_DEVICE -> EnrollmentApproval.NewDevice(
                name = name,
                // Untouched groups keep the server's default (the `all` group).
                groupIds = review.groups?.takeIf { groupsTouched }?.filter { it.selected }?.map { it.id },
            )
            ApprovalMode.REPLACE -> {
                val token = review.tokens?.firstOrNull { it.id == review.replaceTokenId } ?: return
                EnrollmentApproval.Replace(token.id, name.takeIf { it != token.name })
            }
        }
        mutableState.value = review.copy(isSubmitting = true, error = null, invalidName = false)
        viewModelScope.launch {
            interactor.approve(review.pending.userCode, approval).fold(
                onSuccess = { approved -> approved(approved) },
                onFailure = { error ->
                    rethrowCancellation(error)
                    val status = (error as? AdminApiException)?.status
                    val reviewError = when (status) {
                        400 -> if (review.mode == ApprovalMode.REPLACE) ReviewError.TOKEN_NOT_REKEYABLE else ReviewError.INVALID_NAME_OR_GROUPS
                        404 -> ReviewError.NOT_FOUND
                        409 -> ReviewError.NOT_PENDING
                        401, 403 -> ReviewError.NOT_AUTHORIZED
                        else -> ReviewError.NETWORK
                    }
                    lastError = reviewError.toApprovalError()
                    updateReview { it.copy(isSubmitting = false, error = reviewError) }
                },
            )
        }
    }

    private fun approved(approved: ApprovedEnrollment) {
        outcome = ApprovalOutcome.Approved(approved.deviceToken.id, approved.rekeyed)
        mutableState.value = ApproveDeviceScreenState.Done(DoneResult.Approved(approved.deviceToken.name, approved.rekeyed))
    }

    /** Denies the request; [blockSource] also refuses enrollments from its address for 24 hours. */
    fun onDeny(blockSource: Boolean) {
        val review = mutableState.value as? ApproveDeviceScreenState.Review ?: return
        if (review.isSubmitting) return
        mutableState.value = review.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            interactor.deny(review.pending.userCode, blockSource).fold(
                onSuccess = {
                    outcome = ApprovalOutcome.Failed(ApprovalError.DENIED)
                    mutableState.value = ApproveDeviceScreenState.Done(DoneResult.Denied(blockSource))
                },
                onFailure = { error ->
                    rethrowCancellation(error)
                    val reviewError = when ((error as? AdminApiException)?.status) {
                        404 -> ReviewError.NOT_FOUND
                        409 -> ReviewError.NOT_PENDING
                        401, 403 -> ReviewError.NOT_AUTHORIZED
                        else -> ReviewError.NETWORK
                    }
                    lastError = reviewError.toApprovalError()
                    updateReview { it.copy(isSubmitting = false, error = reviewError) }
                },
            )
        }
    }

    /** Back: from the review to the code; anywhere else the request ends. Ignored while submitting. */
    fun onBack() {
        when (val current = mutableState.value) {
            is ApproveDeviceScreenState.Review -> if (!current.isSubmitting) {
                mutableState.value = ApproveDeviceScreenState.EnterCode(current.pending.userCode)
            }
            is ApproveDeviceScreenState.EnterCode -> if (!current.isLookingUp) onClose()
            else -> onClose()
        }
    }

    /** Ends the request with what happened (approved, denied, or why it did not get there). */
    fun onClose() {
        when (val current = mutableState.value) {
            is ApproveDeviceScreenState.Finished -> return
            is ApproveDeviceScreenState.Review -> if (current.isSubmitting) return
            else -> Unit
        }
        authJob?.cancel()
        mutableState.value = ApproveDeviceScreenState.Finished(
            outcome ?: ApprovalOutcome.Failed(lastError ?: ApprovalError.CANCELLED),
        )
    }

    /** After a result, approve the next device (only from LelloStore itself). */
    fun onApproveAnother() {
        if (mutableState.value !is ApproveDeviceScreenState.Done || request.external) return
        outcome = null
        lastError = null
        mutableState.value = ApproveDeviceScreenState.EnterCode()
    }

    private fun finish(error: ApprovalError) {
        authJob?.cancel()
        mutableState.value = ApproveDeviceScreenState.Finished(ApprovalOutcome.Failed(error))
    }

    private inline fun updateReview(transform: (ApproveDeviceScreenState.Review) -> ApproveDeviceScreenState.Review) {
        val current = mutableState.value as? ApproveDeviceScreenState.Review ?: return
        mutableState.value = transform(current)
    }

    private fun AppGroupSummary.toOption() = GroupOption(id, name, isAll = systemKind == ALL_GROUP, selected = systemKind == ALL_GROUP)

    private fun ReviewError.toApprovalError() = when (this) {
        ReviewError.NOT_PENDING, ReviewError.NOT_FOUND -> ApprovalError.UNKNOWN_CODE
        ReviewError.NOT_AUTHORIZED -> ApprovalError.NOT_AUTHORIZED
        ReviewError.INVALID_NAME_OR_GROUPS, ReviewError.TOKEN_NOT_REKEYABLE -> ApprovalError.INVALID_REQUEST
        ReviewError.NETWORK -> ApprovalError.NETWORK
    }

    private fun rethrowCancellation(error: Throwable) {
        if (error is CancellationException) throw error
    }

    interface Interactor {
        val authState: StateFlow<AuthState>

        /** The selected store, which scanned codes must belong to. */
        fun serverUrl(): String
        suspend fun isAdmin(): Result<Boolean>
        suspend fun lookup(userCode: String): Result<PendingEnrollment>
        suspend fun groups(): Result<List<AppGroupSummary>>
        suspend fun deviceTokens(): Result<List<DeviceTokenSummary>>
        suspend fun approve(userCode: String, approval: EnrollmentApproval): Result<ApprovedEnrollment>
        suspend fun deny(userCode: String, blockSource: Boolean): Result<Unit>
        fun elapsedRealtimeMillis(): Long
    }

    companion object {
        /** The server's device-token name limit. */
        const val MAX_NAME_LENGTH = 100
        private const val MAX_CODE_INPUT = 32
        private const val ALL_GROUP = "all"

        /** Trimmed, 1..[MAX_NAME_LENGTH] characters, no control or bidirectional override characters. */
        internal fun isValidName(name: String): Boolean =
            name.length in 1..MAX_NAME_LENGTH &&
                name.none { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' }
    }
}
