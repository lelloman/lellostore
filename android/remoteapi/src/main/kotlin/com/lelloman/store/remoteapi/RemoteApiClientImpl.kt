package com.lelloman.store.remoteapi

import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.domain.model.ApkAcquisition
import com.lelloman.store.remoteapi.dto.ApkAcquisitionDto
import com.lelloman.store.remoteapi.dto.AcquisitionRequestDto
import com.lelloman.store.remoteapi.dto.AppDetailDto
import com.lelloman.store.remoteapi.dto.AppsResponseDto
import com.lelloman.store.remoteapi.dto.toDomain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.InputStream

internal class RemoteApiClientImpl(
    private val httpClient: HttpClient,
    private val deviceSdk: Int? = null,
    private val baseUrlProvider: () -> String,
) : RemoteApiClient {

    private val baseUrl: String
        get() = baseUrlProvider().trimEnd('/')

    override suspend fun getApps(): Result<List<App>> = runCatching {
        val response = httpClient.get("$baseUrl/api/apps") { deviceSdk?.let { url.parameters.append("sdk", it.toString()) } }
        if (!response.status.isSuccess()) {
            throw ApiException("Failed to get apps: ${response.status}")
        }
        val appsResponse: AppsResponseDto = response.body()
        appsResponse.apps
            .filter { it.latestVersion != null }
            .map { it.toDomain().withAbsoluteUrls(baseUrl) }
    }

    override suspend fun getApp(packageName: String): Result<AppDetail> = runCatching {
        val response = httpClient.get("$baseUrl/api/apps/$packageName") { deviceSdk?.let { url.parameters.append("sdk", it.toString()) } }
        if (!response.status.isSuccess()) {
            throw ApiException("Failed to get app $packageName: ${response.status}")
        }
        val appDetail: AppDetailDto = response.body()
        appDetail.toDomain().withAbsoluteUrls(baseUrl)
    }

    private fun App.withAbsoluteUrls(baseUrl: String): App = copy(
        iconUrl = resolveUrl(baseUrl, iconUrl),
    )

    private fun AppDetail.withAbsoluteUrls(baseUrl: String): AppDetail = copy(
        iconUrl = resolveUrl(baseUrl, iconUrl),
    )

    private fun resolveUrl(baseUrl: String, url: String): String =
        if (url.startsWith("/")) "$baseUrl$url" else url

    override suspend fun downloadApk(packageName: String, versionCode: Int): Result<InputStream> =
        runCatching {
            val response = httpClient.get("$baseUrl/api/apps/$packageName/versions/$versionCode/apk")
            if (!response.status.isSuccess()) {
                throw ApiException("Failed to download APK: ${response.status}")
            }
            response.bodyAsChannel().toInputStream()
        }

    override suspend fun acquireApk(packageName: String, versionCode: Int, idempotencyKey: String, purpose: com.lelloman.store.domain.model.AcquisitionPurpose): Result<ApkAcquisition> = runCatching {
        val response = httpClient.post("$baseUrl/api/apps/$packageName/acquisitions") {
            contentType(ContentType.Application.Json)
            setBody(AcquisitionRequestDto(versionCode, idempotencyKey, purpose.wireValue))
        }
        if (!response.status.isSuccess()) throw ApiException("Failed to acquire APK: ${response.status}")
        response.body<ApkAcquisitionDto>().toDomain()
    }

    override suspend fun downloadAcquisition(acquisitionId: String): Result<InputStream> = runCatching {
        require(acquisitionId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid acquisition ID" }
        val response = httpClient.get("$baseUrl/api/acquisitions/$acquisitionId/apk")
        if (!response.status.isSuccess()) throw ApiException("Failed to download acquired APK: ${response.status}")
        response.bodyAsChannel().toInputStream()
    }
}

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
