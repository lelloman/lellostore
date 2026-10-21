package com.lelloman.store.remoteapi.dto

import com.lelloman.store.domain.enrollment.PendingEnrollment
import com.lelloman.store.domain.enrollment.StartedEnrollment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional fields are left out when null (defaults are not encoded). */
@Serializable
internal data class StartEnrollmentRequestDto(
    val name: String,
    val model: String? = null,
    @SerialName("android_sdk") val androidSdk: Int? = null,
    @SerialName("client_version") val clientVersion: String? = null,
)

@Serializable
internal class StartedEnrollmentDto(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Long = 5,
) {
    fun toDomain() = StartedEnrollment(deviceCode, userCode, verificationUri, verificationUriComplete, expiresIn, interval)

    // Keep the device code out of logs and crash reports.
    override fun toString(): String = "StartedEnrollmentDto(userCode=$userCode)"
}

@Serializable
internal class PollRequestDto(@SerialName("device_code") val deviceCode: String) {
    override fun toString(): String = "PollRequestDto(deviceCode=<redacted>)"
}

@Serializable
internal data class OAuthErrorDto(val error: String? = null)

@Serializable
internal data class PendingEnrollmentDto(
    @SerialName("user_code") val userCode: String,
    @SerialName("requested_name") val requestedName: String,
    val model: String? = null,
    @SerialName("android_sdk") val androidSdk: Int? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    @SerialName("source_ip") val sourceIp: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("age_s") val ageSeconds: Long = 0,
    @SerialName("expires_in") val expiresIn: Long = 0,
) {
    fun toDomain() = PendingEnrollment(
        userCode, requestedName, model, androidSdk, clientVersion, sourceIp, createdAt, ageSeconds, expiresIn,
    )
}

/** Nulls are left out, so the server applies its defaults (requested name, `all` group). */
@Serializable
internal data class ApproveEnrollmentRequestDto(
    val name: String? = null,
    @SerialName("group_ids") val groupIds: List<Long>? = null,
    @SerialName("replace_device_token_id") val replaceDeviceTokenId: String? = null,
)

@Serializable
internal data class ApprovedEnrollmentDto(
    @SerialName("device_token") val deviceToken: DeviceTokenDto,
    val rekeyed: Boolean = false,
)

@Serializable
internal data class CurrentUserDto(@SerialName("is_admin") val isAdmin: Boolean = false)
