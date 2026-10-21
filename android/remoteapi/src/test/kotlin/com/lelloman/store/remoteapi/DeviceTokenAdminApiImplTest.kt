package com.lelloman.store.remoteapi

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.AppGrant
import com.lelloman.store.domain.api.AppGroupSummary
import com.lelloman.store.domain.api.DeviceTokenSummary
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

class DeviceTokenAdminApiImplTest {

    private val baseUrl = "https://store.example.com"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun createApi(mockEngine: MockEngine) = DeviceTokenAdminApiImpl(
        HttpClient(mockEngine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
    ) { "$baseUrl/" }

    @Test
    fun `create posts only the name and returns the secret with the store it was created on`() = runTest {
        val api = createApi(MockEngine { request ->
            assertThat(request.method.value).isEqualTo("POST")
            assertThat(request.url.toString()).isEqualTo("$baseUrl/api/admin/device-tokens")
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertThat(body.keys).containsExactly("name")
            assertThat(body.getValue("name").jsonPrimitive.content).isEqualTo("Mi Box")
            respond(
                content = """{"id":"t1","name":"Mi Box","created_by":"admin","created_at":"2026-10-21 10:00:00","expires_at":null,"last_used_at":null,"revoked_at":null,"subject":"device-token:t1","groups":[],"secret":"lellostore_dev_abc"}""",
                status = HttpStatusCode.Created,
                headers = jsonHeaders,
            )
        })

        val created = api.create("Mi Box").getOrThrow()

        assertThat(created.token).isEqualTo(DeviceTokenSummary("t1", "Mi Box", "2026-10-21 10:00:00", null))
        assertThat(created.secret).isEqualTo("lellostore_dev_abc")
        assertThat(created.serverUrl).isEqualTo(baseUrl)
        assertThat(created.toString()).doesNotContain("lellostore_dev_abc")
    }

    @Test
    fun `failures carry the HTTP status`() = runTest {
        val api = createApi(MockEngine { respond("", HttpStatusCode.Forbidden) })

        val error = api.create("Mi Box").exceptionOrNull()

        assertThat(error).isInstanceOf(AdminApiException::class.java)
        assertThat((error as AdminApiException).status).isEqualTo(403)
    }

    @Test
    fun `list maps tokens and revoke deletes by id`() = runTest {
        val requests = mutableListOf<String>()
        val api = createApi(MockEngine { request ->
            requests += "${request.method.value} ${request.url}"
            if (request.method.value == "GET") {
                respond(
                    content = """[{"id":"t2","name":"TV","created_by":"admin","created_at":"2026-10-21 10:00:00","revoked_at":"2026-10-21 11:00:00","subject":"device-token:t2","groups":[{"id":3,"name":"family"},{"id":1,"name":"all"}]}]""",
                    headers = jsonHeaders,
                )
            } else {
                respond("""{"id":"t2"}""", headers = jsonHeaders)
            }
        })

        assertThat(api.list().getOrThrow()).containsExactly(
            DeviceTokenSummary("t2", "TV", "2026-10-21 10:00:00", "2026-10-21 11:00:00", groupIds = listOf(3, 1)),
        )
        api.revoke("t2").getOrThrow()
        assertThat(api.revoke("../apps").isFailure).isTrue()

        assertThat(requests).containsExactly(
            "GET $baseUrl/api/admin/device-tokens",
            "DELETE $baseUrl/api/admin/device-tokens/t2",
        ).inOrder()
    }

    @Test
    fun `create sends the groups the new token joins`() = runTest {
        val api = createApi(MockEngine { request ->
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertThat(body.getValue("group_ids").jsonArray.map { it.jsonPrimitive.content }).containsExactly("3", "7").inOrder()
            respond(
                content = """{"id":"t1","name":"Mi Box","created_by":"admin","created_at":"2026-10-21 10:00:00","subject":"device-token:t1","groups":[{"id":3,"name":"a"},{"id":7,"name":"b"}],"secret":"lellostore_dev_abc"}""",
                status = HttpStatusCode.Created,
                headers = jsonHeaders,
            )
        })

        val created = api.create("Mi Box", listOf(3, 7, 3)).getOrThrow()

        assertThat(created.token.groupIds).containsExactly(3L, 7L).inOrder()
    }

    @Test
    fun `groups and direct grants of a token principal`() = runTest {
        val requests = mutableListOf<String>()
        val api = createApi(MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            when {
                request.url.encodedPath.endsWith("/app-groups") -> respond(
                    content = """{"groups":[{"id":1,"name":"all","system_kind":"all","created_at":"x","updated_at":"x","grants":[],"user_subjects":[]},{"id":4,"name":"family","system_kind":null,"created_at":"x","updated_at":"x","grants":[],"user_subjects":[]}]}""",
                    headers = jsonHeaders,
                )
                request.url.encodedPath.endsWith("missing/access") -> respond("", HttpStatusCode.NotFound)
                request.url.encodedPath.endsWith("/access") -> respond(
                    content = """{"user":{"subject":"device-token:t1"},"direct_grants":[{"package_name":"com.example.player","access_level":"beta"}],"groups":[],"effective_access":[]}""",
                    headers = jsonHeaders,
                )
                else -> {
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    assertThat(body.getValue("access_level").jsonPrimitive.content).isEqualTo("stable")
                    respond("", HttpStatusCode.NoContent)
                }
            }
        })

        assertThat(api.listGroups().getOrThrow()).containsExactly(
            AppGroupSummary(1, "all", "all"),
            AppGroupSummary(4, "family", null),
        ).inOrder()
        assertThat(api.directGrants("t1").getOrThrow()).containsExactly(AppGrant("com.example.player", "beta"))
        assertThat(api.directGrants("missing").getOrThrow()).isEmpty()
        api.setDirectGrant("t2", AppGrant("com.example.news", "stable")).getOrThrow()
        assertThat(api.setDirectGrant("t2", AppGrant("../x", "stable")).isFailure).isTrue()
        assertThat(api.directGrants("../x").isFailure).isTrue()

        assertThat(requests.map { it.replace("%3A", ":") }).containsExactly(
            "GET /api/admin/app-groups",
            "GET /api/admin/users/device-token:t1/access",
            "GET /api/admin/users/device-token:missing/access",
            "PUT /api/admin/users/device-token:t2/apps/com.example.news",
        ).inOrder()
    }
}
