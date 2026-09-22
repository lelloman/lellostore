package com.lelloman.store.domain.model

/** Metadata for the exact APK delivered to this acquisition, including personalization. */
data class ApkAcquisition(
    val id: String,
    val packageName: String,
    val versionCode: Int,
    val size: Long,
    val sha256: String,
)

enum class AcquisitionPurpose(val wireValue: String) { INSTALL("install"), UPDATE("update"), REPAIR("repair") }
