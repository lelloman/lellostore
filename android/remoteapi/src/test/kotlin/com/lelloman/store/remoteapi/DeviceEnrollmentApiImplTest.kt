package com.lelloman.store.remoteapi

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.enrollment.EnrollmentPollResult
import com.lelloman.store.domain.enrollment.EnrollmentRequest
import com.lelloman.store.domain.enrollment.EnrollmentStartResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class DeviceEnrollmentApiImplTest {

    private val baseUrl = "https://store.example.com"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(engine: MockEngine) = DeviceEnrollmentApiImpl(
        HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
    )

    private fun error(code: String) = """{"error":"$code","error_description":"…"}"""

    @Test
    fun `start sends the device details without credentials and maps the answer`() = runTest {
        val api = createApi(MockEngine { request ->
            assertThat(request.method.value).isEqualTo("POST")
            assertThat(request.url.toString()).isEqualTo("$baseUrl/api/device-enrollments")
            assertThat(request.headers[HttpHeaders.Authorization]).isNull()
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertThat(body.keys).containsExactly("name", "model", "android_sdk", "client_version")
            assertThat(body.getValue("name").jsonPrimitive.content).isEqualTo("Mi Box")
            assertThat(body.getValue("android_sdk").jsonPrimitive.content).isEqualTo("28")
            respond(
                """{"device_code":"lellostore_enr_${"a".repeat(64)}","user_code":"WDJB-MJHT",
                "verification_uri":"$baseUrl/admin/device-tokens/approve",
                "verification_uri_complete":"$baseUrl/admin/device-tokens/approve?code=WDJB-MJHT",
                "verification_payload":"{}","expires_in":600,"interval":5}""",
                HttpStatusCode.Created,
                jsonHeaders,
            )
        })

        val result = api.start("$baseUrl/", EnrollmentRequest("Mi Box", "MIBOX4", 28, "1.0.0"))

        val started = (result as EnrollmentStartResult.Started).enrollment
        assertThat(started.userCode).isEqualTo("WDJB-MJHT")
        assertThat(started.verificationUriComplete).isEqualTo("$baseUrl/admin/device-tokens/approve?code=WDJB-MJHT")
        assertThat(started.expiresInSeconds).isEqualTo(600)
        assertThat(started.intervalSeconds).isEqualTo(5)
        assertThat(started.deviceCode).startsWith("lellostore_enr_")
        assertThat(started.toString()).doesNotContain("lellostore_enr_")
    }

    @Test
    fun `optional details are left out`() = runTest {
        val api = createApi(MockEngine { request ->
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertThat(body.keys).containsExactly("name")
            respond(error("slow_down"), HttpStatusCode.TooManyRequests, jsonHeaders)
        })

        assertThat(api.start(baseUrl, EnrollmentRequest("Mi Box"))).isEqualTo(EnrollmentStartResult.RateLimited)
    }

    @Test
    fun `start refusals are mapped`() = runTest {
        val answers = mapOf(
            HttpStatusCode.Forbidden to EnrollmentStartResult.Blocked,
            HttpStatusCode.BadRequest to EnrollmentStartResult.Refused("invalid_request"),
            HttpStatusCode.NotFound to EnrollmentStartResult.Unavailable,
        )
        for ((status, expected) in answers) {
            val api = createApi(MockEngine {
                respond(error(if (status == HttpStatusCode.Forbidden) "access_denied" else "invalid_request"), status, jsonHeaders)
            })
            assertThat(api.start(baseUrl, EnrollmentRequest("Mi Box"))).isEqualTo(expected)
        }
    }

    @Test
    fun `server errors throw so the caller retries`() = runTest {
        val api = createApi(MockEngine { respond("", HttpStatusCode.BadGateway) })

        assertThat(runCatching { api.start(baseUrl, EnrollmentRequest("Mi Box")) }.exceptionOrNull())
            .isInstanceOf(ApiException::class.java)
        assertThat(runCatching { api.poll(baseUrl, "lellostore_enr_x") }.exceptionOrNull())
            .isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `poll sends the device code and maps the RFC 8628 errors`() = runTest {
        val answers = mapOf(
            "authorization_pending" to EnrollmentPollResult.Pending,
            "slow_down" to EnrollmentPollResult.SlowDown,
            "expired_token" to EnrollmentPollResult.Expired,
            "access_denied" to EnrollmentPollResult.Denied,
            "invalid_grant" to EnrollmentPollResult.InvalidGrant,
        )
        for ((code, expected) in answers) {
            val api = createApi(MockEngine { request ->
                assertThat(request.url.toString()).isEqualTo("$baseUrl/api/device-enrollments/poll")
                val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                assertThat(body.getValue("device_code").jsonPrimitive.content).isEqualTo("lellostore_enr_x")
                respond(error(code), HttpStatusCode.BadRequest, jsonHeaders)
            })
            assertThat(api.poll(baseUrl, "lellostore_enr_x")).isEqualTo(expected)
        }
    }

    @Test
    fun `an approved poll returns the device token file once, redacted in logs`() = runTest {
        val file = """{"version":1,"server_url":"$baseUrl","name":"Mi Box","token":"lellostore_dev_${"b".repeat(64)}"}"""
        val api = createApi(MockEngine { respond(file, HttpStatusCode.OK, jsonHeaders) })

        val result = api.poll(baseUrl, "lellostore_enr_x")

        assertThat((result as EnrollmentPollResult.Issued).credential).isEqualTo(file)
        assertThat(result.toString()).doesNotContain("lellostore_dev_")
    }
}
