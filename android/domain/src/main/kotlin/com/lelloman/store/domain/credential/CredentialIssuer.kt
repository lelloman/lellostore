package com.lelloman.store.domain.credential

/**
 * Issues one type of credential to another app after the user confirms the request.
 * Implementations are registered as a set in DI and selected by [type].
 */
interface CredentialIssuer {
    /** The stable wire identifier callers pass as the credential type, e.g. `lellostore.device_token`. */
    val type: String

    /** Type-specific checks of [request], beyond the generic subject-name rules; `null` accepts it. */
    fun validate(request: CredentialRequest): CredentialError? = null

    /** What the confirmation screen tells the user the caller will receive. */
    fun describe(request: CredentialRequest): CredentialDescription

    /** Called at most once per confirmation. The credential must not be logged or persisted. */
    suspend fun issue(request: CredentialRequest): IssueResult
}

/**
 * A validated request. [subjectName] is trimmed; [params] holds the caller's
 * string-valued type-specific parameters.
 */
data class CredentialRequest(
    val type: String,
    val callerPackage: String,
    val callerLabel: String,
    val subjectName: String,
    val replaceExisting: Boolean = false,
    val params: Map<String, String> = emptyMap(),
)

data class CredentialDescription(val title: String, val message: String)

/** Failure codes returned to the caller; [wireValue] is the contract's ERROR extra. */
enum class CredentialError(val wireValue: String) {
    CANCELLED("cancelled"),
    NOT_LOGGED_IN("not_logged_in"),
    NOT_AUTHORIZED("not_authorized"),
    UNSUPPORTED_TYPE("unsupported_type"),
    INVALID_REQUEST("invalid_request"),
    CALLER_UNKNOWN("caller_unknown"),
    NETWORK("network"),
}

sealed interface IssueResult {
    class Success(val credential: String) : IssueResult {
        // Keep the secret out of logs and crash reports.
        override fun toString(): String = "Success(credential=<redacted>)"
    }

    data class Failure(val error: CredentialError) : IssueResult
}
