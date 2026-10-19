package com.lelloman.store.installation

import android.content.ContentResolver
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

class WirelessDebuggingSettingTest {

    private val resolver = mockk<ContentResolver>()

    @Test
    fun `setting 0 on Android 11+ means off`() {
        assertThat(check(sdkInt = 30, value = 0)).isTrue()
        assertThat(check(sdkInt = 36, value = 0)).isTrue()
    }

    @Test
    fun `setting 1 means discovery should run`() {
        assertThat(check(sdkInt = 30, value = 1)).isFalse()
    }

    @Test
    fun `missing setting falls back to the default of 0`() {
        var requested: Pair<String, Int>? = null
        val off = isWirelessDebuggingKnownOff(resolver, 33) { _, name, default ->
            requested = name to default
            default
        }
        assertThat(off).isTrue()
        assertThat(requested).isEqualTo("adb_wifi_enabled" to 0)
    }

    @Test
    fun `before Android 11 the setting is not read and discovery runs`() {
        val off = isWirelessDebuggingKnownOff(resolver, 29) { _, _, _ -> error("must not read") }
        assertThat(off).isFalse()
    }

    @Test
    fun `unreadable setting is treated as unknown`() {
        val off = isWirelessDebuggingKnownOff(resolver, 33) { _, _, _ -> throw SecurityException() }
        assertThat(off).isFalse()
    }

    private fun check(sdkInt: Int, value: Int) =
        isWirelessDebuggingKnownOff(resolver, sdkInt) { _, _, _ -> value }
}
