package com.lelloman.store.domain.enrollment

import com.lelloman.store.domain.api.DeviceTokenSummary

/**
 * The store-native device-code enrollment (docs/DEVICE_ENROLLMENT.md). A device without a login
 * starts an enrollment, shows the user code and polls with its secret device code until an
 * administrator approves or denies it.
 */
interface DeviceEnrollmentApi {
    /**
     * `POST {serverUrl}/api/device-enrollments`, without credentials. Throws on transport failures
     * and on answers the protocol does not define (for example `5xx`).
     */
    suspend fun start(serverUrl: String, request: EnrollmentRequest): EnrollmentStartResult

    /** `POST {serverUrl}/api/device-enrollments/poll`; throws like [start]. */
    suspend fun poll(serverUrl: String, deviceCode: String): EnrollmentPollResult
}

/** What the device tells the administrator about itself; only [name] is required. */
data class EnrollmentRequest(
    val name: String,
    val model: String? = null,
    val androidSdk: Int? = null,
    val clientVersion: String? = null,
)

/** A started enrollment. [deviceCode] is the secret this device polls with; it never leaves it. */
class StartedEnrollment(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
) {
    // Keep the device code out of logs and crash reports.
    override fun toString(): String =
        "StartedEnrollment(userCode=$userCode, expiresIn=$expiresInSeconds, interval=$intervalSeconds)"
}

sealed interface EnrollmentStartResult {
    data class Started(val enrollment: StartedEnrollment) : EnrollmentStartResult

    /** `403 access_denied`: an administrator recently denied an enrollment from this address. */
    data object Blocked : EnrollmentStartResult

    /** `429 slow_down`: too many enrollments from this address, or overall. */
    data object RateLimited : EnrollmentStartResult

    /** `400`: the server refused the request, e.g. an invalid name. */
    data class Refused(val error: String) : EnrollmentStartResult

    /** The store has no enrollment endpoints (older server, or authentication not configured). */
    data object Unavailable : EnrollmentStartResult
}

sealed interface EnrollmentPollResult {
    data object Pending : EnrollmentPollResult
    data object SlowDown : EnrollmentPollResult
    data object Expired : EnrollmentPollResult
    data object Denied : EnrollmentPollResult

    /** Unknown or already redeemed device code, or a malformed request: start over. */
    data object InvalidGrant : EnrollmentPollResult

    /** Approved: [credential] is the `device-token.json` text, delivered only once. */
    class Issued(val credential: String) : EnrollmentPollResult {
        // Keep the secret out of logs and crash reports.
        override fun toString(): String = "Issued(credential=<redacted>)"
    }
}

/** Administrator side of enrollment, with the signed-in OIDC session. */
interface DeviceEnrollmentAdminApi {
    /** `GET /api/me`: whether the signed-in principal is an administrator. */
    suspend fun isAdmin(): Result<Boolean>

    /** The pending request of [userCode]; fails with an `AdminApiException` (404, 409, 429, …). */
    suspend fun lookup(userCode: String): Result<PendingEnrollment>
    suspend fun approve(userCode: String, approval: EnrollmentApproval): Result<ApprovedEnrollment>
    suspend fun deny(userCode: String, blockSource: Boolean): Result<Unit>
}

/** A pending enrollment as the server saw it; times are the server's UTC strings. */
data class PendingEnrollment(
    val userCode: String,
    val requestedName: String,
    val model: String?,
    val androidSdk: Int?,
    val clientVersion: String?,
    val sourceIp: String,
    val createdAt: String,
    val ageSeconds: Long,
    val expiresInSeconds: Long,
)

sealed interface EnrollmentApproval {
    /** A new device token, named [name] (the requested name when null), joining [groupIds] (`all` when null). */
    data class NewDevice(val name: String?, val groupIds: List<Long>?) : EnrollmentApproval

    /** Rekeys the existing token [deviceTokenId]: it keeps its subject, grants and groups. */
    data class Replace(val deviceTokenId: String, val name: String?) : EnrollmentApproval
}

data class ApprovedEnrollment(val deviceToken: DeviceTokenSummary, val rekeyed: Boolean)

/** User codes: 8 characters from an alphabet without vowels or look-alikes, shown as `WDJB-MJHT`. */
object UserCode {
    const val ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"
    private const val LENGTH = 8

    /** The canonical `XXXX-XXXX` form of [input] (any case, spaces and dashes), or null. */
    fun normalize(input: String): String? {
        val compact = input.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        if (compact.length != LENGTH || compact.any { it !in ALPHABET }) return null
        return compact.substring(0, 4) + "-" + compact.substring(4)
    }
}
