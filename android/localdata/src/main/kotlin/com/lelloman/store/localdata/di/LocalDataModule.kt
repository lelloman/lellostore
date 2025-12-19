package com.lelloman.store.localdata.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.localdata.config.ConfigStoreImpl
import com.lelloman.store.localdata.db.LellostoreDatabase
import com.lelloman.store.localdata.db.dao.AppVersionsDao
import com.lelloman.store.localdata.db.dao.AppsDao
import com.lelloman.store.localdata.db.dao.InstalledAppsDao
import com.lelloman.store.localdata.prefs.UserPreferencesStoreImpl
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
        ).build()
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
}
