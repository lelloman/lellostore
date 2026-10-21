package com.lelloman.store.remoteapi

import com.lelloman.store.domain.enrollment.DeviceEnrollmentApi
import com.lelloman.store.domain.enrollment.EnrollmentPollResult
import com.lelloman.store.domain.enrollment.EnrollmentRequest
import com.lelloman.store.domain.enrollment.EnrollmentStartResult
import com.lelloman.store.remoteapi.dto.OAuthErrorDto
import com.lelloman.store.remoteapi.dto.PollRequestDto
import com.lelloman.store.remoteapi.dto.StartEnrollmentRequestDto
import com.lelloman.store.remoteapi.dto.StartedEnrollmentDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

/**
 * The device side of enrollment. [httpClient] must not attach credentials: these routes need
 * none, and the device has none yet (see RemoteApiModule).
 */
internal class DeviceEnrollmentApiImpl(
    private val httpClient: HttpClient,
) : DeviceEnrollmentApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start(serverUrl: String, request: EnrollmentRequest): EnrollmentStartResult {
        val response = httpClient.post("${serverUrl.trimEnd('/')}$PATH") {
            contentType(ContentType.Application.Json)
            setBody(StartEnrollmentRequestDto(request.name, request.model, request.androidSdk, request.clientVersion))
        }
        return when (response.status.value) {
            201, 200 -> EnrollmentStartResult.Started(response.body<StartedEnrollmentDto>().toDomain())
            400 -> EnrollmentStartResult.Refused(response.oauthError() ?: "invalid_request")
            403 -> EnrollmentStartResult.Blocked
            429 -> EnrollmentStartResult.RateLimited
            // No such route: the server predates enrollment or has no authentication configured.
            404, 405 -> EnrollmentStartResult.Unavailable
            else -> throw ApiException("Enrollment failed: HTTP ${response.status.value}")
        }
    }

    override suspend fun poll(serverUrl: String, deviceCode: String): EnrollmentPollResult {
        val response = httpClient.post("${serverUrl.trimEnd('/')}$PATH/poll") {
            contentType(ContentType.Application.Json)
            setBody(PollRequestDto(deviceCode))
        }
        return when (response.status.value) {
            200 -> EnrollmentPollResult.Issued(response.bodyAsText())
            400, 403 -> when (response.oauthError()) {
                "authorization_pending" -> EnrollmentPollResult.Pending
                "slow_down" -> EnrollmentPollResult.SlowDown
                "expired_token" -> EnrollmentPollResult.Expired
                "access_denied" -> EnrollmentPollResult.Denied
                "invalid_grant", "invalid_request" -> EnrollmentPollResult.InvalidGrant
                else -> throw ApiException("Unexpected enrollment poll answer")
            }
            429 -> EnrollmentPollResult.SlowDown
            else -> throw ApiException("Enrollment poll failed: HTTP ${response.status.value}")
        }
    }

    private suspend fun HttpResponse.oauthError(): String? =
        runCatching { json.decodeFromString<OAuthErrorDto>(bodyAsText()).error }.getOrNull()

    private companion object {
        const val PATH = "/api/device-enrollments"
    }
}
