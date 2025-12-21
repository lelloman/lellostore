package com.lelloman.store.remoteapi

import com.lelloman.store.domain.auth.SessionExpiredHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Before
import org.junit.Test

class SessionExpiredInterceptorTest {

    private lateinit var sessionExpiredHandler: SessionExpiredHandler
    private lateinit var interceptor: SessionExpiredInterceptor
    private lateinit var chain: Interceptor.Chain
    private lateinit var request: Request

    @Before
    fun setup() {
        sessionExpiredHandler = mockk(relaxed = true)
        interceptor = SessionExpiredInterceptor(sessionExpiredHandler)
        chain = mockk()
        request = Request.Builder().url("https://example.com/api").build()
        every { chain.request() } returns request
    }

    private fun mockResponse(code: Int): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Test")
            .build()
    }

    @Test
    fun `401 response triggers onSessionExpired`() {
        every { chain.proceed(request) } returns mockResponse(401)

        interceptor.intercept(chain)

        verify(exactly = 1) { sessionExpiredHandler.onSessionExpired() }
    }

    @Test
    fun `403 response does not trigger onSessionExpired`() {
        every { chain.proceed(request) } returns mockResponse(403)

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionExpiredHandler.onSessionExpired() }
    }

    @Test
    fun `200 response does not trigger onSessionExpired`() {
        every { chain.proceed(request) } returns mockResponse(200)

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionExpiredHandler.onSessionExpired() }
    }

    @Test
    fun `500 response does not trigger onSessionExpired`() {
        every { chain.proceed(request) } returns mockResponse(500)

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionExpiredHandler.onSessionExpired() }
    }

    @Test
    fun `interceptor returns the response unchanged`() {
        val response = mockResponse(200)
        every { chain.proceed(request) } returns response

        val result = interceptor.intercept(chain)

        assert(result === response)
    }
}
