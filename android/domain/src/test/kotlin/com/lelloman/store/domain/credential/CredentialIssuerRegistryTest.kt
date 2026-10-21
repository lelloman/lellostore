package com.lelloman.store.domain.credential

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialIssuerRegistryTest {

    private class FakeIssuer(override val type: String) : CredentialIssuer {
        override fun describe(request: CredentialRequest) = CredentialDescription("title", "message")
        override suspend fun issue(request: CredentialRequest): IssueResult = IssueResult.Success("credential")
    }

    @Test
    fun `issuers are found by their type`() {
        val deviceToken = FakeIssuer("lellostore.device_token")
        val identity = FakeIssuer("pezzottify.identity")

        val registry = CredentialIssuerRegistry(setOf(deviceToken, identity))

        assertThat(registry["lellostore.device_token"]).isSameInstanceAs(deviceToken)
        assertThat(registry["pezzottify.identity"]).isSameInstanceAs(identity)
        assertThat(registry.types).containsExactly("lellostore.device_token", "pezzottify.identity")
    }

    @Test
    fun `unknown types are not found`() {
        val registry = CredentialIssuerRegistry(setOf(FakeIssuer("lellostore.device_token")))

        assertThat(registry["lellostore.publish_token"]).isNull()
        assertThat(registry["LELLOSTORE.DEVICE_TOKEN"]).isNull()
        assertThat(CredentialIssuerRegistry(emptySet())["lellostore.device_token"]).isNull()
    }

    @Test
    fun `two issuers of the same type are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialIssuerRegistry(setOf(FakeIssuer("lellostore.device_token"), FakeIssuer("lellostore.device_token")))
        }
    }

    @Test
    fun `a blank type is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialIssuerRegistry(setOf(FakeIssuer(" ")))
        }
    }

    @Test
    fun `successful results keep the credential out of toString`() {
        assertThat(IssueResult.Success("lellostore_dev_secret").toString()).doesNotContain("secret")
    }
}
