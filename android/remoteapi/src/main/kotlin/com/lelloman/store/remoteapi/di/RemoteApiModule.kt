package com.lelloman.store.remoteapi.di

import android.util.Log
import com.lelloman.store.domain.api.DeviceTokenAdminApi
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.SessionExpiredHandler
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.enrollment.DeviceEnrollmentAdminApi
import com.lelloman.store.domain.enrollment.DeviceEnrollmentApi
import com.lelloman.store.remoteapi.DeviceEnrollmentAdminApiImpl
import com.lelloman.store.remoteapi.DeviceEnrollmentApiImpl
import com.lelloman.store.remoteapi.DeviceTokenAdminApiImpl
import com.lelloman.store.remoteapi.RemoteApiClientImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RemoteApiModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authStore: AuthStore,
        sessionExpiredHandler: SessionExpiredHandler,
        auditLog: com.lelloman.store.logger.AuditLog,
        configStore: ConfigStore,
        storeSession: com.lelloman.store.domain.config.StoreSession,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(com.lelloman.store.remoteapi.AuditHttpInterceptor(auditLog))
        .addInterceptor(com.lelloman.store.remoteapi.StoreAuthInterceptor(
            authStore, configStore, storeSession, sessionExpiredHandler,
        ))
        .build()

    @Provides
    @Singleton
    fun provideHttpClient(
        json: Json,
        okHttpClient: OkHttpClient,
    ): HttpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d("KtorHttp", message)
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideRemoteApiClient(
        httpClient: HttpClient,
        configStore: ConfigStore,
    ): RemoteApiClient = RemoteApiClientImpl(
        httpClient = httpClient,
        baseUrlProvider = { configStore.serverUrl.value },
        deviceSdk = android.os.Build.VERSION.SDK_INT,
    )

    @Provides
    @Singleton
    fun provideDeviceTokenAdminApi(
        httpClient: HttpClient,
        configStore: ConfigStore,
    ): DeviceTokenAdminApi = DeviceTokenAdminApiImpl(
        httpClient = httpClient,
        baseUrlProvider = { configStore.serverUrl.value },
    )

    @Provides
    @Singleton
    fun provideDeviceEnrollmentAdminApi(
        httpClient: HttpClient,
        configStore: ConfigStore,
    ): DeviceEnrollmentAdminApi = DeviceEnrollmentAdminApiImpl(
        httpClient = httpClient,
        baseUrlProvider = { configStore.serverUrl.value },
    )

    /**
     * Enrollment runs before the device has a credential and must never send one: its client has
     * no auth or session-expiry interceptor, no request logging (the body carries the device code)
     * and follows no redirects.
     */
    @Provides
    @Singleton
    fun provideDeviceEnrollmentApi(
        json: Json,
        auditLog: com.lelloman.store.logger.AuditLog,
    ): DeviceEnrollmentApi = DeviceEnrollmentApiImpl(
        HttpClient(OkHttp) {
            engine {
                preconfigured = OkHttpClient.Builder()
                    .addInterceptor(com.lelloman.store.remoteapi.AuditHttpInterceptor(auditLog))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .build()
            }
            install(ContentNegotiation) {
                json(json)
            }
        },
    )
}
