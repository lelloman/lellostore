package com.lelloman.store.remoteapi

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.domain.enrollment.EnrollmentApproval
import com.lelloman.store.domain.enrollment.PendingEnrollment
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class DeviceEnrollmentAdminApiImplTest {

    private val baseUrl = "https://store.example.com"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val token = """{"id":"t1","name":"Mi Box","created_at":"2026-10-21 10:00:00","revoked_at":null,"groups":[{"id":1,"name":"all"}]}"""

    private fun createApi(engine: MockEngine) = DeviceEnrollmentAdminApiImpl(
        HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
    ) { "$baseUrl/" }

    @Test
    fun `isAdmin reads the current user`() = runTest {
        val api = createApi(MockEngine { request ->
            assertThat(request.url.toString()).isEqualTo("$baseUrl/api/me")
            respond("""{"subject":"u","email":"a@example.com","is_admin":true}""", headers = jsonHeaders)
        })

        assertThat(api.isAdmin().getOrThrow()).isTrue()
    }

    @Test
    fun `lookup normalizes the code and maps the pending request`() = runTest {
        val api = createApi(MockEngine { request ->
            assertThat(request.url.toString()).isEqualTo("$baseUrl/api/admin/device-enrollments/WDJB-MJHT")
            respond(
                """{"user_code":"WDJB-MJHT","requested_name":"Mi Box (living room)","model":"MIBOX4","android_sdk":28,
                "client_version":"1.0.0","source_ip":"198.51.100.7","created_at":"2026-10-21 10:00:00",
                "expires_at":"2026-10-21 10:10:00","age_s":75,"expires_in":525}""",
                headers = jsonHeaders,
            )
        })

        val pending = api.lookup("wdjb mjht").getOrThrow()

        assertThat(pending).isEqualTo(
            PendingEnrollment("WDJB-MJHT", "Mi Box (living room)", "MIBOX4", 28, "1.0.0", "198.51.100.7", "2026-10-21 10:00:00", 75, 525),
        )
    }

    @Test
    fun `an invalid code never reaches the server`() = runTest {
        val api = createApi(MockEngine { error("unexpected request") })

        val error = api.lookup("../../me").exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `lookup failures carry the status`() = runTest {
        val api = createApi(MockEngine { respond("""{"error":"conflict"}""", HttpStatusCode.Conflict, jsonHeaders) })

        assertThat((api.lookup("WDJB-MJHT").exceptionOrNull() as AdminApiException).status).isEqualTo(409)
    }

    @Test
    fun `approving a new device sends the name and groups`() = runTest {
        val api = createApi(MockEngine { request ->
            assertThat(request.url.toString()).isEqualTo("$baseUrl/api/admin/device-enrollments/WDJB-MJHT/approve")
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertThat(body.keys).containsExactly("name", "group_ids")
            assertThat(body.getValue("group_ids").jsonArray.map { it.jsonPrimitive.content }).containsExactly("1", "3")
            respond("""{"device_token":$token,"rekeyed":false}""", headers = jsonHeaders)
        })

        val approved = api.approve("WDJB-MJHT", EnrollmentApproval.NewDevice("Mi Box", listOf(1, 3, 3))).getOrThrow()

        assertThat(approved.deviceToken).isEqualTo(DeviceTokenSummary("t1", "Mi Box", "2026-10-21 10:00:00", null, listOf(1)))
        assertThat(approved.rekeyed).isFalse()
    }

    @Test
    fun `server defaults are kept when nothing is chosen`() = runTest {
        val api = createApi(MockEngine { request ->
            assertThat(request.body.toByteArray().decodeToString()).isEqualTo("{}")
            respond("""{"device_token":$token,"rekeyed":false}""", headers = jsonHeaders)
        })

        api.approve("WDJB-MJHT", EnrollmentApproval.NewDevice(null, null)).getOrThrow()
    }

    @Test
    fun `replacing a device rekeys the chosen token`() = runTest {
        val api = createApi(MockEngine { request ->
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertThat(body.keys).containsExactly("replace_device_token_id")
            assertThat(body.getValue("replace_device_token_id").jsonPrimitive.content).isEqualTo("t1")
            respond("""{"device_token":$token,"rekeyed":true}""", headers = jsonHeaders)
        })

        assertThat(api.approve("WDJB-MJHT", EnrollmentApproval.Replace("t1", null)).getOrThrow().rekeyed).isTrue()
    }

    @Test
    fun `deny passes the block choice`() = runTest {
        val requests = mutableListOf<String>()
        val api = createApi(MockEngine { request ->
            requests += "${request.method.value} ${request.url}"
            respond("", HttpStatusCode.NoContent)
        })

        api.deny("WDJB-MJHT", blockSource = false).getOrThrow()
        api.deny("WDJB-MJHT", blockSource = true).getOrThrow()

        assertThat(requests).containsExactly(
            "POST $baseUrl/api/admin/device-enrollments/WDJB-MJHT/deny?block_source=false",
            "POST $baseUrl/api/admin/device-enrollments/WDJB-MJHT/deny?block_source=true",
        ).inOrder()
    }
}
