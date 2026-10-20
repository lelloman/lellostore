package com.lelloman.store.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lelloman.authenticator.broker.BrokerActivity
import com.lelloman.authenticator.broker.BrokerConfig
import com.lelloman.authenticator.broker.LelloAuthBroker
import com.lelloman.authenticator.localdata.AccountStore
import com.lelloman.authenticator.domain.NativeAuthorization
import com.lelloman.store.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationManagementActivity
import net.openid.appauth.AuthorizationRequest

/** Same-package caller: the broker still validates the server's registered callback recipient. */
object EmbeddedSignInBroker {
    fun initialize() {
        LelloAuthBroker.configure(if (BuildConfig.SIGN_IN_BROKER_ENABLED) {
            BrokerConfig(BuildConfig.SIGN_IN_BROKER_ISSUER)
        } else null)
    }

    fun acceptsIssuer(issuer: String): Boolean =
        BuildConfig.SIGN_IN_BROKER_ENABLED && LelloAuthBroker.config?.issuer == issuer

    fun hasAccount(context: Context, issuer: String): Boolean = try {
        AccountStore(context).load().accounts.any { it.issuer == issuer }
    } catch (error: Exception) {
        Log.w("SignInBroker", "Could not read connected accounts; using hosted sign-in", error)
        false
    }

    suspend fun authorizationIntent(context: Context, request: AuthorizationRequest): Intent? =
        withContext(Dispatchers.IO) {
            val issuer = request.configuration.discoveryDoc?.issuer?.toString() ?: return@withContext null
            if (!acceptsIssuer(issuer) || !hasAccount(context, issuer)) return@withContext null
            try { NativeAuthorization.parse(request.toUri().toString(), issuer) }
            catch (error: Exception) {
                Log.w("SignInBroker", "Request is not supported by native approval; using hosted sign-in", error)
                return@withContext null
            }
            // Use AppAuth's public manager to retain PKCE, state checking, cancellation and
            // callback handling. No callback data is accepted directly by this object.
            AuthorizationManagementActivity.createStartForResultIntent(
                context, request,
                Intent(context, BrokerActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(request.toUri()),
            )
        }
}
