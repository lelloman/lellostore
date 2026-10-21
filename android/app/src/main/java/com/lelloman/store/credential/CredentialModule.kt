package com.lelloman.store.credential

import com.lelloman.store.domain.credential.CredentialIssuer
import com.lelloman.store.domain.credential.CredentialIssuerRegistry
import com.lelloman.store.ui.screen.credential.IssueCredentialViewModel
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/** Credential types other apps can request; add an `@IntoSet` binding per type. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialModule {

    @Multibinds
    abstract fun credentialIssuers(): Set<CredentialIssuer>

    @Binds
    @IntoSet
    abstract fun bindDeviceTokenIssuer(issuer: DeviceTokenCredentialIssuer): CredentialIssuer

    @Binds
    abstract fun bindIssueCredentialInteractor(impl: IssueCredentialInteractorImpl): IssueCredentialViewModel.Interactor

    companion object {
        @Provides
        fun provideCredentialIssuerRegistry(issuers: Set<@JvmSuppressWildcards CredentialIssuer>) =
            CredentialIssuerRegistry(issuers)
    }
}
