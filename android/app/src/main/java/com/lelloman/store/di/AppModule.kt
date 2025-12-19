package com.lelloman.store.di

import com.lelloman.store.localdata.di.DefaultServerUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val DEFAULT_SERVER_URL = "https://store.lelloman.com"

    @Provides
    @DefaultServerUrl
    fun provideDefaultServerUrl(): String = DEFAULT_SERVER_URL
}
