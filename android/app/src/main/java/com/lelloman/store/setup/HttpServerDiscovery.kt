package com.lelloman.store.setup

import com.lelloman.store.domain.auth.OidcConfig
import com.lelloman.store.domain.config.ServerAddress
import com.lelloman.store.domain.config.ServerDiscovery
import com.lelloman.store.domain.config.ServerMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpServerDiscovery @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context?,
) : ServerDiscovery {
    // Never reuse the authenticated catalog client for discovery.
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun discover(serverUrl: String): ServerMetadata = withContext(Dispatchers.IO) {
        val origin = ServerAddress.normalize(serverUrl)
        client.newCall(Request.Builder().url("$origin/api/server-config").build()).execute().use { response ->
            readResponse(origin, response)
        }
    }

    internal fun readResponse(origin: String, response: okhttp3.Response): ServerMetadata {
        if (response.code == 404 || response.code == 405 ||
            (response.isSuccessful && response.body?.contentType()?.subtype == "html")) {
            return legacyOrUnsupported(origin)
        }
        check(response.code != 503) { "This store is not configured yet. Ask its operator to finish setup." }
        check(response.isSuccessful) { "Could not read this store's configuration (${response.code}). Check the address." }
        val source = requireNotNull(response.body).source()
        source.request(65537)
        check(source.buffer.size <= 65536) { "Server configuration is too large" }
        val bytes = source.buffer.readByteArray()
        val body = bytes.toString(Charsets.UTF_8).trim()
        if (body.startsWith("<!DOCTYPE html", ignoreCase = true) || body.startsWith("<html", ignoreCase = true)) {
            return legacyOrUnsupported(origin)
        }
        val root = try { JSONObject(body) } catch (_: org.json.JSONException) {
            error("This server returned invalid setup information. Ask its operator to check /api/server-config.")
        }
        return parse(root)
    }

    internal fun legacyOrUnsupported(origin: String): ServerMetadata =
        LegacyDeploymentMigration.metadata(context, origin)
            ?: error("This server does not support app setup yet. Ask its operator to upgrade the backend.")

    override fun canMigrateLegacySession(serverUrl: String, oidc: OidcConfig): Boolean =
        LegacyDeploymentMigration.canMigrateSession(context, serverUrl, oidc)

    internal fun parse(root: JSONObject): ServerMetadata {
        check(root.getInt("schema_version") == 1) { "Unsupported server setup version. Update the app." }
        val auth = root.getJSONObject("auth")
        check(auth.getString("method") == "oidc") { "Unsupported sign-in method. Update the app." }
        val issuer = auth.getString("issuer_url")
        val uri = URI(issuer)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Invalid sign-in provider address" }
        val name = root.getString("name")
        require(name.isNotBlank() && name.length <= 120 && name.none(Char::isISOControl)) { "Invalid store name" }
        val clientId = auth.getJSONObject("clients").getString("android")
        require(clientId.isNotBlank() && clientId.length <= 256 && clientId.none(Char::isWhitespace)) { "Invalid Android client configuration" }
        val scopeArray = auth.getJSONArray("scopes")
        val scopes = (0 until scopeArray.length()).map(scopeArray::getString)
        require("openid" in scopes && scopes.size <= 32 && scopes.all { scope ->
            scope.length in 1..128 && scope.all { it.code == 0x21 || it.code in 0x23..0x5b || it.code in 0x5d..0x7e }
        }) { "Invalid sign-in scopes" }
        val capabilities = root.getJSONObject("capabilities")
        return ServerMetadata(name, OidcConfig(issuer, clientId, "com.lelloman.store:/oauth2redirect", scopes),
            capabilities.getBoolean("push"), capabilities.getBoolean("paravoid"))
    }
}
