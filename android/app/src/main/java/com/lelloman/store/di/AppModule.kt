package com.lelloman.store.di

import com.lelloman.store.domain.auth.OidcConfig
import com.lelloman.store.domain.download.DownloadManager
import com.lelloman.store.download.DownloadManagerImpl
import com.lelloman.store.interactor.AppDetailInteractorImpl
import com.lelloman.store.interactor.CatalogInteractorImpl
import com.lelloman.store.interactor.LoginInteractorImpl
import com.lelloman.store.localdata.auth.AuthStoreImpl
import com.lelloman.store.localdata.di.DefaultServerUrl
import com.lelloman.store.localdata.di.OidcConfigQualifier
import com.lelloman.store.ui.screen.catalog.CatalogViewModel
import com.lelloman.store.ui.screen.detail.AppDetailViewModel
import com.lelloman.store.ui.screen.login.AuthIntentProvider
import com.lelloman.store.ui.screen.login.LoginViewModel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val DEFAULT_SERVER_URL = "https://store.lelloman.com"

    // OIDC Configuration
    private const val OIDC_ISSUER_URL = "https://auth.lelloman.com"
    private const val OIDC_CLIENT_ID = "22cd4a2d-a771-41e3-b76e-3f83ff8e9bbf"
    private const val OIDC_REDIRECT_URI = "com.lelloman.store:/oauth2redirect"

    @Provides
    @DefaultServerUrl
    fun provideDefaultServerUrl(): String = DEFAULT_SERVER_URL

    @Provides
    @Singleton
    @OidcConfigQualifier
    fun provideOidcConfig(): OidcConfig = OidcConfig(
        issuerUrl = OIDC_ISSUER_URL,
        clientId = OIDC_CLIENT_ID,
        redirectUri = OIDC_REDIRECT_URI,
    )

    @Provides
    @Singleton
    fun provideAuthIntentProvider(authStoreImpl: AuthStoreImpl): AuthIntentProvider {
        return object : AuthIntentProvider {
            override fun createAuthIntent() = authStoreImpl.createAuthIntent()
        }
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
    @Singleton
    abstract fun bindDownloadManager(impl: DownloadManagerImpl): DownloadManager
}
