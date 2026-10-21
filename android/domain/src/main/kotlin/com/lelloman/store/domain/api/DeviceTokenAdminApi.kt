package com.lelloman.store.domain.api

/**
 * Administrator routes of the selected store used to issue device tokens:
 * `/api/admin/device-tokens`, plus the app groups and direct app grants of their principals.
 */
interface DeviceTokenAdminApi {
    /** Creates a token whose principal joins [groupIds] in the same server transaction. */
    suspend fun create(name: String, groupIds: List<Long> = emptyList()): Result<CreatedDeviceToken>
    suspend fun list(): Result<List<DeviceTokenSummary>>
    suspend fun revoke(id: String): Result<Unit>

    /** Every app group of the store, system groups (such as `all`) included. */
    suspend fun listGroups(): Result<List<AppGroupSummary>>

    /** The direct per-app grants of the principal of the token [tokenId] (not those it gets from groups). */
    suspend fun directGrants(tokenId: String): Result<List<AppGrant>>

    /** Grants the principal of the token [tokenId] direct access to [grant]'s app. */
    suspend fun setDirectGrant(tokenId: String, grant: AppGrant): Result<Unit>
}

/**
 * A device token without its secret; times are the server's `YYYY-MM-DD HH:MM:SS` UTC strings.
 * [groupIds] are the app groups its principal is a member of.
 */
data class DeviceTokenSummary(
    val id: String,
    val name: String,
    val createdAt: String,
    val revokedAt: String?,
    val groupIds: List<Long> = emptyList(),
)

/** An app group; [systemKind] is set for groups the server manages itself, e.g. `all`. */
data class AppGroupSummary(val id: Long, val name: String, val systemKind: String?)

/** Access to one app; [accessLevel] is the server's level, `stable` or `beta`. */
data class AppGrant(val packageName: String, val accessLevel: String)

/** A newly created token, its one-time [secret], and the store it was created on. */
class CreatedDeviceToken(
    val token: DeviceTokenSummary,
    val secret: String,
    val serverUrl: String,
) {
    // Keep the secret out of logs and crash reports.
    override fun toString(): String = "CreatedDeviceToken(token=$token, serverUrl=$serverUrl)"
}

/** The server answered with a non-success [status]. */
class AdminApiException(val status: Int) : Exception("Admin request failed: HTTP $status")
