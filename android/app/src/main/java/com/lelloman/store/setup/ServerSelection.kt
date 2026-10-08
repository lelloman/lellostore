package com.lelloman.store.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.config.ServerAddress
import com.lelloman.store.domain.config.ServerDiscovery
import com.lelloman.store.domain.config.StoreSession
import com.lelloman.store.domain.updates.UpdateChecker
import com.lelloman.store.localdata.db.LellostoreDatabase
import com.lelloman.store.notification.NotificationBrokerRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerSelection @Inject constructor(
    private val config: ConfigStore,
    private val discovery: ServerDiscovery,
    private val auth: AuthStore,
    private val broker: NotificationBrokerRuntime,
    private val session: StoreSession,
    private val database: LellostoreDatabase,
    private val preferences: DataStore<Preferences>,
    private val updates: UpdateChecker,
    private val remote: com.lelloman.store.domain.remote.RemoteDeviceOperations,
    @ApplicationContext private val context: Context,
) {
    suspend fun select(value: String) {
        val url = ServerAddress.normalize(value)
        // Validate before disturbing a working connection.
        discovery.discover(url)
        session.change {
            if (config.readServerUrl() == url) {
                config.setServerUrl(url)
                return@change
            }
            auth.logout()
            remote.cancel()
            broker.signedOut()
            database.withTransaction {
                database.appVersionsDao().deleteAll()
                database.appsDao().deleteAll()
            }
            preferences.edit { settings ->
                settings.asMap().keys.filter { it.name.startsWith("app.") }.forEach { key ->
                    settings.remove(key)
                }
            }
            updates.clear()
            context.cacheDir.resolve("apks").deleteRecursively()
            check(config.setServerUrl(url) == ConfigStore.SetServerUrlResult.Success)
        }
    }
}
