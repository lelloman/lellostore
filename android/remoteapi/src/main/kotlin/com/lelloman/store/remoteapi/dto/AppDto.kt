package com.lelloman.store.remoteapi.dto

import com.lelloman.store.domain.model.App
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppDto(
    @SerialName("package_name")
    val packageName: String,
    val name: String,
    val description: String?,
    @SerialName("icon_url")
    val iconUrl: String,
    @SerialName("latest_version")
    val latestVersion: AppVersionDto,
)

fun AppDto.toDomain(): App = App(
    packageName = packageName,
    name = name,
    description = description,
    iconUrl = iconUrl,
    latestVersion = latestVersion.toDomain(),
)
