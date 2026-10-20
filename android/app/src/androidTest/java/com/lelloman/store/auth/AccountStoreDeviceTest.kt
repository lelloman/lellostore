package com.lelloman.store.auth

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.lelloman.authenticator.broker.AccountsModel
import com.lelloman.authenticator.broker.LelloAuthBroker
import com.lelloman.authenticator.domain.*
import com.lelloman.authenticator.localdata.AccountStore
import com.lelloman.authenticator.localdata.PhoneKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Isolated emulator with a screen lock. Credentials persist encrypted; wrong callbacks are refused. */
class AccountStoreDeviceTest {
    // HiltTestApplication does not run LelloStoreApplication initialization. This
    // outer rule must configure the broker before the inner activity rule launches.
    @get:Rule(order = 0) val broker = object : ExternalResource() {
        override fun before() {
            EmbeddedSignInBroker.initialize()
            check(LelloAuthBroker.isConfigured) { "Run with -PbrokerEnabled=true and a development issuer" }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BrokerAccountsActivity>()

    @Test fun accountCredentialsAndPendingEnrollmentSurviveRecreationWithoutAcceptingWrongCallbacks() {
        val keys = PhoneKey()
        val (alias, publicKey) = keys.create()
        val accountKey = keys.create()
        val now = System.currentTimeMillis()/1000
        val pending = PendingEnrollment(EnrollmentProtocol.random(), "https://auth.example.test", "phone-client", alias, publicKey, EnrollmentProtocol.random(), EnrollmentProtocol.random(), now+600)
        val account = ConnectedAccount(EnrollmentProtocol.random(), pending.issuer, "synthetic-user", "Private account", accountKey.first, accountKey.second, "synthetic-access", "synthetic-refresh", now+600)
        val store = AccountStore(compose.activity)
        val original = store.load()
        val code = EnrollmentProtocol.random()
        val callback = "${pending.issuer}/authenticator/callback?enrollment_id=${pending.id}&state=${pending.state}&code=$code"
        try {
            compose.runOnUiThread {
                store.update { AccountDocument(accounts = listOf(account), selectedAccount = account.id, pendingEnrollment = pending) }
                val model = ViewModelProvider(compose.activity)[AccountsModel::class.java]
                model.foreground()
                assertEquals(account.name, model.state.accounts.single().name)
                assertTrue(model.state.enrollmentPending); assertFalse(model.state.enrollmentApproved)
                val sessionFile = java.io.File(compose.activity.noBackupFilesDir, "connected-accounts.bin")
                val raw = sessionFile.readBytes().decodeToString()
                assertFalse(raw.contains("synthetic-access")); assertFalse(raw.contains("synthetic-refresh")); assertFalse(raw.contains("Private account"))
                assertFalse(model.enrollmentCallback(callback.replace(pending.state, EnrollmentProtocol.random())))
                assertNull(store.load().pendingEnrollment?.code)
                model.dismissError()
                assertTrue(model.enrollmentCallback(callback))
                assertEquals(code, store.load().pendingEnrollment?.code)
                assertTrue(model.state.enrollmentApproved)
                // The setting lives in the same encrypted document and defaults to off.
                assertFalse(LelloAuthBroker.settings(compose.activity).requireUserAuthenticationForApproval.value)
                LelloAuthBroker.settings(compose.activity).setRequireUserAuthenticationForApproval(true)
                assertTrue(store.load().requireUserAuthentication)
                LelloAuthBroker.settings(compose.activity).setRequireUserAuthenticationForApproval(false)
            }
            compose.activityRule.scenario.recreate()
            compose.runOnUiThread {
                val model = ViewModelProvider(compose.activity)[AccountsModel::class.java]
                model.foreground()
                assertEquals(account.name, model.state.accounts.single().name)
                assertEquals(code, store.load().pendingEnrollment?.code)
                assertFalse(model.enrollmentCallback(callback.replace(code, EnrollmentProtocol.random())))
                assertEquals(code, store.load().pendingEnrollment?.code)
                model.dismissError()
                model.cancelEnrollment()
                assertNull(store.load().pendingEnrollment)
                assertEquals(account, store.load().accounts.single())
                assertFalse(model.enrollmentCallback(callback))
                assertTrue(runCatching { keys.begin(alias, publicKey) }.isFailure)
                model.disconnect(account.id)
                assertTrue(store.load().accounts.isEmpty())
                assertNull(store.load().selectedAccount)
                assertTrue(runCatching { keys.begin(accountKey.first, accountKey.second) }.isFailure)
            }
        } finally {
            store.update { original }
            keys.delete(alias); keys.delete(accountKey.first)
        }
    }
}
