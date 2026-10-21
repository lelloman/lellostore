package com.lelloman.store.remoteapi.dto

import com.lelloman.store.domain.api.AppGrant
import com.lelloman.store.domain.api.AppGroupSummary
import com.lelloman.store.domain.api.DeviceTokenSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DeviceTokenDto(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
    val groups: List<GroupRefDto> = emptyList(),
) {
    fun toDomain() = DeviceTokenSummary(id, name, createdAt, revokedAt, groups.map { it.id })
}

@Serializable
internal data class GroupRefDto(val id: Long, val name: String)

@Serializable
internal class CreatedDeviceTokenDto(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
    val groups: List<GroupRefDto> = emptyList(),
    val secret: String,
) {
    fun toSummary() = DeviceTokenSummary(id, name, createdAt, revokedAt, groups.map { it.id })

    // Keep the secret out of logs and crash reports.
    override fun toString(): String = "CreatedDeviceTokenDto(id=$id, name=$name)"
}

/** `group_ids` is left out when empty (defaults are not encoded), as the server defaults it. */
@Serializable
internal data class CreateDeviceTokenRequestDto(
    val name: String,
    @SerialName("group_ids") val groupIds: List<Long> = emptyList(),
)

@Serializable
internal data class AppGroupsResponseDto(val groups: List<AppGroupDto>)

@Serializable
internal data class AppGroupDto(
    val id: Long,
    val name: String,
    @SerialName("system_kind") val systemKind: String? = null,
) {
    fun toDomain() = AppGroupSummary(id, name, systemKind)
}

@Serializable
internal data class UserAccessDto(@SerialName("direct_grants") val directGrants: List<GrantDto> = emptyList())

@Serializable
internal data class GrantDto(
    @SerialName("package_name") val packageName: String,
    @SerialName("access_level") val accessLevel: String,
) {
    fun toDomain() = AppGrant(packageName, accessLevel)
}

@Serializable
internal data class AccessLevelRequestDto(@SerialName("access_level") val accessLevel: String)
