package com.lelloman.store.installation

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class InstallationChannelModule {
    @Binds
    @IntoSet
    abstract fun bindLegacyAdbChannel(impl: LegacyAdbInstallationChannel): InstallationChannel

    @Binds
    @IntoSet
    abstract fun bindWirelessTlsAdbChannel(
        impl: WirelessTlsAdbInstallationChannel,
    ): InstallationChannel

    @Binds
    @IntoSet
    abstract fun bindPackageInstallerChannel(impl: PackageInstallerChannel): InstallationChannel
}
