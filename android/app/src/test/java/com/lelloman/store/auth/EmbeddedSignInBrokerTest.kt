package com.lelloman.store.auth

import android.app.Application
import android.net.Uri
import com.lelloman.authenticator.broker.BrokerConfig
import com.lelloman.authenticator.broker.LelloAuthBroker
import com.lelloman.store.BuildConfig
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class EmbeddedSignInBrokerTest {
    @Test fun `default build disables enrollment and authorization even with a stale library configuration`() = runTest {
        assertFalse(BuildConfig.SIGN_IN_BROKER_ENABLED)
        LelloAuthBroker.configure(BrokerConfig("https://stale.example.test"))
        EmbeddedSignInBroker.initialize()
        assertFalse(LelloAuthBroker.isConfigured)
        assertFalse(EmbeddedSignInBroker.acceptsIssuer("https://stale.example.test"))
        val request = AuthorizationRequest.Builder(
            AuthorizationServiceConfiguration(Uri.parse("https://issuer.test/authorize"), Uri.parse("https://issuer.test/token")),
            "store", ResponseTypeValues.CODE, Uri.parse("com.lelloman.store:/oauth2redirect"),
        ).build()
        // No access to accounts and no approval activity launch in a disabled build.
        assertNull(EmbeddedSignInBroker.authorizationIntent(mockk(), request))
    }
}
