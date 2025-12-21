package com.lelloman.store.localdata.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.lelloman.store.domain.apps.AppsRepository
import com.lelloman.store.domain.apps.InstalledAppsRepository
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.auth.OidcConfig
import com.lelloman.store.domain.auth.SessionExpiredHandler
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.localdata.apps.AppsRepositoryImpl
import com.lelloman.store.localdata.apps.InstalledAppsRepositoryImpl
import com.lelloman.store.localdata.auth.AuthStoreImpl
import com.lelloman.store.localdata.auth.SessionExpiredHandlerImpl
import com.lelloman.store.localdata.config.ConfigStoreImpl
import com.lelloman.store.localdata.db.LellostoreDatabase
import com.lelloman.store.localdata.db.dao.AppVersionsDao
import com.lelloman.store.localdata.db.dao.AppsDao
import com.lelloman.store.localdata.db.dao.InstalledAppsDao
import com.lelloman.store.localdata.prefs.UserPreferencesStoreImpl
import com.lelloman.store.logger.Logger
import com.lelloman.store.domain.api.RemoteApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalDataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LellostoreDatabase {
        return Room.databaseBuilder(
            context,
            LellostoreDatabase::class.java,
            "lellostore.db"
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    @Provides
    fun provideAppsDao(db: LellostoreDatabase): AppsDao = db.appsDao()

    @Provides
    fun provideAppVersionsDao(db: LellostoreDatabase): AppVersionsDao = db.appVersionsDao()

    @Provides
    fun provideInstalledAppsDao(db: LellostoreDatabase): InstalledAppsDao = db.installedAppsDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("settings")
        }
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @Provides
    @Singleton
    fun provideConfigStore(
        dataStore: DataStore<Preferences>,
        @DefaultServerUrl defaultServerUrl: String,
        @ApplicationScope scope: CoroutineScope,
    ): ConfigStore = ConfigStoreImpl(dataStore, defaultServerUrl, scope)

    @Provides
    @Singleton
    fun provideUserPreferencesStore(
        dataStore: DataStore<Preferences>,
        @ApplicationScope scope: CoroutineScope,
    ): UserPreferencesStore = UserPreferencesStoreImpl(dataStore, scope)

    @Provides
    @Singleton
    fun provideAuthStoreImpl(
        @ApplicationContext context: Context,
        @OidcConfigQualifier oidcConfig: OidcConfig,
        @ApplicationScope scope: CoroutineScope,
        logger: Logger,
    ): AuthStoreImpl = AuthStoreImpl(context, oidcConfig, scope, logger)

    @Provides
    @Singleton
    fun provideAuthStore(authStoreImpl: AuthStoreImpl): AuthStore = authStoreImpl

    @Provides
    @Singleton
    fun provideSessionExpiredHandler(
        authStore: AuthStore,
        logger: Logger,
    ): SessionExpiredHandler = SessionExpiredHandlerImpl(authStore, logger)

    @Provides
    @Singleton
    fun provideAppsRepository(
        appsDao: AppsDao,
        appVersionsDao: AppVersionsDao,
        remoteApiClient: RemoteApiClient,
    ): AppsRepository = AppsRepositoryImpl(appsDao, appVersionsDao, remoteApiClient)

    @Provides
    @Singleton
    fun provideInstalledAppsRepository(
        @ApplicationContext context: Context,
        installedAppsDao: InstalledAppsDao,
        appsDao: AppsDao,
    ): InstalledAppsRepository = InstalledAppsRepositoryImpl(context, installedAppsDao, appsDao)
}
