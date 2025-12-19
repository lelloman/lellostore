package com.lelloman.store.remoteapi

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class RemoteApiClientImplTest {

    private val baseUrl = "https://test.example.com"

    private fun createClient(mockEngine: MockEngine): RemoteApiClientImpl {
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return RemoteApiClientImpl(httpClient, baseUrl)
    }

    @Test
    fun `getApps returns list of apps on success`() = runTest {
        val responseJson = """
            {
                "apps": [
                    {
                        "package_name": "com.example.app1",
                        "name": "App One",
                        "description": "First app",
                        "icon_url": "https://example.com/icon1.png",
                        "latest_version": {
                            "version_code": 1,
                            "version_name": "1.0.0",
                            "size": 1024000,
                            "sha256": "abc123",
                            "min_sdk": 24,
                            "uploaded_at": 1700000000000
                        }
                    },
                    {
                        "package_name": "com.example.app2",
                        "name": "App Two",
                        "description": null,
                        "icon_url": "https://example.com/icon2.png",
                        "latest_version": {
                            "version_code": 5,
                            "version_name": "2.0.0",
                            "size": 2048000,
                            "sha256": "def456",
                            "min_sdk": 26,
                            "uploaded_at": 1700100000000
                        }
                    }
                ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertThat(request.url.toString()).isEqualTo("$baseUrl/api/apps")
            respond(
                content = responseJson,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val client = createClient(mockEngine)
        val result = client.getApps()

        assertThat(result.isSuccess).isTrue()
        val apps = result.getOrThrow()
        assertThat(apps).hasSize(2)

        assertThat(apps[0].packageName).isEqualTo("com.example.app1")
        assertThat(apps[0].name).isEqualTo("App One")
        assertThat(apps[0].description).isEqualTo("First app")
        assertThat(apps[0].latestVersion.versionCode).isEqualTo(1)
        assertThat(apps[0].latestVersion.versionName).isEqualTo("1.0.0")

        assertThat(apps[1].packageName).isEqualTo("com.example.app2")
        assertThat(apps[1].description).isNull()
        assertThat(apps[1].latestVersion.versionCode).isEqualTo(5)
    }

    @Test
    fun `getApps returns failure on error response`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound
            )
        }

        val client = createClient(mockEngine)
        val result = client.getApps()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `getApp returns app detail on success`() = runTest {
        val packageName = "com.example.app"
        val responseJson = """
            {
                "package_name": "$packageName",
                "name": "Example App",
                "description": "An example application",
                "icon_url": "https://example.com/icon.png",
                "versions": [
                    {
                        "version_code": 3,
                        "version_name": "1.2.0",
                        "size": 3072000,
                        "sha256": "hash123",
                        "min_sdk": 24,
                        "uploaded_at": 1700200000000
                    },
                    {
                        "version_code": 2,
                        "version_name": "1.1.0",
                        "size": 2560000,
                        "sha256": "hash456",
                        "min_sdk": 24,
                        "uploaded_at": 1700100000000
                    }
                ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertThat(request.url.toString()).isEqualTo("$baseUrl/api/apps/$packageName")
            respond(
                content = responseJson,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val client = createClient(mockEngine)
        val result = client.getApp(packageName)

        assertThat(result.isSuccess).isTrue()
        val appDetail = result.getOrThrow()
        assertThat(appDetail.packageName).isEqualTo(packageName)
        assertThat(appDetail.name).isEqualTo("Example App")
        assertThat(appDetail.versions).hasSize(2)
        assertThat(appDetail.versions[0].versionCode).isEqualTo(3)
        assertThat(appDetail.versions[1].versionCode).isEqualTo(2)
    }

    @Test
    fun `getApp returns failure on error response`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "Internal Server Error",
                status = HttpStatusCode.InternalServerError
            )
        }

        val client = createClient(mockEngine)
        val result = client.getApp("com.example.app")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `downloadApk returns input stream on success`() = runTest {
        val packageName = "com.example.app"
        val versionCode = 5
        val apkBytes = "fake apk content".toByteArray()

        val mockEngine = MockEngine { request ->
            assertThat(request.url.toString())
                .isEqualTo("$baseUrl/api/apps/$packageName/versions/$versionCode/apk")
            respond(
                content = apkBytes,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.OctetStream.toString()
                )
            )
        }

        val client = createClient(mockEngine)
        val result = client.downloadApk(packageName, versionCode)

        assertThat(result.isSuccess).isTrue()
        val inputStream = result.getOrThrow()
        val content = inputStream.readBytes().decodeToString()
        assertThat(content).isEqualTo("fake apk content")
    }

    @Test
    fun `downloadApk returns failure on error response`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = "Forbidden",
                status = HttpStatusCode.Forbidden
            )
        }

        val client = createClient(mockEngine)
        val result = client.downloadApk("com.example.app", 1)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(ApiException::class.java)
    }

    @Test
    fun `getApps handles network exception`() = runTest {
        val mockEngine = MockEngine {
            throw java.io.IOException("Network error")
        }

        val client = createClient(mockEngine)
        val result = client.getApps()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
    }
}
