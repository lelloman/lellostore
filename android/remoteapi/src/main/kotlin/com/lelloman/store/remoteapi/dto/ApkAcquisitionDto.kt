package com.lelloman.store.remoteapi.dto

import com.lelloman.store.domain.model.ApkAcquisition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ApkAcquisitionDto(
    val id: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("version_code") val versionCode: Int,
    val size: Long,
    val sha256: String,
) {
    fun toDomain() = ApkAcquisition(id, packageName, versionCode, size, sha256)
}

@Serializable
internal data class AcquisitionRequestDto(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val purpose: String,
)
