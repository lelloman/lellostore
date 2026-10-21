package com.lelloman.store.credential

import android.content.Context
import com.lelloman.store.R
import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.AppGrant
import com.lelloman.store.domain.api.DeviceTokenAdminApi
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.domain.credential.CredentialDescription
import com.lelloman.store.domain.credential.CredentialError
import com.lelloman.store.domain.credential.CredentialIssuer
import com.lelloman.store.domain.credential.CredentialRequest
import com.lelloman.store.domain.credential.IssueResult
import com.lelloman.store.localdata.auth.DeviceToken
import com.lelloman.store.localdata.auth.DeviceTokenFile
import com.lelloman.store.logger.AuditLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * `lellostore.device_token`: creates a device token named after the subject with the
 * signed-in administrator's session and returns it as the same `device-token.json` the web
 * admin page downloads.
 *
 * With `replaceExisting`, the new token takes over the access of the older unrevoked tokens
 * with the same name: it joins the union of their app groups and receives the union of their
 * direct app grants; afterwards those tokens are revoked. When there is nothing to take over
 * (the first token for that name, or earlier tokens without any access) it joins the
 * server's `all` system group, so the device does not start with an empty catalogue.
 *
 * Everything after reading the token list is best effort: the new token is returned even if
 * groups, grants or revocations could not be read or applied. When the earlier access could
 * not be read completely the token never falls back to `all`, so a failed read cannot widen
 * a device's access. What happened is recorded in the local log (counts only).
 */
class DeviceTokenCredentialIssuer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DeviceTokenAdminApi,
    private val audit: AuditLog,
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
        // Read before creating: the new token must not count itself among the replaced ones.
        val replaced = if (request.replaceExisting) {
            api.list()
                .map { tokens -> tokens.filter { it.name == request.subjectName && it.revokedAt == null } }
                .getOrElse { it.rethrowCancellation(); null }
        } else {
            emptyList()
        }
        val access = if (replaced == null) Access.unknown() else inheritedAccess(replaced)
        val created = api.create(request.subjectName, access.groupIds)
            .getOrElse { return IssueResult.Failure(it.toCredentialError()) }
        val grantsSet = access.grants.count { grant ->
            api.setDirectGrant(created.token.id, grant).onFailure { it.rethrowCancellation() }.isSuccess
        }
        val revoked = replaced.orEmpty()
            .filter {
                it.id != created.token.id &&
                    // Server times are `YYYY-MM-DD HH:MM:SS` UTC, so they order as strings.
                    it.createdAt <= created.token.createdAt
            }
            .count { api.revoke(it.id).onFailure { error -> error.rethrowCancellation() }.isSuccess }
        audit.record(
            "credential.device_token.access",
            mapOf(
                "replace" to request.replaceExisting,
                "replaced" to replaced?.size,
                "revoked" to revoked,
                "groups" to created.token.groupIds.size,
                "joined_all" to access.joinedAll,
                "grants" to access.grants.size,
                "grants_set" to grantsSet,
                "complete" to (access.complete && grantsSet == access.grants.size),
            ),
        )
        val file = DeviceToken(name = created.token.name, serverUrl = created.serverUrl, token = created.secret)
        return IssueResult.Success(DeviceTokenFile.serialize(file))
    }

    private suspend fun inheritedAccess(replaced: List<DeviceTokenSummary>): Access {
        var complete = true
        val groupIds = replaced.flatMap { it.groupIds }.distinct().sorted()
        val grants = linkedMapOf<String, AppGrant>()
        for (token in replaced) {
            val tokenGrants = api.directGrants(token.id).getOrElse {
                it.rethrowCancellation()
                complete = false
                emptyList()
            }
            for (grant in tokenGrants) {
                val current = grants[grant.packageName]
                if (current == null || grant.accessLevel.rank() > current.accessLevel.rank()) {
                    grants[grant.packageName] = grant
                }
            }
        }
        if (groupIds.isNotEmpty() || grants.isNotEmpty() || !complete) {
            return Access(groupIds, grants.values.toList(), joinedAll = false, complete = complete)
        }
        val all = api.listGroups()
            .getOrElse { it.rethrowCancellation(); return Access(emptyList(), emptyList(), joinedAll = false, complete = false) }
            .firstOrNull { it.systemKind == ALL_GROUP_KIND }
        return Access(listOfNotNull(all?.id), emptyList(), joinedAll = all != null, complete = true)
    }

    private class Access(val groupIds: List<Long>, val grants: List<AppGrant>, val joinedAll: Boolean, val complete: Boolean) {
        companion object {
            fun unknown() = Access(emptyList(), emptyList(), joinedAll = false, complete = false)
        }
    }

    // `beta` includes `stable`; an unknown level never wins over a known one.
    private fun String.rank(): Int = ACCESS_LEVELS.indexOf(this)

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
        private const val ALL_GROUP_KIND = "all"
        private val ACCESS_LEVELS = listOf("stable", "beta")
    }
}
