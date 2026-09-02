package com.lelloman.store.remoteapi.dto

import com.lelloman.store.domain.model.AppDetail
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppDetailDto(
    @SerialName("package_name")
    val packageName: String,
    val name: String,
    val description: String?,
    @SerialName("icon_url")
    val iconUrl: String,
    val versions: List<AppVersionDto>,
    @SerialName("access_level")
    val accessLevel: String = "stable",
)

fun AppDetailDto.toDomain(): AppDetail = AppDetail(
    packageName = packageName,
    name = name,
    description = description,
    iconUrl = iconUrl,
    versions = versions.map { it.toDomain() },
    accessLevel = accessLevel.toAccessLevel(),
)
