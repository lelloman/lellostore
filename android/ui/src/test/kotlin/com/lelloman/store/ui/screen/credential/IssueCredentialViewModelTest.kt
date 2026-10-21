package com.lelloman.store.ui.screen.credential

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.credential.CredentialDescription
import com.lelloman.store.domain.credential.CredentialError
import com.lelloman.store.domain.credential.CredentialIssuer
import com.lelloman.store.domain.credential.CredentialRequest
import com.lelloman.store.domain.credential.IssueResult
import com.lelloman.store.ui.model.AuthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IssueCredentialViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val issuer = FakeIssuer()
    private val interactor = FakeInteractor(issuer)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun request(
        callerPackage: String? = "com.lelloman.pezzottv",
        type: String? = FakeIssuer.TYPE,
        subjectName: String? = "  Mi Box (living room) ",
        replaceExisting: Boolean = false,
        malformed: Boolean = false,
    ) = IncomingCredentialRequest(
        callerPackage = callerPackage,
        callerLabel = "PezzotTV",
        type = type,
        subjectName = subjectName,
        replaceExisting = replaceExisting,
        malformed = malformed,
    )

    private fun TestScope.started(
        incoming: IncomingCredentialRequest = request(),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): IssueCredentialViewModel {
        val viewModel = IssueCredentialViewModel(interactor, savedState)
        viewModel.start(incoming)
        advanceUntilIdle()
        return viewModel
    }

    private val IssueCredentialViewModel.outcome: CredentialOutcome?
        get() = (state.value as? IssueCredentialScreenState.Finished)?.outcome

    @Test
    fun `a valid request asks for confirmation with the trimmed subject`() = runTest {
        val viewModel = started()

        val state = viewModel.state.value as IssueCredentialScreenState.Confirm
        assertThat(state.subjectName).isEqualTo("Mi Box (living room)")
        assertThat(state.account).isEqualTo("admin@example.com")
        assertThat(state.description.message).isEqualTo("PezzotTV wants Mi Box (living room)")
        assertThat(issuer.issued).isEmpty()
    }

    @Test
    fun `confirming issues once and returns the credential with its type`() = runTest {
        val viewModel = started(request(replaceExisting = true))

        viewModel.onConfirm()
        advanceUntilIdle()

        val outcome = viewModel.outcome as CredentialOutcome.Issued
        assertThat(outcome.type).isEqualTo(FakeIssuer.TYPE)
        assertThat(outcome.credential).isEqualTo(FakeIssuer.CREDENTIAL)
        assertThat(outcome.toString()).doesNotContain(FakeIssuer.CREDENTIAL)
        assertThat(issuer.issued).containsExactly(
            CredentialRequest(
                type = FakeIssuer.TYPE,
                callerPackage = "com.lelloman.pezzottv",
                callerLabel = "PezzotTV",
                subjectName = "Mi Box (living room)",
                replaceExisting = true,
            ),
        )
    }

    @Test
    fun `declining returns cancelled without issuing`() = runTest {
        val viewModel = started()

        viewModel.onCancel()
        advanceUntilIdle()

        assertThat(viewModel.outcome).isEqualTo(CredentialOutcome.Failed(CredentialError.CANCELLED))
        assertThat(issuer.issued).isEmpty()
    }

    @Test
    fun `issuer failures are returned as their error`() = runTest {
        issuer.result = { IssueResult.Failure(CredentialError.NOT_AUTHORIZED) }
        val viewModel = started()

        viewModel.onConfirm()
        advanceUntilIdle()

        assertThat(viewModel.outcome).isEqualTo(CredentialOutcome.Failed(CredentialError.NOT_AUTHORIZED))
    }

    @Test
    fun `an issuer exception is a network error`() = runTest {
        issuer.result = { throw java.io.IOException("offline") }
        val viewModel = started()

        viewModel.onConfirm()
        advanceUntilIdle()

        assertThat(viewModel.outcome).isEqualTo(CredentialOutcome.Failed(CredentialError.NETWORK))
    }

    @Test
    fun `without a session the request ends as not logged in`() = runTest {
        interactor.authState.value = AuthState.NotAuthenticated
        val viewModel = started()

        assertThat(viewModel.state.value).isEqualTo(IssueCredentialScreenState.NeedsLogin)
        viewModel.onConfirm()
        viewModel.onCancel()
        advanceUntilIdle()

        assertThat(viewModel.outcome).isEqualTo(CredentialOutcome.Failed(CredentialError.NOT_LOGGED_IN))
        assertThat(issuer.issued).isEmpty()
    }

    @Test
    fun `confirmation waits for the session to load`() = runTest {
        interactor.authState.value = AuthState.Loading
        val viewModel = started()
        assertThat(viewModel.state.value).isEqualTo(IssueCredentialScreenState.Loading)

        interactor.authState.value = AuthState.Authenticated("admin@example.com")
        advanceUntilIdle()

        assertThat(viewModel.state.value).isInstanceOf(IssueCredentialScreenState.Confirm::class.java)
    }

    @Test
    fun `invalid subject names are refused before confirmation`() = runTest {
        val invalid = listOf(null, "", "   ", "x".repeat(65), "TV\nOK", "TV‮evil", "TV\u0000")
        for (subject in invalid) {
            val viewModel = started(request(subjectName = subject))
            assertThat(viewModel.outcome).isEqualTo(CredentialOutcome.Failed(CredentialError.INVALID_REQUEST))
        }
        assertThat(started(request(subjectName = "x".repeat(64))).state.value)
            .isInstanceOf(IssueCredentialScreenState.Confirm::class.java)
        assertThat(issuer.issued).isEmpty()
    }

    @Test
    fun `unknown types are unsupported and a missing type is invalid`() = runTest {
        assertThat(started(request(type = "pezzottify.identity")).outcome)
            .isEqualTo(CredentialOutcome.Failed(CredentialError.UNSUPPORTED_TYPE))
        assertThat(started(request(type = null)).outcome)
            .isEqualTo(CredentialOutcome.Failed(CredentialError.INVALID_REQUEST))
        assertThat(started(request(type = " ")).outcome)
            .isEqualTo(CredentialOutcome.Failed(CredentialError.INVALID_REQUEST))
    }

    @Test
    fun `an unknown caller or malformed extras are refused`() = runTest {
        assertThat(started(request(callerPackage = null)).outcome)
            .isEqualTo(CredentialOutcome.Failed(CredentialError.CALLER_UNKNOWN))
        assertThat(started(request(malformed = true)).outcome)
            .isEqualTo(CredentialOutcome.Failed(CredentialError.INVALID_REQUEST))
    }

    @Test
    fun `type-specific validation is applied`() = runTest {
        issuer.validation = CredentialError.INVALID_REQUEST

        assertThat(started().outcome).isEqualTo(CredentialOutcome.Failed(CredentialError.INVALID_REQUEST))
    }

    @Test
    fun `repeated confirms and an activity recreation issue only once`() = runTest {
        val pending = CompletableDeferred<IssueResult>()
        issuer.result = { pending.await() }
        val viewModel = started()

        viewModel.onConfirm()
        viewModel.onConfirm()
        advanceUntilIdle()
        // A configuration change creates a new activity that starts the same view model again.
        viewModel.start(request())
        viewModel.onCancel()
        viewModel.onConfirm()
        advanceUntilIdle()
        assertThat((viewModel.state.value as IssueCredentialScreenState.Confirm).isIssuing).isTrue()

        pending.complete(IssueResult.Success(FakeIssuer.CREDENTIAL))
        advanceUntilIdle()

        assertThat(issuer.issued).hasSize(1)
        assertThat(viewModel.outcome).isInstanceOf(CredentialOutcome.Issued::class.java)
    }

    @Test
    fun `a request restored after process death following a confirmation is not issued again`() = runTest {
        val savedState = SavedStateHandle()
        val pending = CompletableDeferred<IssueResult>()
        issuer.result = { pending.await() }
        started(savedState = savedState).onConfirm()
        advanceUntilIdle()

        // Process death: a new view model restored from the same saved state.
        val restored = started(savedState = savedState)

        assertThat(restored.outcome).isEqualTo(CredentialOutcome.Failed(CredentialError.NETWORK))
        assertThat(issuer.issued).hasSize(1)
        assertThat(savedState.keys().map { savedState.get<Any>(it) }).containsExactly(true)
    }

    @Test
    fun `nothing changes once finished`() = runTest {
        val viewModel = started()
        viewModel.onConfirm()
        advanceUntilIdle()

        interactor.authState.value = AuthState.NotAuthenticated
        viewModel.onCancel()
        viewModel.onConfirm()
        advanceUntilIdle()

        assertThat(viewModel.outcome).isInstanceOf(CredentialOutcome.Issued::class.java)
        assertThat(issuer.issued).hasSize(1)
    }

    private class FakeInteractor(private val issuer: CredentialIssuer) : IssueCredentialViewModel.Interactor {
        override val authState = MutableStateFlow<AuthState>(AuthState.Authenticated("admin@example.com"))
        override fun issuer(type: String): CredentialIssuer? = issuer.takeIf { it.type == type }
    }

    private class FakeIssuer : CredentialIssuer {
        val issued = mutableListOf<CredentialRequest>()
        var validation: CredentialError? = null
        var result: suspend () -> IssueResult = { IssueResult.Success(CREDENTIAL) }

        override val type = TYPE
        override fun validate(request: CredentialRequest) = validation
        override fun describe(request: CredentialRequest) =
            CredentialDescription("Title", "${request.callerLabel} wants ${request.subjectName}")

        override suspend fun issue(request: CredentialRequest): IssueResult {
            issued += request
            return result()
        }

        companion object {
            const val TYPE = "lellostore.device_token"
            const val CREDENTIAL = "{\"token\":\"lellostore_dev_fake\"}"
        }
    }
}
