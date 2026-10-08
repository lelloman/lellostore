package com.lelloman.store.remoteapi

import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.SessionExpiredHandler
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.config.StoreSession
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

/** Credentials and authentication failures belong to the selected store only. */
class StoreAuthInterceptor(
    private val auth: AuthStore,
    private val config: ConfigStore,
    private val session: StoreSession,
    private val expired: SessionExpiredHandler,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val epoch = session.epoch
        val selected = runBlocking { config.readServerUrl() }
        val origin = runCatching { URI(selected) }.getOrNull()
        val target = chain.request().url
        val matches = origin != null && target.scheme == origin.scheme &&
            target.host.equals(origin.host, ignoreCase = true) &&
            target.port == (origin.port.takeIf { it >= 0 } ?: 443)
        val token = if (matches) runBlocking {
            auth.getAccessToken().takeIf { config.readServerUrl() == selected && session.epoch == epoch }
        } else null
        val request = chain.request().newBuilder().removeHeader("Authorization").apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        val response = chain.proceed(request)
        if (response.code == 401 && token != null && session.epoch == epoch &&
            runBlocking { config.readServerUrl() } == selected) expired.onSessionExpired()
        return response
    }
}
