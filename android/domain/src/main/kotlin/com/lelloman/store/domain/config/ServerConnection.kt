package com.lelloman.store.domain.config

import com.lelloman.store.domain.auth.OidcConfig
import java.net.URI

data class ServerMetadata(
    val name: String,
    val oidc: OidcConfig,
    val push: Boolean,
    val paravoid: Boolean,
)

interface ServerDiscovery {
    suspend fun discover(serverUrl: String): ServerMetadata
    /** Only an explicit deployment migration may bind a pre-discovery session. */
    fun canMigrateLegacySession(serverUrl: String, oidc: OidcConfig): Boolean = false
}

object ServerAddress {
    fun normalize(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && (uri.port == -1 || uri.port in 1..65535)) {
            "Enter an HTTPS server address without a path, query, or credentials"
        }
        val port = if (uri.port == 443) -1 else uri.port
        return URI("https", null, uri.host.lowercase(), port, null, null, null).toASCIIString()
    }
}

/** A server cannot change while catalog/download/install work still owns it. */
class StoreSession {
    private val monitor = Any()
    private var operations = 0
    private var switching = false
    private var generation = 0L
    val epoch: Long get() = synchronized(monitor) { generation }

    suspend fun <T> use(expectedEpoch: Long = epoch, block: suspend () -> T): T {
        synchronized(monitor) {
            check(!switching) { "Server setup is in progress. Try again when it finishes." }
            check(generation == expectedEpoch) { "Server changed. Start this operation again." }
            operations++
        }
        try { return block() } finally { synchronized(monitor) { operations-- } }
    }

    suspend fun <T> change(block: suspend () -> T): T {
        synchronized(monitor) {
            check(!switching && operations == 0) { "Wait for current store operations to finish, or cancel downloads, before changing servers." }
            switching = true
            generation++
        }
        try { return block() } finally { synchronized(monitor) { switching = false } }
    }
}
