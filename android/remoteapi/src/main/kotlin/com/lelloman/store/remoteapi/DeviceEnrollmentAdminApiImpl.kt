package com.lelloman.store.remoteapi

import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.enrollment.ApprovedEnrollment
import com.lelloman.store.domain.enrollment.DeviceEnrollmentAdminApi
import com.lelloman.store.domain.enrollment.EnrollmentApproval
import com.lelloman.store.domain.enrollment.PendingEnrollment
import com.lelloman.store.domain.enrollment.UserCode
import com.lelloman.store.remoteapi.dto.ApproveEnrollmentRequestDto
import com.lelloman.store.remoteapi.dto.ApprovedEnrollmentDto
import com.lelloman.store.remoteapi.dto.CurrentUserDto
import com.lelloman.store.remoteapi.dto.PendingEnrollmentDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** The administrator side of enrollment, through the authenticated store client. */
internal class DeviceEnrollmentAdminApiImpl(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
) : DeviceEnrollmentAdminApi {

    override suspend fun isAdmin(): Result<Boolean> = runCatching {
        val response = httpClient.get("${baseUrlProvider().trimEnd('/')}/api/me")
        response.requireSuccess()
        response.body<CurrentUserDto>().isAdmin
    }

    override suspend fun lookup(userCode: String): Result<PendingEnrollment> = runCatching {
        val response = httpClient.get(enrollmentUrl(userCode))
        response.requireSuccess()
        response.body<PendingEnrollmentDto>().toDomain()
    }

    override suspend fun approve(userCode: String, approval: EnrollmentApproval): Result<ApprovedEnrollment> = runCatching {
        val body = when (approval) {
            is EnrollmentApproval.NewDevice -> ApproveEnrollmentRequestDto(approval.name, approval.groupIds?.distinct())
            is EnrollmentApproval.Replace -> {
                require(approval.deviceTokenId.matches(TOKEN_ID)) { "Invalid device token ID" }
                ApproveEnrollmentRequestDto(approval.name, replaceDeviceTokenId = approval.deviceTokenId)
            }
        }
        val response = httpClient.post("${enrollmentUrl(userCode)}/approve") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        response.requireSuccess()
        val approved = response.body<ApprovedEnrollmentDto>()
        ApprovedEnrollment(approved.deviceToken.toDomain(), approved.rekeyed)
    }

    override suspend fun deny(userCode: String, blockSource: Boolean): Result<Unit> = runCatching {
        val response = httpClient.post("${enrollmentUrl(userCode)}/deny") {
            url.parameters.append("block_source", blockSource.toString())
        }
        response.requireSuccess()
    }

    /** The code is validated before it becomes part of a URL. */
    private fun enrollmentUrl(userCode: String): String {
        val code = requireNotNull(UserCode.normalize(userCode)) { "Invalid user code" }
        return "${baseUrlProvider().trimEnd('/')}$PATH/$code"
    }

    private fun HttpResponse.requireSuccess() {
        if (!status.isSuccess()) throw AdminApiException(status.value)
    }

    private companion object {
        const val PATH = "/api/admin/device-enrollments"
        val TOKEN_ID = Regex("[a-zA-Z0-9_-]{1,128}")
    }
}
