package com.lelloman.store.localdata.di

import javax.inject.Qualifier

/**
 * Qualifier for the default server URL.
 * This must be provided by the :app module.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultServerUrl

/**
 * Qualifier for the application-scoped CoroutineScope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
