package com.lelloman.store.logger.di

import com.lelloman.store.logger.AndroidLogger
import com.lelloman.store.logger.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LoggerModule {

    @Provides
    @Singleton
    fun provideLogger(): Logger = AndroidLogger()
}
