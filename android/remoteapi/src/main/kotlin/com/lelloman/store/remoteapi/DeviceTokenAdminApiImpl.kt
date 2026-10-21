package com.lelloman.store.remoteapi

import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.CreatedDeviceToken
import com.lelloman.store.domain.api.DeviceTokenAdminApi
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.remoteapi.dto.CreateDeviceTokenRequestDto
import com.lelloman.store.remoteapi.dto.CreatedDeviceTokenDto
import com.lelloman.store.remoteapi.dto.DeviceTokenDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

internal class DeviceTokenAdminApiImpl(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
) : DeviceTokenAdminApi {

    override suspend fun create(name: String): Result<CreatedDeviceToken> = runCatching {
        // Resolve once so the returned server URL is the one the token was created on.
        val baseUrl = baseUrlProvider().trimEnd('/')
        val response = httpClient.post("$baseUrl$PATH") {
            contentType(ContentType.Application.Json)
            setBody(CreateDeviceTokenRequestDto(name))
        }
        response.requireSuccess()
        val created = response.body<CreatedDeviceTokenDto>()
        CreatedDeviceToken(created.toSummary(), created.secret, baseUrl)
    }

    override suspend fun list(): Result<List<DeviceTokenSummary>> = runCatching {
        val response = httpClient.get("${baseUrlProvider().trimEnd('/')}$PATH")
        response.requireSuccess()
        response.body<List<DeviceTokenDto>>().map { it.toDomain() }
    }

    override suspend fun revoke(id: String): Result<Unit> = runCatching {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid device token ID" }
        val response = httpClient.delete("${baseUrlProvider().trimEnd('/')}$PATH/$id")
        response.requireSuccess()
    }

    private fun HttpResponse.requireSuccess() {
        if (!status.isSuccess()) throw AdminApiException(status.value)
    }

    private companion object {
        const val PATH = "/api/admin/device-tokens"
    }
}
