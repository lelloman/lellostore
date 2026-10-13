package com.lelloman.store.installation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SilentPackageInstallerChannelTest {

    @Test
    fun `update of an app LelloStore installed is eligible`() {
        assertThat(ineligibility()).isNull()
    }

    @Test
    fun `LelloStore updating itself is eligible without being its own installer`() {
        assertThat(ineligibility(targetPackage = OWN, installerOfRecord = null)).isNull()
    }

    @Test
    fun `app installed by another installer is not eligible`() {
        assertThat(ineligibility(installerOfRecord = null))
            .isEqualTo("LelloStore is not the installer of record (installer: none)")
        assertThat(ineligibility(installerOfRecord = "com.android.vending")).isNotNull()
    }

    @Test
    fun `first install is not eligible`() {
        assertThat(ineligibility(installedVersionCode = -1)).isEqualTo("First installs require confirmation")
    }

    @Test
    fun `old Android, missing permission and unreadable APK are not eligible`() {
        assertThat(ineligibility(sdkInt = 30)).isNotNull()
        assertThat(ineligibility(canRequestInstalls = false)).isNotNull()
        assertThat(ineligibility(apkTargetSdk = null)).isNotNull()
    }

    @Test
    fun `APK below the target SDK floor is not eligible`() {
        assertThat(ineligibility(sdkInt = 36, apkTargetSdk = 33)).isNotNull()
        assertThat(ineligibility(sdkInt = 36, apkTargetSdk = 34)).isNull()
    }

    @Test
    fun `target SDK floor follows the documented table`() {
        assertThat((31..38).map(::minimumTargetSdkForSilentUpdate))
            .containsExactly(29, 29, 30, 31, 33, 34, 35, 35).inOrder()
    }

    private fun ineligibility(
        sdkInt: Int = 36,
        canRequestInstalls: Boolean = true,
        targetPackage: String = "com.example.app",
        installedVersionCode: Long = 1,
        installerOfRecord: String? = OWN,
        apkTargetSdk: Int? = 36,
    ) = silentInstallIneligibility(
        sdkInt = sdkInt,
        canRequestInstalls = canRequestInstalls,
        ownPackage = OWN,
        targetPackage = targetPackage,
        installedVersionCode = installedVersionCode,
        installerOfRecord = installerOfRecord,
        apkTargetSdk = apkTargetSdk,
    )

    private companion object {
        const val OWN = "com.lelloman.store"
    }
}
