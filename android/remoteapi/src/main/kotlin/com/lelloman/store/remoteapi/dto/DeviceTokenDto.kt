package com.lelloman.store.remoteapi.dto

import com.lelloman.store.domain.api.DeviceTokenSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DeviceTokenDto(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
) {
    fun toDomain() = DeviceTokenSummary(id, name, createdAt, revokedAt)
}

@Serializable
internal class CreatedDeviceTokenDto(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
    val secret: String,
) {
    fun toSummary() = DeviceTokenSummary(id, name, createdAt, revokedAt)

    // Keep the secret out of logs and crash reports.
    override fun toString(): String = "CreatedDeviceTokenDto(id=$id, name=$name)"
}

@Serializable
internal data class CreateDeviceTokenRequestDto(val name: String)
