package com.lelloman.store.installation

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.InstallationMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in physical-device test for the complete self-ADB package installation path.
 *
 * The normal connected test suite skips this test. Use scripts/validate-self-adb.sh with a
 * harmless APK whose package can be installed or upgraded on the selected device.
 */
class SelfAdbInstallationDeviceTest {

    @Test
    fun installFixtureAndRecordAttribution() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Run through scripts/validate-self-adb.sh",
            arguments.getString(ARG_ENABLED) == "true",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageName = requireNotNull(arguments.getString(ARG_PACKAGE_NAME))
        val versionCode = requireNotNull(arguments.getString(ARG_VERSION_CODE)).toInt()
        val transport = requireNotNull(arguments.getString(ARG_TRANSPORT))
        val apk = File(context.filesDir, FIXTURE_FILE_NAME)
        check(apk.isFile && apk.length() > 0) { "Validation APK was not staged in app files" }

        val channel = when (transport) {
            TRANSPORT_LEGACY -> LegacyAdbInstallationChannel(context)
            TRANSPORT_TLS -> WirelessTlsAdbInstallationChannel(context)
            else -> error("Unsupported validation transport: $transport")
        }
        val result = runBlocking {
            channel.install(
                InstallationRequest(
                    apk = apk,
                    packageName = packageName,
                    versionCode = versionCode,
                    mode = InstallationMode.BACKGROUND,
                )
            )
        }

        assertThat(result).isEqualTo(ChannelInstallationResult.Installed)
        assertThat(installedVersionCode(context, packageName)).isAtLeast(versionCode.toLong())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = context.packageManager.getInstallSourceInfo(packageName)
            Log.i(
                TAG,
                "transport=$transport package=$packageName " +
                    "installer=${source.installingPackageName} " +
                    "initiator=${source.initiatingPackageName} " +
                    "originator=${source.originatingPackageName}",
            )
            assertThat(source.initiatingPackageName).isEqualTo(ANDROID_SHELL_PACKAGE)
        }
    }

    private companion object {
        const val TAG = "SelfAdbValidation"
        const val ARG_ENABLED = "selfAdbValidation"
        const val ARG_PACKAGE_NAME = "selfAdbPackage"
        const val ARG_VERSION_CODE = "selfAdbVersionCode"
        const val ARG_TRANSPORT = "selfAdbTransport"
        const val FIXTURE_FILE_NAME = "self-adb-validation.apk"
        const val TRANSPORT_LEGACY = "legacy"
        const val TRANSPORT_TLS = "tls"
        const val ANDROID_SHELL_PACKAGE = "com.android.shell"
    }
}
