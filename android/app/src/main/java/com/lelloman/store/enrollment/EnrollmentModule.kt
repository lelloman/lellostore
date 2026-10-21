package com.lelloman.store.enrollment

import com.lelloman.store.ui.screen.enrollment.EnrollmentViewModel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Device-code enrollment of this device and approval of other devices (credential provider phases 2 and 3). */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnrollmentModule {

    @Binds
    abstract fun bindEnrollmentInteractor(impl: EnrollmentInteractorImpl): EnrollmentViewModel.Interactor
}
