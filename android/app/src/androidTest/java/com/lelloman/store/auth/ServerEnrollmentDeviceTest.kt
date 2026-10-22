package com.lelloman.store.auth

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lelloman.authenticator.broker.AccountsModel
import com.lelloman.authenticator.broker.ApprovalModel
import com.lelloman.authenticator.broker.BrokerActivity
import com.lelloman.authenticator.broker.CallbackRecipient
import com.lelloman.authenticator.broker.LelloAuthBroker
import com.lelloman.authenticator.domain.*
import com.lelloman.authenticator.localdata.AccountStore
import com.lelloman.authenticator.localdata.PhoneKey
import com.lelloman.authenticator.remoteapi.EnrollmentApi
import com.lelloman.authenticator.remoteapi.DevicesApi
import com.lelloman.authenticator.remoteapi.BrokerCredentialExpired
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Opt-in, synthetic localhost server only; never supplies credentials to a production issuer. */
class ServerEnrollmentDeviceTest {
    @org.junit.Before fun configureBroker() { EmbeddedSignInBroker.initialize() }
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val issuer = "https://localhost:19443"
    private val cookies = mutableMapOf<String, String>()
    private fun hosted(path: String, fields: Map<String,String>? = null): Pair<Int,String?> {
        val connection = URI(issuer + path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (fields != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Origin", issuer)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = fields.entries.joinToString("&") { "${URLEncoder.encode(it.key,"UTF-8")}=${URLEncoder.encode(it.value,"UTF-8")}" }
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten().forEach {
                val parts = it.substringBefore(';').split('=', limit=2); cookies[parts[0]] = parts[1]
            }
            return status to connection.getHeaderField("Location")
        } finally { connection.disconnect() }
    }
    private fun pinPrompt(timeout: Long = 10000) = device.wait(Until.findObject(By.clazz("android.widget.EditText")), timeout)

    @Test fun twoAccountsEnrollWithoutPromptsAndApproveWithAndWithoutTheBiometricSetting() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localEnrollment") == "true")
        assertEquals(issuer, com.lelloman.store.BuildConfig.SIGN_IN_BROKER_ISSUER)
        val keys = PhoneKey(); val aliases = mutableListOf<String>(); val accounts = mutableListOf<ConnectedAccount>()
        val store = AccountStore(context)
        val originalAccounts = store.load()
        store.update { AccountDocument() }
        assertNull(kotlinx.coroutines.runBlocking { EmbeddedSignInBroker.authorizationIntent(context, storeRequest()) })
        val settings = LelloAuthBroker.settings(context)
        try {
            ActivityScenario.launch(BrokerAccountsActivity::class.java).use { scenario ->
                for (username in listOf("android-fixture", "android-fixture-two")) {
                    cookies.clear()
                    val (alias, publicKey) = keys.create(); aliases += alias
                    val api = EnrollmentApi(issuer, "lello-authenticator")
                    val state = EnrollmentProtocol.random(); val verifier = EnrollmentProtocol.random()
                    val started = api.start(publicKey, "Synthetic emulator", state, verifier)
                    val pending = PendingEnrollment(started.id, issuer, "lello-authenticator", alias, publicKey, state, verifier, started.expiresAt)
                    val path = "/authenticator/enroll/${pending.id}"
                    assertEquals(303, hosted(path).first)
                    assertEquals(303, hosted("/login", mapOf("csrf_token" to cookies.getValue("__Host-lelloauth_csrf"), "username" to username, "password" to "fixture-password-2468", "redirect_to" to path)).first)
                    assertEquals(200, hosted(path).first)
                    val approval = hosted(path, mapOf("csrf_token" to cookies.getValue("__Host-lelloauth_csrf"), "action" to "allow"))
                    assertEquals(303, approval.first)
                    lateinit var model: AccountsModel
                    scenario.onActivity { activity ->
                        model = ViewModelProvider(activity)[AccountsModel::class.java]
                        store.update { it.copy(pendingEnrollment = pending) }
                        model.foreground()
                        assertTrue(model.enrollmentCallback(checkNotNull(approval.second)))
                        model.connectAccount()
                    }
                    // Enrollment signs the proof directly: no system prompt may appear.
                    compose.waitUntil(20000) { !model.state.loading && model.state.accounts.size == accounts.size + 1 }
                    assertNull(pinPrompt(500))
                    assertNull(model.state.error)
                    val document = store.load()
                    assertNull(document.pendingEnrollment)
                    val account = document.accounts.last()
                    assertEquals(username, account.name)
                    accounts += account
                }
            }
            val manager = kotlinx.coroutines.runBlocking { EmbeddedSignInBroker.authorizationIntent(context, storeRequest()) }
            assertEquals(net.openid.appauth.AuthorizationManagementActivity::class.java.name, checkNotNull(manager).component?.className)
            assertNotEquals(accounts[0].userId, accounts[1].userId)
            assertNotEquals(accounts[0].keyAlias, accounts[1].keyAlias)
            assertNotEquals(accounts[0].refreshToken, accounts[1].refreshToken)
            settings.setRequireUserAuthenticationForApproval(false)
            val first = nativeApproval(requirePrompt = false)
            settings.setRequireUserAuthenticationForApproval(true)
            nativeApproval(requirePrompt = true)
            exchange(checkNotNull(first))
            nativeApproval(requirePrompt = false, expireOnly = true)
            manageDevices(accounts, store)
        } finally {
            runCatching { settings.setRequireUserAuthenticationForApproval(false) }
            store.update { originalAccounts }
            aliases.forEach(keys::delete)
        }
    }
    private fun storeRequest(): net.openid.appauth.AuthorizationRequest {
        val document = org.json.JSONObject().put("issuer", issuer)
            .put("authorization_endpoint", "$issuer/authorize").put("token_endpoint", "$issuer/token")
            .put("jwks_uri", "$issuer/.well-known/jwks.json")
            .put("response_types_supported", org.json.JSONArray().put("code"))
            .put("subject_types_supported", org.json.JSONArray().put("public"))
            .put("id_token_signing_alg_values_supported", org.json.JSONArray().put("RS256"))
        val configuration = net.openid.appauth.AuthorizationServiceConfiguration(net.openid.appauth.AuthorizationServiceDiscovery(document))
        return net.openid.appauth.AuthorizationRequest.Builder(configuration, "android-native-fixture", "code",
            android.net.Uri.parse("lelloauth-fixture:/callback")).setScopes("openid", "profile", "email").build()
    }
    private fun manageDevices(accounts: List<ConnectedAccount>, store: AccountStore) {
        val account = accounts.first()
        val other = accounts.last()
        val api = DevicesApi(account, "lello-authenticator")
        assertEquals(listOf(account.id), api.list().devices.map { it.id })
        assertEquals(listOf(other.id), DevicesApi(other, "lello-authenticator").list().devices.map { it.id })
        assertTrue(runCatching { api.rename(other.id, "Foreign phone") }.isFailure)
        assertTrue(runCatching { api.challenge(other.id, System.currentTimeMillis()/1000) }.isFailure)
        settingsOffForManagement()
        ActivityScenario.launch(BrokerAccountsActivity::class.java).use { scenario ->
            lateinit var model: AccountsModel
            scenario.onActivity { model = ViewModelProvider(it)[AccountsModel::class.java]; model.showDevices(account.id) }
            compose.waitUntil(15000) { !model.state.loading }
            assertEquals(listOf(account.id), model.state.devices.map { it.id })
            compose.onNodeWithText("Rename", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Renamed emulator")
            compose.onNodeWithText("Confirm device change", useUnmergedTree = true).performClick()
            assertNotNull("Management requires PIN even with approval biometrics off", pinPrompt())
            device.pressBack()
            compose.waitUntil(10000) { !model.authenticationPending }
            assertNotEquals("Renamed emulator", api.list().devices.single().name)
            compose.onNodeWithText("Rename", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Renamed emulator")
            compose.onNodeWithText("Confirm device change", useUnmergedTree = true).performClick()
            checkNotNull(pinPrompt()).text = "2468"; device.pressEnter()
            compose.waitUntil(15000) { !model.state.loading && model.state.devices.singleOrNull()?.name == "Renamed emulator" }
            assertEquals("Renamed emulator", api.list().devices.single().name)
            compose.onNodeWithText("Revoke", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Confirm device change", useUnmergedTree = true).performClick()
            checkNotNull(pinPrompt()).text = "2468"; device.pressEnter()
            compose.waitUntil(15000) { !model.state.loading && model.state.accounts.none { it.id == account.id } }
            assertNull(model.state.devicesAccount)
            assertEquals(listOf(other.id), store.load().accounts.map { it.id })
            assertTrue(runCatching { api.list() }.exceptionOrNull() is BrokerCredentialExpired)
            assertTrue(runCatching { EnrollmentApi(account.issuer, "lello-authenticator").refresh(account) }.exceptionOrNull() is BrokerCredentialExpired)
            scenario.onActivity { model.showDevices(other.id) }
            compose.waitUntil(15000) { !model.state.loading }
            assertEquals(listOf(other.id), model.state.devices.map { it.id })
            scenario.onActivity { model.disconnect(other.id) }
            assertTrue(store.load().accounts.isEmpty())
            scenario.onActivity { model.foreground() }
            assertTrue(model.state.accounts.isEmpty())
        }
    }
    private fun settingsOffForManagement() { LelloAuthBroker.settings(context).setRequireUserAuthenticationForApproval(false) }
    private data class Approved(val code: String, val verifier: String)
    private fun nativeApproval(requirePrompt: Boolean, expireOnly: Boolean = false): Approved? {
        val verifier = EnrollmentProtocol.random()
        val callerState = EnrollmentProtocol.random()
        val fields = mapOf("client_id" to "android-native-fixture", "response_type" to "code", "redirect_uri" to "lelloauth-fixture:/callback",
            "scope" to "openid profile email", "state" to callerState, "nonce" to "synthetic-nonce", "code_challenge" to EnrollmentProtocol.challenge(verifier), "code_challenge_method" to "S256")
        val uri = issuer + "/authorize?" + fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value,"UTF-8")}" }
        SyntheticCallbackActivity.lastCallback = null
        // The App Link arrives as a new intent on the host's exported broker activity, which finishes itself after delivery.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, BrokerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrokerActivity
        try {
            lateinit var approval: ApprovalModel
            instrumentation.runOnMainSync { approval = ViewModelProvider(activity)[ApprovalModel::class.java] }
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setClass(context, BrokerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(10000) { approval.state.active }
            compose.waitUntil(15000) { approval.state.transaction != null || approval.state.error }
            instrumentation.runOnMainSync {
                assertFalse(approval.state.error)
                assertFalse(approval.incoming(uri.replace(callerState, EnrollmentProtocol.random())))
                assertNull(approval.state.delivery)
                val target = checkNotNull(approval.state.transaction).recipient
                assertTrue(runCatching { CallbackRecipient(activity).intent(target.copy(certificate = "00".repeat(32))) }.isFailure)
                assertEquals(activity.packageName, CallbackRecipient(activity).intent(target).component?.packageName)
                // A freshly constructed model, as after process death, holds no approval or signature.
                val recreated = ApprovalModel(activity.application)
                assertFalse(recreated.state.active)
                assertNull(recreated.approve())
            }
            compose.onNodeWithText("Synthetic music app").assertIsDisplayed()
            assertNull(SyntheticCallbackActivity.lastCallback)
            if (expireOnly) {
                // Real two-minute expiry: no fake server timestamp and no clock override.
                val expiresAt = checkNotNull(approval.state.transaction).expiresAt
                compose.waitUntil(135000) { System.currentTimeMillis() / 1000 >= expiresAt + 2 }
                compose.onAllNodesWithText("Approve sign-in", useUnmergedTree = true).onLast().assertIsNotEnabled()
                instrumentation.runOnMainSync {
                    assertNull(approval.approve())
                    assertTrue(approval.state.expired)
                    assertFalse(approval.state.proofReady)
                    assertNull(approval.state.delivery)
                }
                assertNull(SyntheticCallbackActivity.lastCallback)
                return null
            }
            compose.onAllNodesWithText("Approve sign-in", useUnmergedTree = true).onLast().performScrollTo().performClick()
            compose.waitForIdle()
            if (requirePrompt) {
                assertNotNull("The setting must require a system prompt before signing", pinPrompt())
                device.pressBack()
                compose.waitUntil(10000) { !approval.state.promptPending && !approval.state.signingReady }
                assertNull("Cancelling the prompt must not sign", SyntheticCallbackActivity.lastCallback)
                assertNotNull("The transaction stays pending after a cancelled prompt", approval.state.transaction)
                compose.onAllNodesWithText("Approve sign-in", useUnmergedTree = true).onLast().performScrollTo().performClick()
                val pin = checkNotNull(pinPrompt())
                pin.text = "2468"; device.pressEnter()
            } else assertNull("A tap-only approval must not show a system prompt", pinPrompt(1500))
            compose.waitUntil(20000) { SyntheticCallbackActivity.lastCallback != null }
        } finally { instrumentation.runOnMainSync { if (!activity.isFinishing && !activity.isDestroyed) activity.finish() } }
        val callback = android.net.Uri.parse(checkNotNull(SyntheticCallbackActivity.lastCallback))
        assertEquals(callerState, callback.getQueryParameter("state"))
        return Approved(checkNotNull(callback.getQueryParameter("code")), verifier)
    }
    private fun exchange(approved: Approved) {
        val connection = URI(issuer + "/token").toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val fields = mapOf("grant_type" to "authorization_code", "client_id" to "android-native-fixture", "redirect_uri" to "lelloauth-fixture:/callback", "code" to approved.code, "code_verifier" to approved.verifier)
            connection.outputStream.use { out -> out.write(fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value,"UTF-8")}" }.toByteArray()) }
            assertEquals(200, connection.responseCode)
            val token = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val claims = org.json.JSONObject(String(java.util.Base64.getUrlDecoder().decode(token.getString("id_token").split('.')[1])))
            assertEquals("synthetic-nonce", claims.getString("nonce"))
            assertEquals("android-native-fixture", claims.getString("aud"))
        } finally { connection.disconnect() }
    }
}
