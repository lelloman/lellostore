package com.lelloman.store.di

import com.lelloman.store.BuildConfig
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.download.DownloadManagerImpl
import com.lelloman.store.interactor.AppDetailInteractorImpl
import com.lelloman.store.interactor.CatalogInteractorImpl
import com.lelloman.store.interactor.LoginInteractorImpl
import com.lelloman.store.interactor.SettingsInteractorImpl
import com.lelloman.store.interactor.UpdatesInteractorImpl
import com.lelloman.store.localdata.auth.AuthStoreImpl
import com.lelloman.store.localdata.di.DefaultServerUrl
import com.lelloman.store.ui.screen.catalog.CatalogViewModel
import com.lelloman.store.ui.screen.detail.AppDetailViewModel
import com.lelloman.store.ui.screen.login.AuthIntentProvider
import com.lelloman.store.ui.screen.login.LoginViewModel
import com.lelloman.store.ui.screen.settings.SettingsViewModel
import com.lelloman.store.ui.screen.updates.UpdatesViewModel
import com.lelloman.store.updates.UpdateCheckerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @DefaultServerUrl
    fun provideDefaultServerUrl(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): String =
        com.lelloman.store.setup.LegacyDeploymentMigration.initialServer(context, BuildConfig.DEFAULT_SERVER_URL)

    @Provides
    @Singleton
    fun provideServerDiscovery(discovery: com.lelloman.store.setup.HttpServerDiscovery): com.lelloman.store.domain.config.ServerDiscovery = discovery

    @Provides
    @Singleton
    fun provideStoreSession() = com.lelloman.store.domain.config.StoreSession()

    @Provides
    @Singleton
    fun provideAuthIntentProvider(authStoreImpl: AuthStoreImpl): AuthIntentProvider {
        return object : AuthIntentProvider {
            override suspend fun createAuthIntent() = authStoreImpl.createAuthIntent()
        }
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    abstract fun bindLoginInteractor(impl: LoginInteractorImpl): LoginViewModel.Interactor

    @Binds
    abstract fun bindCatalogInteractor(impl: CatalogInteractorImpl): CatalogViewModel.Interactor

    @Binds
    abstract fun bindAppDetailInteractor(impl: AppDetailInteractorImpl): AppDetailViewModel.Interactor

    @Binds
    abstract fun bindUpdatesInteractor(impl: UpdatesInteractorImpl): UpdatesViewModel.Interactor

    @Binds
    abstract fun bindSettingsInteractor(impl: SettingsInteractorImpl): SettingsViewModel.Interactor

    @Binds
    @Singleton
    abstract fun bindDownloadManager(impl: DownloadManagerImpl): DownloadManager

    @Binds
    @Singleton
    abstract fun bindUpdateChecker(impl: UpdateCheckerImpl): UpdateChecker
}
