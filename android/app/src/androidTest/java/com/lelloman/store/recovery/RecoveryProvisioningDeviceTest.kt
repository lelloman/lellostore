package com.lelloman.store.recovery

import android.content.Intent
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.installation.InstallationRequest
import com.lelloman.store.installation.ChannelInstallationResult
import com.lelloman.store.installation.LegacyAdbInstallationChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in signed-release tests. Use a disposable emulator; never run the upgrade test on a personal device. */
class RecoveryProvisioningDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun provisionAndBindHealthToAttempt() = runBlocking {
        assumeTrue(arguments.getString("recoveryValidation") == "true")
        val client = RecoveryCompanionClient(context)
        assertThat(client.trustedCompanionInstalled()).isTrue()
        client.provision().getOrThrow()
        client.setSelfUpdatesEnabled(true)
        val current = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()
        val attempt = requireNotNull(client.recordSelfUpdate(current + 1))
        assertThat(client.acknowledgePendingHealth()).isFalse()
        assertThat(client.cancelUnreplacedAttempt("stale-attempt", "test")).isFalse()
        assertThat(client.cancelUnreplacedAttempt(attempt, "Validation: no replacement requested")).isTrue()
        context.getExternalFilesDir(null)?.mkdirs()
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, RecoverySetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Test
    fun pairIndependentWirelessIdentity() = runBlocking {
        val code = arguments.getString("recoveryPairCode")
        assumeTrue(arguments.getString("recoveryValidation") == "true" && code != null)
        RecoveryCompanionClient(context).pairRecovery(requireNotNull(code)).getOrThrow()
    }

    @Test
    fun installHigherVersionFixtureThroughSelfAdb() = runBlocking {
        assumeTrue(arguments.getString("recoveryUpgradeValidation") == "true")
        val fixture = requireNotNull(context.getExternalFilesDir(null)).resolve("recovery-upgrade.apk")
        val target = requireNotNull(arguments.getString("recoveryTargetVersion")).toInt()
        val client = RecoveryCompanionClient(context)
        val attempt = requireNotNull(client.recordSelfUpdate(target))
        // Replacement kills this instrumentation process. The host must verify companion HEALTHY afterward.
        val result = LegacyAdbInstallationChannel(context).install(InstallationRequest(
            apk = fixture, packageName = context.packageName, versionCode = target, mode = InstallationMode.BACKGROUND,
        ))
        if (result != ChannelInstallationResult.Installed) {
            client.cancelUnreplacedAttempt(attempt, "Validation install rejected: $result")
        }
        assertThat(result).isEqualTo(ChannelInstallationResult.Installed)
    }
}
