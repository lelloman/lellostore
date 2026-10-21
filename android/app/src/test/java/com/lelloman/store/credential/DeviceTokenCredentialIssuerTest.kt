package com.lelloman.store.credential

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.AdminApiException
import com.lelloman.store.domain.api.AppGrant
import com.lelloman.store.domain.api.AppGroupSummary
import com.lelloman.store.domain.api.CreatedDeviceToken
import com.lelloman.store.domain.api.DeviceTokenAdminApi
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.domain.credential.CredentialError
import com.lelloman.store.domain.credential.CredentialIssuerRegistry
import com.lelloman.store.domain.credential.CredentialRequest
import com.lelloman.store.domain.credential.IssueResult
import com.lelloman.store.localdata.auth.DeviceToken
import com.lelloman.store.localdata.auth.DeviceTokenFile
import com.lelloman.store.logger.AuditLog
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class DeviceTokenCredentialIssuerTest {

    private val api = FakeDeviceTokenAdminApi()
    private val issuer = DeviceTokenCredentialIssuer(
        RuntimeEnvironment.getApplication(),
        api,
        AuditLog(RuntimeEnvironment.getApplication()),
    )

    private fun request(replaceExisting: Boolean = false) = CredentialRequest(
        type = DeviceTokenCredentialIssuer.TYPE,
        callerPackage = "com.lelloman.pezzottv",
        callerLabel = "PezzotTV",
        subjectName = "Mi Box (living room)",
        replaceExisting = replaceExisting,
    )

    @Test
    fun `the credential is the web admin device-token json`() = runTest {
        val result = issuer.issue(request())

        val credential = (result as IssueResult.Success).credential
        assertThat(credential).isEqualTo(
            "{\"version\":1,\"server_url\":\"https://store.example.com\",\"name\":\"Mi Box (living room)\",\"token\":\"$SECRET\"}",
        )
        assertThat(DeviceTokenFile.parse(credential)).isEqualTo(
            DeviceTokenFile.Result.Valid(DeviceToken("Mi Box (living room)", "https://store.example.com", SECRET)),
        )
        assertThat(api.created).containsExactly("Mi Box (living room)" to listOf(ALL_GROUP))
        assertThat(api.listed).isFalse()
        assertThat(api.revoked).isEmpty()
    }

    @Test
    fun `a replacing token inherits the groups and direct grants of the tokens it replaces`() = runTest {
        api.existing = listOf(
            DeviceTokenSummary("old", "Mi Box (living room)", "2026-10-20 09:00:00", null, groupIds = listOf(7)),
            DeviceTokenSummary("other-name", "Mi Box (bedroom)", "2026-10-20 09:00:00", null, groupIds = listOf(9)),
            DeviceTokenSummary("revoked", "Mi Box (living room)", "2026-10-19 09:00:00", "2026-10-19 10:00:00", groupIds = listOf(8)),
        )
        api.grants = mapOf(
            "old" to listOf(AppGrant("com.example.player", "beta")),
            "other-name" to listOf(AppGrant("com.example.other", "stable")),
        )

        assertThat(issuer.issue(request(replaceExisting = true))).isInstanceOf(IssueResult.Success::class.java)

        assertThat(api.created).containsExactly("Mi Box (living room)" to listOf(7L))
        assertThat(api.grantsSet).containsExactly(NEW_ID to AppGrant("com.example.player", "beta"))
        assertThat(api.groupsListed).isFalse()
        assertThat(api.revoked).containsExactly("old")
    }

    @Test
    fun `access of several replaced tokens is united`() = runTest {
        api.existing = listOf(
            DeviceTokenSummary("a", "Mi Box (living room)", "2026-10-19 09:00:00", null, groupIds = listOf(3, 7)),
            DeviceTokenSummary("b", "Mi Box (living room)", "2026-10-20 09:00:00", null, groupIds = listOf(7, 5)),
        )
        api.grants = mapOf(
            "a" to listOf(AppGrant("com.example.player", "stable"), AppGrant("com.example.news", "stable")),
            "b" to listOf(AppGrant("com.example.player", "beta")),
        )

        issuer.issue(request(replaceExisting = true))

        assertThat(api.created).containsExactly("Mi Box (living room)" to listOf(3L, 5L, 7L))
        assertThat(api.grantsSet).containsExactly(
            NEW_ID to AppGrant("com.example.player", "beta"),
            NEW_ID to AppGrant("com.example.news", "stable"),
        )
        assertThat(api.revoked).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `with nothing to inherit the token joins the all group`() = runTest {
        api.existing = listOf(
            DeviceTokenSummary("other-name", "Mi Box (bedroom)", "2026-10-20 09:00:00", null, groupIds = listOf(9)),
        )
        issuer.issue(request(replaceExisting = true))
        assertThat(api.created.last()).isEqualTo("Mi Box (living room)" to listOf(ALL_GROUP))

        // Earlier tokens without any access (as the broker used to create them) do not keep the device empty.
        api.existing = listOf(DeviceTokenSummary("old", "Mi Box (living room)", "2026-10-20 09:00:00", null))
        issuer.issue(request(replaceExisting = true))
        assertThat(api.created.last()).isEqualTo("Mi Box (living room)" to listOf(ALL_GROUP))
        assertThat(api.grantsSet).isEmpty()
    }

    @Test
    fun `a server without the all group creates a token without groups`() = runTest {
        api.groups = listOf(AppGroupSummary(4, "family", null))

        assertThat(issuer.issue(request(replaceExisting = true))).isInstanceOf(IssueResult.Success::class.java)

        assertThat(api.created).containsExactly("Mi Box (living room)" to emptyList<Long>())
    }

    @Test
    fun `failing to read access still issues the token but never widens it to all`() = runTest {
        api.listResult = Result.failure(java.io.IOException("offline"))
        assertThat(issuer.issue(request(replaceExisting = true))).isInstanceOf(IssueResult.Success::class.java)
        assertThat(api.created.last()).isEqualTo("Mi Box (living room)" to emptyList<Long>())
        assertThat(api.revoked).isEmpty()

        api.listResult = null
        api.existing = listOf(DeviceTokenSummary("old", "Mi Box (living room)", "2026-10-20 09:00:00", null))
        api.grantsResult = Result.failure(AdminApiException(500))
        assertThat(issuer.issue(request(replaceExisting = true))).isInstanceOf(IssueResult.Success::class.java)
        assertThat(api.created.last()).isEqualTo("Mi Box (living room)" to emptyList<Long>())
        assertThat(api.revoked).containsExactly("old")

        api.grantsResult = null
        api.groupsResult = Result.failure(AdminApiException(500))
        assertThat(issuer.issue(request(replaceExisting = true))).isInstanceOf(IssueResult.Success::class.java)
        assertThat(api.created.last()).isEqualTo("Mi Box (living room)" to emptyList<Long>())
    }

    @Test
    fun `a grant that cannot be applied still returns the token`() = runTest {
        api.existing = listOf(DeviceTokenSummary("old", "Mi Box (living room)", "2026-10-20 09:00:00", null))
        api.grants = mapOf("old" to listOf(AppGrant("com.example.player", "stable")))
        api.setGrantResult = Result.failure(AdminApiException(404))

        assertThat(issuer.issue(request(replaceExisting = true))).isInstanceOf(IssueResult.Success::class.java)
        assertThat(api.revoked).containsExactly("old")
    }

    @Test
    fun `replacing revokes only older unrevoked tokens with the same name`() = runTest {
        api.existing = listOf(
            DeviceTokenSummary("old", "Mi Box (living room)", "2026-10-20 09:00:00", null),
            DeviceTokenSummary("same-second", "Mi Box (living room)", NOW, null),
            DeviceTokenSummary("already-revoked", "Mi Box (living room)", "2026-10-19 09:00:00", "2026-10-19 10:00:00"),
            DeviceTokenSummary("other-name", "Mi Box (bedroom)", "2026-10-20 09:00:00", null),
            DeviceTokenSummary("case", "mi box (living room)", "2026-10-20 09:00:00", null),
            DeviceTokenSummary("newer", "Mi Box (living room)", "2026-10-21 10:00:01", null),
            DeviceTokenSummary(NEW_ID, "Mi Box (living room)", NOW, null),
        )

        val result = issuer.issue(request(replaceExisting = true))

        assertThat(result).isInstanceOf(IssueResult.Success::class.java)
        assertThat(api.revoked).containsExactly("old", "same-second").inOrder()
    }

    @Test
    fun `a failed replacement still returns the new token`() = runTest {
        api.existing = listOf(DeviceTokenSummary("old", "Mi Box (living room)", "2026-10-20 09:00:00", null))
        api.revokeResult = Result.failure(AdminApiException(500))

        assertThat(issuer.issue(request(replaceExisting = true))).isInstanceOf(IssueResult.Success::class.java)

        api.listResult = Result.failure(java.io.IOException("offline"))
        assertThat(issuer.issue(request(replaceExisting = true))).isInstanceOf(IssueResult.Success::class.java)
    }

    @Test
    fun `server refusals map to contract errors`() = runTest {
        val cases = mapOf(
            AdminApiException(403) to CredentialError.NOT_AUTHORIZED,
            AdminApiException(401) to CredentialError.NOT_LOGGED_IN,
            AdminApiException(500) to CredentialError.NETWORK,
            AdminApiException(400) to CredentialError.NETWORK,
            java.io.IOException("offline") to CredentialError.NETWORK,
        )
        for ((error, expected) in cases) {
            api.createResult = Result.failure(error)
            assertThat(issuer.issue(request(replaceExisting = true))).isEqualTo(IssueResult.Failure(expected))
        }
        assertThat(api.revoked).isEmpty()
    }

    @Test
    fun `the confirmation names the caller and the device`() {
        val plain = issuer.describe(request())
        assertThat(plain.message).isEqualTo(
            "PezzotTV wants a LelloStore device token for “Mi Box (living room)”. " +
                "The device will be able to install and update apps from your store.",
        )
        assertThat(issuer.describe(request(replaceExisting = true)).message)
            .endsWith("Older device tokens named “Mi Box (living room)” will be revoked.")
    }

    @Test
    fun `the device-token issuer is registered by its type`() {
        val registry = CredentialIssuerRegistry(setOf(issuer))

        assertThat(registry["lellostore.device_token"]).isSameInstanceAs(issuer)
    }

    private class FakeDeviceTokenAdminApi : DeviceTokenAdminApi {
        val created = mutableListOf<Pair<String, List<Long>>>()
        val revoked = mutableListOf<String>()
        val grantsSet = mutableListOf<Pair<String, AppGrant>>()
        var listed = false
        var groupsListed = false
        var existing = emptyList<DeviceTokenSummary>()
        var groups = listOf(AppGroupSummary(4, "family", null), AppGroupSummary(ALL_GROUP, "all", "all"))
        var grants = emptyMap<String, List<AppGrant>>()
        var createResult: Result<CreatedDeviceToken>? = null
        var listResult: Result<List<DeviceTokenSummary>>? = null
        var groupsResult: Result<List<AppGroupSummary>>? = null
        var grantsResult: Result<List<AppGrant>>? = null
        var revokeResult: Result<Unit> = Result.success(Unit)
        var setGrantResult: Result<Unit> = Result.success(Unit)

        override suspend fun create(name: String, groupIds: List<Long>): Result<CreatedDeviceToken> {
            created += name to groupIds
            return createResult ?: Result.success(
                CreatedDeviceToken(DeviceTokenSummary(NEW_ID, name, NOW, null, groupIds), SECRET, "https://store.example.com"),
            )
        }

        override suspend fun list(): Result<List<DeviceTokenSummary>> {
            listed = true
            return listResult ?: Result.success(existing)
        }

        override suspend fun revoke(id: String): Result<Unit> {
            revoked += id
            return revokeResult
        }

        override suspend fun listGroups(): Result<List<AppGroupSummary>> {
            groupsListed = true
            return groupsResult ?: Result.success(groups)
        }

        override suspend fun directGrants(tokenId: String): Result<List<AppGrant>> =
            grantsResult ?: Result.success(grants[tokenId].orEmpty())

        override suspend fun setDirectGrant(tokenId: String, grant: AppGrant): Result<Unit> {
            grantsSet += tokenId to grant
            return setGrantResult
        }
    }

    private companion object {
        const val NEW_ID = "new"
        const val NOW = "2026-10-21 10:00:00"
        const val ALL_GROUP = 1L
        val SECRET = "lellostore_dev_" + "0123456789abcdef".repeat(4)
    }
}
