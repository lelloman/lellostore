package com.lelloman.store.ui.screen.credential

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lelloman.store.domain.credential.CredentialError
import com.lelloman.store.domain.credential.CredentialIssuer
import com.lelloman.store.domain.credential.CredentialRequest
import com.lelloman.store.domain.credential.IssueResult
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
 * Validates a credential request from another app, waits for a session, and issues the
 * credential at most once after the user confirms.
 *
 * The credential only lives in memory until the activity returns it. A confirmation is
 * recorded in saved state, so an activity restored after process death reports failure
 * instead of offering a second confirmation for the same request.
 */
@HiltViewModel
class IssueCredentialViewModel @Inject constructor(
    private val interactor: Interactor,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val mutableState = MutableStateFlow<IssueCredentialScreenState>(IssueCredentialScreenState.Loading)
    val state: StateFlow<IssueCredentialScreenState> = mutableState.asStateFlow()

    private var started = false
    private var request: CredentialRequest? = null
    private var issuer: CredentialIssuer? = null
    private var authJob: Job? = null

    /** Called on every activity creation; only the first call per view model has an effect. */
    fun start(incoming: IncomingCredentialRequest) {
        if (started) return
        started = true
        if (savedStateHandle.get<Boolean>(KEY_CONFIRMED) == true) {
            // The process died after a confirmation; whether a credential was created is unknown.
            return finish(CredentialError.NETWORK)
        }
        val callerPackage = incoming.callerPackage ?: return finish(CredentialError.CALLER_UNKNOWN)
        if (incoming.malformed) return finish(CredentialError.INVALID_REQUEST)
        val type = incoming.type?.takeIf { it.isNotBlank() } ?: return finish(CredentialError.INVALID_REQUEST)
        val issuer = interactor.issuer(type) ?: return finish(CredentialError.UNSUPPORTED_TYPE)
        val subjectName = incoming.subjectName?.trim()?.takeIf(::isValidSubjectName)
            ?: return finish(CredentialError.INVALID_REQUEST)
        val request = CredentialRequest(
            type = type,
            callerPackage = callerPackage,
            callerLabel = incoming.callerLabel,
            subjectName = subjectName,
            replaceExisting = incoming.replaceExisting,
            params = incoming.params,
        )
        issuer.validate(request)?.let { return finish(it) }
        this.request = request
        this.issuer = issuer
        observeAuthState(request, issuer)
    }

    private fun observeAuthState(request: CredentialRequest, issuer: CredentialIssuer) {
        authJob = viewModelScope.launch {
            interactor.authState.collect { auth ->
                val current = mutableState.value
                if (current is IssueCredentialScreenState.Finished ||
                    (current is IssueCredentialScreenState.Confirm && current.isIssuing)
                ) return@collect
                mutableState.value = when (auth) {
                    AuthState.Loading -> IssueCredentialScreenState.Loading
                    AuthState.NotAuthenticated -> IssueCredentialScreenState.NeedsLogin
                    is AuthState.Authenticated -> IssueCredentialScreenState.Confirm(
                        description = issuer.describe(request),
                        subjectName = request.subjectName,
                        account = auth.userEmail,
                    )
                }
            }
        }
    }

    fun onConfirm() {
        val current = mutableState.value as? IssueCredentialScreenState.Confirm ?: return
        if (current.isIssuing) return
        val request = request ?: return
        val issuer = issuer ?: return
        savedStateHandle[KEY_CONFIRMED] = true
        mutableState.value = current.copy(isIssuing = true)
        viewModelScope.launch {
            val result = try {
                issuer.issue(request)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                IssueResult.Failure(CredentialError.NETWORK)
            }
            when (result) {
                is IssueResult.Success -> finish(CredentialOutcome.Issued(request.type, result.credential))
                is IssueResult.Failure -> finish(result.error)
            }
        }
    }

    /** Back or Cancel. Ignored while issuing, so the outcome of a started request is not lost. */
    fun onCancel() {
        when (val current = mutableState.value) {
            is IssueCredentialScreenState.Finished -> Unit
            is IssueCredentialScreenState.Confirm -> if (!current.isIssuing) finish(CredentialError.CANCELLED)
            IssueCredentialScreenState.NeedsLogin -> finish(CredentialError.NOT_LOGGED_IN)
            IssueCredentialScreenState.Loading -> finish(CredentialError.CANCELLED)
        }
    }

    private fun finish(error: CredentialError) = finish(CredentialOutcome.Failed(error))

    private fun finish(outcome: CredentialOutcome) {
        authJob?.cancel()
        mutableState.value = IssueCredentialScreenState.Finished(outcome)
    }

    interface Interactor {
        val authState: StateFlow<AuthState>
        fun issuer(type: String): CredentialIssuer?
    }

    companion object {
        const val MAX_SUBJECT_NAME_LENGTH = 64
        private const val KEY_CONFIRMED = "credential_confirmed"

        /**
         * 1..[MAX_SUBJECT_NAME_LENGTH] characters, already trimmed. Control and bidirectional
         * override characters are refused: the name is shown to the user as what the caller
         * asks for, so it must not be able to break lines or reorder the confirmation text.
         */
        internal fun isValidSubjectName(name: String): Boolean =
            name.length in 1..MAX_SUBJECT_NAME_LENGTH &&
                name.none { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
    }
}
