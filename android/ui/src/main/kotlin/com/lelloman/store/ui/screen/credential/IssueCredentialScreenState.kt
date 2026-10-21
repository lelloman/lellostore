package com.lelloman.store.ui.screen.credential

import com.lelloman.store.domain.credential.CredentialDescription
import com.lelloman.store.domain.credential.CredentialError

/**
 * What the calling activity sent, before validation. [malformed] marks extras that could not
 * be read, or params that are not all strings.
 */
data class IncomingCredentialRequest(
    val callerPackage: String?,
    val callerLabel: String,
    val type: String?,
    val subjectName: String?,
    val replaceExisting: Boolean = false,
    val params: Map<String, String> = emptyMap(),
    val malformed: Boolean = false,
)

sealed interface IssueCredentialScreenState {
    data object Loading : IssueCredentialScreenState

    /**
     * LelloStore has no session. The request ends with `not_logged_in`; the user signs in
     * in LelloStore itself and the caller asks again.
     */
    data object NeedsLogin : IssueCredentialScreenState

    data class Confirm(
        val description: CredentialDescription,
        val subjectName: String,
        val account: String,
        val isIssuing: Boolean = false,
    ) : IssueCredentialScreenState

    /** Terminal: the activity returns [outcome] to the caller and finishes. */
    data class Finished(val outcome: CredentialOutcome) : IssueCredentialScreenState
}

sealed interface CredentialOutcome {
    class Issued(val type: String, val credential: String) : CredentialOutcome {
        // Keep the secret out of logs and crash reports.
        override fun toString(): String = "Issued(type=$type, credential=<redacted>)"
    }

    data class Failed(val error: CredentialError) : CredentialOutcome
}
