package com.lelloman.store.credential

/**
 * The inter-app contract of [IssueCredentialActivity]. These strings are a public API that
 * other apps are built against; never rename them. See the "Credential requests from other
 * apps" section of android/ARCHITECTURE.md.
 */
object CredentialContract {
    const val ACTION_ISSUE_CREDENTIAL = "com.lelloman.store.action.ISSUE_CREDENTIAL"

    /** In: the requested type (required). Out on success: the same type. */
    const val EXTRA_CREDENTIAL_TYPE = "com.lelloman.store.extra.CREDENTIAL_TYPE"

    /** In: what the credential is for, shown to the user (required, trimmed, 1..64 characters). */
    const val EXTRA_SUBJECT_NAME = "com.lelloman.store.extra.SUBJECT_NAME"

    /** In: replace earlier credentials of the same subject (optional, default false). */
    const val EXTRA_REPLACE_EXISTING = "com.lelloman.store.extra.REPLACE_EXISTING"

    /** In: a Bundle of type-specific string parameters (optional). */
    const val EXTRA_PARAMS = "com.lelloman.store.extra.PARAMS"

    /** Out on `RESULT_OK`: the credential string. */
    const val EXTRA_CREDENTIAL = "com.lelloman.store.extra.CREDENTIAL"

    /** Out on `RESULT_CANCELED`: a [com.lelloman.store.domain.credential.CredentialError] wire value. */
    const val EXTRA_ERROR = "com.lelloman.store.extra.ERROR"
}
