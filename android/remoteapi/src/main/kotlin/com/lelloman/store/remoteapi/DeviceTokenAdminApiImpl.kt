package com.lelloman.store.remoteapi

import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.AppGrant
import com.lelloman.store.domain.api.AppGroupSummary
import com.lelloman.store.domain.api.CreatedDeviceToken
import com.lelloman.store.domain.api.DeviceTokenAdminApi
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.remoteapi.dto.AccessLevelRequestDto
import com.lelloman.store.remoteapi.dto.AppGroupsResponseDto
import com.lelloman.store.remoteapi.dto.CreateDeviceTokenRequestDto
import com.lelloman.store.remoteapi.dto.CreatedDeviceTokenDto
import com.lelloman.store.remoteapi.dto.DeviceTokenDto
import com.lelloman.store.remoteapi.dto.UserAccessDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

internal class DeviceTokenAdminApiImpl(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
) : DeviceTokenAdminApi {

    override suspend fun create(name: String, groupIds: List<Long>): Result<CreatedDeviceToken> = runCatching {
        // Resolve once so the returned server URL is the one the token was created on.
        val baseUrl = baseUrlProvider().trimEnd('/')
        val response = httpClient.post("$baseUrl$PATH") {
            contentType(ContentType.Application.Json)
            setBody(CreateDeviceTokenRequestDto(name, groupIds.distinct()))
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
        requireTokenId(id)
        val response = httpClient.delete("${baseUrlProvider().trimEnd('/')}$PATH/$id")
        response.requireSuccess()
    }

    override suspend fun listGroups(): Result<List<AppGroupSummary>> = runCatching {
        val response = httpClient.get("${baseUrlProvider().trimEnd('/')}$GROUPS_PATH")
        response.requireSuccess()
        response.body<AppGroupsResponseDto>().groups.map { it.toDomain() }
    }

    override suspend fun directGrants(tokenId: String): Result<List<AppGrant>> = runCatching {
        requireTokenId(tokenId)
        val response = httpClient.get("${baseUrlProvider().trimEnd('/')}$USERS_PATH/$SUBJECT_PREFIX$tokenId/access")
        // A principal the server never registered has no grants.
        if (response.status == HttpStatusCode.NotFound) return@runCatching emptyList()
        response.requireSuccess()
        response.body<UserAccessDto>().directGrants.map { it.toDomain() }
    }

    override suspend fun setDirectGrant(tokenId: String, grant: AppGrant): Result<Unit> = runCatching {
        requireTokenId(tokenId)
        require(grant.packageName.matches(Regex("[a-zA-Z0-9._]{1,255}"))) { "Invalid package name" }
        val response = httpClient.put(
            "${baseUrlProvider().trimEnd('/')}$USERS_PATH/$SUBJECT_PREFIX$tokenId/apps/${grant.packageName}",
        ) {
            contentType(ContentType.Application.Json)
            setBody(AccessLevelRequestDto(grant.accessLevel))
        }
        response.requireSuccess()
    }

    private fun requireTokenId(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid device token ID" }
    }

    private fun HttpResponse.requireSuccess() {
        if (!status.isSuccess()) throw AdminApiException(status.value)
    }

    private companion object {
        const val PATH = "/api/admin/device-tokens"
        const val GROUPS_PATH = "/api/admin/app-groups"
        const val USERS_PATH = "/api/admin/users"
        const val SUBJECT_PREFIX = "device-token:"
    }
}
