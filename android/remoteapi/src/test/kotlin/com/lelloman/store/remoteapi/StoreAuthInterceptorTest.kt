package com.lelloman.store.remoteapi

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.SessionExpiredHandler
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.config.StoreSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test

class StoreAuthInterceptorTest {
    private val auth = mockk<AuthStore>()
    private val config = mockk<ConfigStore>()
    private val expired = mockk<SessionExpiredHandler>(relaxed = true)
    private val session = StoreSession()
    private val interceptor = StoreAuthInterceptor(auth, config, session, expired)

    private fun request(url: String, status: Int = 200, duringResponse: () -> Unit = {}): Request {
        val chain = mockk<Interceptor.Chain>()
        val original = Request.Builder().url(url).build()
        every { chain.request() } returns original
        var sent: Request? = null
        every { chain.proceed(any()) } answers {
            sent = firstArg()
            duringResponse()
            Response.Builder().request(sent!!).code(status).message("test")
                .protocol(Protocol.HTTP_1_1).build()
        }
        interceptor.intercept(chain)
        return requireNotNull(sent)
    }

    @Test fun `only the selected origin receives a token`() {
        coEvery { config.readServerUrl() } returns "https://a.example"
        coEvery { auth.getAccessToken() } returns "secret-a"
        assertThat(request("https://a.example/api/apps").header("Authorization")).isEqualTo("Bearer secret-a")
        listOf("https://b.example/icon", "https://a.example:8443/icon", "http://a.example/icon").forEach {
            assertThat(request(it, 401).header("Authorization")).isNull()
        }
        coVerify(exactly = 1) { auth.getAccessToken() }
        verify(exactly = 0) { expired.onSessionExpired() }
    }

    @Test fun `switch while refreshing discards the old token`() {
        coEvery { config.readServerUrl() } returns "https://a.example"
        coEvery { auth.getAccessToken() } coAnswers {
            session.change { }
            "old-token"
        }
        assertThat(request("https://a.example/api/apps").header("Authorization")).isNull()
    }

    @Test fun `a late 401 cannot log out a new session`() {
        coEvery { config.readServerUrl() } returns "https://a.example"
        coEvery { auth.getAccessToken() } returns "secret-a"
        request("https://a.example/api/apps", 401) { runBlocking { session.change { } } }
        verify(exactly = 0) { expired.onSessionExpired() }
        request("https://a.example/api/apps", 401)
        verify(exactly = 1) { expired.onSessionExpired() }
    }
}
