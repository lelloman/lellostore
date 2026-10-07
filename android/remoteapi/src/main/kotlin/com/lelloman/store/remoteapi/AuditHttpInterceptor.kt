package com.lelloman.store.remoteapi

import com.lelloman.store.logger.AuditLog
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/** Metadata only: never persist headers, query strings, bodies or arbitrary URL paths. */
class AuditHttpInterceptor(private val audit: AuditLog) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fields = mapOf("request_id" to UUID.randomUUID().toString(),
            "method" to request.method, "host" to request.url.host,
            "route" to safeRoute(request.url.pathSegments))
        val start = System.nanoTime()
        audit.record("http.started", fields)
        try {
            val response = chain.proceed(request)
            audit.record("http.response", fields + mapOf("status" to response.code,
                "headers_ms" to (System.nanoTime() - start) / 1_000_000,
                "content_length" to response.body?.contentLength()))
            return response
        } catch (error: Exception) {
            audit.record("http.failed", fields + mapOf("error_type" to error.javaClass.simpleName,
                "duration_ms" to (System.nanoTime() - start) / 1_000_000))
            throw error
        }
    }

    companion object {
        private val routeWords = setOf("api", "v1", "push", "devices", "subscriptions", "stream",
            "apps", "versions", "download", "icon", "events", "updates", "channels", "auth", "token")
        internal fun safeRoute(segments: List<String>): String =
            segments.joinToString("/", prefix = "/") { if (it in routeWords) it else "_" }
    }
}
