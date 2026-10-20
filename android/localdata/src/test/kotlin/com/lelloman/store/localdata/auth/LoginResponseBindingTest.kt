package com.lelloman.store.localdata.auth

import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class LoginResponseBindingTest {
    private fun request(state: String = "original-state", redirect: String = "com.lelloman.store:/oauth2redirect") =
        AuthorizationRequest.Builder(
            AuthorizationServiceConfiguration(Uri.parse("https://issuer.test/authorize"), Uri.parse("https://issuer.test/token")),
            "store", ResponseTypeValues.CODE, Uri.parse(redirect),
        ).setState(state).build()

    @Test fun `original request and state are required before token exchange`() {
        val request = request()
        val response = AuthorizationResponse.Builder(request).setState(request.state).setAuthorizationCode("code").build()
        assertTrue(matchesLoginResponse(request.jsonSerializeString(), request.state, response))
        assertFalse(matchesLoginResponse(request.jsonSerializeString(), "another-state", response))
        assertFalse(matchesLoginResponse(request.jsonSerializeString(), null, response))
    }

    @Test fun `wrong callback state cannot reuse the original request`() {
        val request = request()
        val response = AuthorizationResponse.Builder(request).setState("wrong").setAuthorizationCode("code").build()
        assertFalse(matchesLoginResponse(request.jsonSerializeString(), request.state, response))
    }

    @Test fun `impersonated callback cannot replace redirect or PKCE using the correct state`() {
        val original = request()
        val replacement = request(redirect = "attacker:/callback")
        val response = AuthorizationResponse.Builder(replacement).setState(original.state).setAuthorizationCode("code").build()
        assertFalse(matchesLoginResponse(original.jsonSerializeString(), original.state, response))
        val otherVerifier = request()
        val replacedVerifier = AuthorizationResponse.Builder(otherVerifier).setState(original.state).setAuthorizationCode("code").build()
        assertFalse(matchesLoginResponse(original.jsonSerializeString(), original.state, replacedVerifier))
    }

    @Test fun `a recreated AppAuth request retains its binding while a new login rejects the old callback`() {
        val original = request()
        val restored = AuthorizationRequest.jsonDeserialize(original.jsonSerializeString())
        val response = AuthorizationResponse.Builder(restored).setState(original.state).setAuthorizationCode("code").build()
        assertTrue(matchesLoginResponse(original.jsonSerializeString(), original.state, response))
        val newLogin = request("new-state")
        assertFalse(matchesLoginResponse(newLogin.jsonSerializeString(), newLogin.state, response))
    }
}
