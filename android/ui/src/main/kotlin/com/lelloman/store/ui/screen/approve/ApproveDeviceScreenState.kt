package com.lelloman.store.ui.screen.approve

import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.domain.enrollment.PendingEnrollment

/** How the approval was started: from LelloStore itself, or by another app for result. */
data class ApproveDeviceRequest(
    /** Set when another app started `APPROVE_DEVICE` for result (from getCallingActivity()). */
    val callerPackage: String? = null,
    val callerLabel: String = "",
    val external: Boolean = false,
    val userCode: String? = null,
    val deviceName: String? = null,
    val malformed: Boolean = false,
)

sealed interface ApproveDeviceScreenState {
    data object Loading : ApproveDeviceScreenState

    /** LelloStore is not signed in. */
    data object NeedsLogin : ApproveDeviceScreenState

    /** Signed in, but not as an administrator (device tokens never are). */
    data object NotAdmin : ApproveDeviceScreenState

    data class EnterCode(
        val code: String = "",
        val error: CodeError? = null,
        val isLookingUp: Boolean = false,
    ) : ApproveDeviceScreenState

    data class Review(
        val pending: PendingEnrollment,
        /** Elapsed-realtime when [pending] was read, to age it on screen. */
        val lookedUpAtMillis: Long,
        val mode: ApprovalMode = ApprovalMode.NEW_DEVICE,
        val name: String,
        /** Null while loading or when they could not be read (the server then uses `all`). */
        val groups: List<GroupOption>? = null,
        val groupsUnavailable: Boolean = false,
        val tokens: List<DeviceTokenSummary>? = null,
        val tokensUnavailable: Boolean = false,
        val replaceTokenId: String? = null,
        val invalidName: Boolean = false,
        val isSubmitting: Boolean = false,
        val error: ReviewError? = null,
    ) : ApproveDeviceScreenState {
        val canApprove: Boolean
            get() = !isSubmitting && (mode == ApprovalMode.NEW_DEVICE || replaceTokenId != null)
    }

    data class Done(val result: DoneResult) : ApproveDeviceScreenState

    /** The request from another app ended; the activity returns [outcome] and closes. */
    data class Finished(val outcome: ApprovalOutcome) : ApproveDeviceScreenState
}

enum class ApprovalMode { NEW_DEVICE, REPLACE }

data class GroupOption(val id: Long, val name: String, val isAll: Boolean, val selected: Boolean)

enum class CodeError {
    /** Not a user code, or a QR code that is not an enrollment. */
    INVALID,

    /** A QR code of another store. */
    OTHER_STORE,

    /** No pending request with this code (unknown, expired, or already approved or denied). */
    NOT_FOUND,

    /** Already approved, denied or expired. */
    NOT_PENDING,

    /** Too many unknown codes: the server refuses lookups for a while. */
    TOO_MANY,
    NOT_AUTHORIZED,
    NETWORK,
}

enum class ReviewError {
    INVALID_NAME_OR_GROUPS,
    NOT_PENDING,
    NOT_FOUND,
    TOKEN_NOT_REKEYABLE,
    NOT_AUTHORIZED,
    NETWORK,
}

sealed interface DoneResult {
    data class Approved(val name: String, val rekeyed: Boolean) : DoneResult
    data class Denied(val blocked: Boolean) : DoneResult
}

/** The result of an `APPROVE_DEVICE` request; [wireValue] is the contract's ERROR extra. */
sealed interface ApprovalOutcome {
    data class Approved(val deviceTokenId: String, val rekeyed: Boolean) : ApprovalOutcome
    data class Failed(val error: ApprovalError) : ApprovalOutcome
}

enum class ApprovalError(val wireValue: String) {
    CANCELLED("cancelled"),
    NOT_LOGGED_IN("not_logged_in"),
    NOT_AUTHORIZED("not_authorized"),
    INVALID_REQUEST("invalid_request"),
    CALLER_UNKNOWN("caller_unknown"),
    NETWORK("network"),

    /** The administrator denied the request instead of approving it. */
    DENIED("denied"),

    /** No pending request with this code. */
    UNKNOWN_CODE("unknown_code"),
}
