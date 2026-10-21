package com.lelloman.store.credential

import android.content.Context
import com.lelloman.store.R
import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.CreatedDeviceToken
import com.lelloman.store.domain.api.DeviceTokenAdminApi
import com.lelloman.store.domain.credential.CredentialDescription
import com.lelloman.store.domain.credential.CredentialError
import com.lelloman.store.domain.credential.CredentialIssuer
import com.lelloman.store.domain.credential.CredentialRequest
import com.lelloman.store.domain.credential.IssueResult
import com.lelloman.store.localdata.auth.DeviceToken
import com.lelloman.store.localdata.auth.DeviceTokenFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * `lellostore.device_token`: creates a device token named after the subject with the
 * signed-in administrator's session and returns it as the same `device-token.json` the web
 * admin page downloads. With `replaceExisting`, older unrevoked tokens with the same name are
 * revoked afterwards; that is best effort, the new token is returned even if a revocation fails.
 */
class DeviceTokenCredentialIssuer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DeviceTokenAdminApi,
) : CredentialIssuer {

    override val type: String = TYPE

    override fun describe(request: CredentialRequest): CredentialDescription {
        val message = context.getString(R.string.credential_device_token_message, request.callerLabel, request.subjectName)
        return CredentialDescription(
            title = context.getString(R.string.credential_device_token_title),
            message = if (request.replaceExisting) {
                message + " " + context.getString(R.string.credential_device_token_replace, request.subjectName)
            } else {
                message
            },
        )
    }

    override suspend fun issue(request: CredentialRequest): IssueResult {
        val created = api.create(request.subjectName).getOrElse { return IssueResult.Failure(it.toCredentialError()) }
        if (request.replaceExisting) revokeOlderTokens(created)
        val file = DeviceToken(name = created.token.name, serverUrl = created.serverUrl, token = created.secret)
        return IssueResult.Success(DeviceTokenFile.serialize(file))
    }

    private suspend fun revokeOlderTokens(created: CreatedDeviceToken) {
        val tokens = api.list().getOrElse { it.rethrowCancellation(); return }
        tokens
            .filter {
                it.id != created.token.id &&
                    it.name == created.token.name &&
                    it.revokedAt == null &&
                    // Server times are `YYYY-MM-DD HH:MM:SS` UTC, so they order as strings.
                    it.createdAt <= created.token.createdAt
            }
            .forEach { api.revoke(it.id).onFailure { error -> error.rethrowCancellation() } }
    }

    private fun Throwable.toCredentialError(): CredentialError {
        rethrowCancellation()
        return when ((this as? AdminApiException)?.status) {
            401 -> CredentialError.NOT_LOGGED_IN
            403 -> CredentialError.NOT_AUTHORIZED
            else -> CredentialError.NETWORK
        }
    }

    // The admin API wraps calls in runCatching; a cancelled request must still cancel.
    private fun Throwable.rethrowCancellation() {
        if (this is CancellationException) throw this
    }

    companion object {
        const val TYPE = "lellostore.device_token"
    }
}
