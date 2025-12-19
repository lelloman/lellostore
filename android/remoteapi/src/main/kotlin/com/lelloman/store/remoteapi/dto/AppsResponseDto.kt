package com.lelloman.store.remoteapi.dto

import kotlinx.serialization.Serializable

@Serializable
data class AppsResponseDto(
    val apps: List<AppDto>,
)
