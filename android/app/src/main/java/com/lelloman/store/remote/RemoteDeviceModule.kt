package com.lelloman.store.remote

import com.lelloman.store.domain.remote.RemoteDeviceOperations
import com.lelloman.store.domain.remote.RemoteDeviceSession
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteDeviceModule {
    @Binds abstract fun session(manager: RemoteDeviceManager): RemoteDeviceSession
    @Binds abstract fun operations(manager: RemoteDeviceManager): RemoteDeviceOperations
}
