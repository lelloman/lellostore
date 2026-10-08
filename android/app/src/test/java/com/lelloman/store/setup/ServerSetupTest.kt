package com.lelloman.store.setup

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ServerSetupTest {
    private fun metadata() = JSONObject("""{
        "schema_version":1,"name":"Independent Store",
        "auth":{"method":"oidc","issuer_url":"https://identity.example/realm",
        "clients":{"android":"independent-client"},"scopes":["openid","email"]},
        "capabilities":{"push":false,"paravoid":false}}
    """)

    @Test fun `discovery uses the selected stores public registration`() {
        val result = HttpServerDiscovery().parse(metadata())
        assertThat(result.name).isEqualTo("Independent Store")
        assertThat(result.oidc.issuerUrl).isEqualTo("https://identity.example/realm")
        assertThat(result.oidc.clientId).isEqualTo("independent-client")
        assertThat(result.push).isFalse()
    }

    @Test fun `unsupported or insecure metadata is rejected`() {
        val discovery = HttpServerDiscovery()
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
