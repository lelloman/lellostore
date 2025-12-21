package com.lelloman.store.remoteapi.dto

import com.lelloman.store.domain.model.AppVersion
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppVersionDto(
    @SerialName("version_code")
    val versionCode: Int,
    @SerialName("version_name")
    val versionName: String,
    val size: Long,
    val sha256: String? = null,
    @SerialName("min_sdk")
    val minSdk: Int,
    @SerialName("uploaded_at")
    val uploadedAt: String,
)

fun AppVersionDto.toDomain(): AppVersion = AppVersion(
    versionCode = versionCode,
    versionName = versionName,
    size = size,
    sha256 = sha256,
    minSdk = minSdk,
    uploadedAt = LocalDateTime.parse(uploadedAt.replace(" ", "T")).toInstant(TimeZone.UTC),
)
