package com.lelloman.store.e2e

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class MockServerRule : TestWatcher() {

    lateinit var mockWebServer: MockWebServer
        private set

    val baseUrl: String
        get() = mockWebServer.url("/").toString().dropLast(1) // Remove trailing slash

    override fun starting(description: Description) {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    override fun finished(description: Description) {
        mockWebServer.shutdown()
    }

    fun enqueueAppsResponse(apps: List<MockApp>) {
        val json = buildAppsJson(apps)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json)
        )
    }

    fun enqueueAppDetailResponse(app: MockAppDetail) {
        val json = buildAppDetailJson(app)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json)
        )
    }

    fun enqueueApkDownload(apkBytes: ByteArray) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody(Buffer().write(apkBytes))
        )
    }

    fun enqueueError(code: Int, message: String) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setBody("""{"error": "error", "message": "$message"}""")
        )
    }

    private fun buildAppsJson(apps: List<MockApp>): String {
        val appsJson = apps.joinToString(",") { app ->
            """
            {
                "package_name": "${app.packageName}",
                "name": "${app.name}",
                "description": ${app.description?.let { "\"$it\"" } ?: "null"},
                "icon_url": "${app.iconUrl}",
                "latest_version": {
                    "version_code": ${app.versionCode},
                    "version_name": "${app.versionName}",
                    "size": ${app.size},
                    "sha256": "${app.sha256}",
                    "min_sdk": ${app.minSdk},
                    "uploaded_at": "${app.uploadedAt}"
                }
            }
            """.trimIndent()
        }
        return """{"apps": [$appsJson]}"""
    }

    private fun buildAppDetailJson(app: MockAppDetail): String {
        val versionsJson = app.versions.joinToString(",") { version ->
            """
            {
                "version_code": ${version.versionCode},
                "version_name": "${version.versionName}",
                "size": ${version.size},
                "sha256": "${version.sha256}",
                "min_sdk": ${version.minSdk},
                "uploaded_at": "${version.uploadedAt}"
            }
            """.trimIndent()
        }
        return """
        {
            "package_name": "${app.packageName}",
            "name": "${app.name}",
            "description": ${app.description?.let { "\"$it\"" } ?: "null"},
            "icon_url": "${app.iconUrl}",
            "versions": [$versionsJson]
        }
        """.trimIndent()
    }
}

data class MockApp(
    val packageName: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String = "https://example.com/icon.png",
    val versionCode: Int = 1,
    val versionName: String = "1.0.0",
    val size: Long = 1000,
    val sha256: String = "abc123",
    val minSdk: Int = 24,
    val uploadedAt: String = "2024-01-01T00:00:00Z",
)

data class MockAppDetail(
    val packageName: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String = "https://example.com/icon.png",
    val versions: List<MockAppVersion> = emptyList(),
)

data class MockAppVersion(
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val sha256: String,
    val minSdk: Int = 24,
    val uploadedAt: String = "2024-01-01T00:00:00Z",
)
