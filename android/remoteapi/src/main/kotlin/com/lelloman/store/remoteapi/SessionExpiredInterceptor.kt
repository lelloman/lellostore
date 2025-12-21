package com.lelloman.store.remoteapi

import com.lelloman.store.domain.auth.SessionExpiredHandler
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that detects 401 responses and triggers session expiration.
 *
 * This interceptor runs AFTER the token has been attached and refreshed (if needed).
 * A 401 at this point means the session is truly expired and cannot be recovered.
 *
 * Note: 403 (Forbidden) is NOT handled here because it indicates the user is authenticated
 * but lacks permission for the specific resource (e.g., non-admin accessing admin endpoints).
 */
class SessionExpiredInterceptor(
    private val sessionExpiredHandler: SessionExpiredHandler,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (response.code == 401) {
            sessionExpiredHandler.onSessionExpired()
        }

        return response
    }
}
