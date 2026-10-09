package com.lelloman.store.setup

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ServerSetupTest {
    private fun response(code: Int, contentType: String, body: String) = okhttp3.Response.Builder()
        .request(okhttp3.Request.Builder().url("https://store.lelloman.com/api/server-config").build())
        .protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("test")
        .body(body.toResponseBody(contentType.toMediaType())).build()

    @Test fun `existing deployment survives old backend returning HTML instead of discovery`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("auth_prefs", 0).edit().putString("old-session", "present").commit()
        LegacyDeploymentMigration.initialServer(context, "")
        val discovery = HttpServerDiscovery(context)
        response(200, "text/html", "<!DOCTYPE html><html>old website</html>").use { reply ->
            val restored = discovery.readResponse("https://store.lelloman.com", reply)
            assertThat(restored.oidc.issuerUrl).isEqualTo("https://auth.lelloman.com")
            assertThat(discovery.canMigrateLegacySession("https://store.lelloman.com", restored.oidc)).isTrue()
            assertThat(discovery.canMigrateLegacySession("https://other.example", restored.oidc)).isFalse()
            assertThat(discovery.canMigrateLegacySession("https://store.lelloman.com",
                restored.oidc.copy(clientId = "another-client"))).isFalse()
            assertThat(discovery.canMigrateLegacySession("https://store.lelloman.com",
                restored.oidc.copy(issuerUrl = "https://other.example"))).isFalse()
        }
        response(503, "application/json", "{}").use { reply ->
            assertThat(runCatching { discovery.readResponse("https://store.lelloman.com", reply) }.isFailure).isTrue()
        }
    }

    @Test fun `fresh installs and unrelated servers never receive personal fallback configuration`() {
        val context = RuntimeEnvironment.getApplication()
        LegacyDeploymentMigration.initialServer(context, "")
        val discovery = HttpServerDiscovery(context)
        for (origin in listOf("https://store.lelloman.com", "https://other.example")) {
            response(200, "text/html", "<!DOCTYPE html><html>website</html>").use { reply ->
                val error = runCatching { discovery.readResponse(origin, reply) }.exceptionOrNull()
                assertThat(error?.message).contains("upgrade the backend")
            }
        }
    }

    private fun metadata() = JSONObject("""{
        "schema_version":1,"name":"Independent Store",
        "auth":{"method":"oidc","issuer_url":"https://identity.example/realm",
        "clients":{"android":"independent-client"},"scopes":["openid","email"]},
        "capabilities":{"push":false,"paravoid":false}}
    """)

    @Test fun `discovery uses the selected stores public registration`() {
        val result = HttpServerDiscovery(null).parse(metadata())
        assertThat(result.name).isEqualTo("Independent Store")
        assertThat(result.oidc.issuerUrl).isEqualTo("https://identity.example/realm")
        assertThat(result.oidc.clientId).isEqualTo("independent-client")
        assertThat(result.push).isFalse()
    }

    @Test fun `unsupported or insecure metadata is rejected`() {
        val discovery = HttpServerDiscovery(null)
        assertThat(runCatching { discovery.parse(metadata().put("schema_version", 2)) }.isFailure).isTrue()
        val insecure = metadata().apply { getJSONObject("auth").put("issuer_url", "http://identity.example") }
        assertThat(runCatching { discovery.parse(insecure) }.isFailure).isTrue()
        val method = metadata().apply { getJSONObject("auth").put("method", "password") }
        assertThat(runCatching { discovery.parse(method) }.isFailure).isTrue()
    }

    @Test fun `fresh installation stays unconfigured after auth preferences are created`() {
        val context = RuntimeEnvironment.getApplication()
        assertThat(LegacyDeploymentMigration.initialServer(context, "")).isEmpty()
        context.getSharedPreferences("auth_prefs", 0).edit().putString("key", "value").commit()
        assertThat(LegacyDeploymentMigration.initialServer(context, "")).isEmpty()
    }

    @Test fun `existing implicit deployment is preserved once`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("auth_prefs", 0).edit().putString("key", "value").commit()
        assertThat(LegacyDeploymentMigration.initialServer(context, "")).isEqualTo("https://store.lelloman.com")
        context.getSharedPreferences("auth_prefs", 0).edit().clear().commit()
        assertThat(LegacyDeploymentMigration.initialServer(context, "")).isEqualTo("https://store.lelloman.com")
    }
}
