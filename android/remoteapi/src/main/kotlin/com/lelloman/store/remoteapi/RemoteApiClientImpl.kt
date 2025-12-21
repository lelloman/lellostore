package com.lelloman.store.remoteapi

import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.model.App
import com.lelloman.store.domain.model.AppDetail
import com.lelloman.store.remoteapi.dto.AppDetailDto
import com.lelloman.store.remoteapi.dto.AppsResponseDto
import com.lelloman.store.remoteapi.dto.toDomain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.InputStream

internal class RemoteApiClientImpl(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : RemoteApiClient {

    override suspend fun getApps(): Result<List<App>> = runCatching {
        val response = httpClient.get("$baseUrl/api/apps")
        if (!response.status.isSuccess()) {
            throw ApiException("Failed to get apps: ${response.status}")
        }
        val appsResponse: AppsResponseDto = response.body()
        appsResponse.apps.map { it.toDomain().withAbsoluteUrls(baseUrl) }
    }

    override suspend fun getApp(packageName: String): Result<AppDetail> = runCatching {
        val response = httpClient.get("$baseUrl/api/apps/$packageName")
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
}

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
