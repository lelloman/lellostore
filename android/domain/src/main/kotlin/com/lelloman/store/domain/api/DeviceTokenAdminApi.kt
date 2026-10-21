package com.lelloman.store.domain.api

/** Administrator device-token routes of the selected store (`/api/admin/device-tokens`). */
interface DeviceTokenAdminApi {
    suspend fun create(name: String): Result<CreatedDeviceToken>
    suspend fun list(): Result<List<DeviceTokenSummary>>
    suspend fun revoke(id: String): Result<Unit>
}

/** A device token without its secret; times are the server's `YYYY-MM-DD HH:MM:SS` UTC strings. */
data class DeviceTokenSummary(
    val id: String,
    val name: String,
    val createdAt: String,
    val revokedAt: String?,
)

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
