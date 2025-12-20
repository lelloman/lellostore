package com.lelloman.store.remoteapi.di

import android.util.Log
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
    fun provideHttpClient(json: Json, authStore: AuthStore): HttpClient = HttpClient(OkHttp) {
        engine {
            addInterceptor { chain ->
                val token = runBlocking { authStore.getAccessToken() }
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
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
        baseUrl = configStore.serverUrl.value,
    )
}
