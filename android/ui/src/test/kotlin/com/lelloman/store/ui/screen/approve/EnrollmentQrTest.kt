package com.lelloman.store.ui.screen.approve

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric provides the real org.json implementation.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EnrollmentQrTest {

    private val store = "https://store.example.com"

    private fun parse(text: String) = EnrollmentQr.parse(text, store)

    @Test
    fun `the approval link a TV shows names its code`() {
        assertThat(parse("$store/admin/device-tokens/approve?code=WDJB-MJHT")).isEqualTo(ScannedEnrollment.Code("WDJB-MJHT"))
        assertThat(parse("https://Store.Example.com:443/admin/device-tokens/approve?code=wdjb-mjht"))
            .isEqualTo(ScannedEnrollment.Code("WDJB-MJHT"))
        assertThat(parse("$store/admin/device-tokens/approve?code=WDJB%20MJHT")).isEqualTo(ScannedEnrollment.Code("WDJB-MJHT"))
    }

    @Test
    fun `the JSON payload is accepted`() {
        val payload = """{"type":"device-enrollment","version":1,"server_url":"$store","user_code":"WDJB-MJHT"}"""
        assertThat(parse(payload)).isEqualTo(ScannedEnrollment.Code("WDJB-MJHT"))
    }

    @Test
    fun `a bare code is accepted`() {
        assertThat(parse(" wdjbmjht ")).isEqualTo(ScannedEnrollment.Code("WDJB-MJHT"))
    }

    @Test
    fun `codes of another store are recognised as such`() {
        assertThat(parse("https://other.example.com/admin/device-tokens/approve?code=WDJB-MJHT"))
            .isEqualTo(ScannedEnrollment.OtherStore("other.example.com"))
        val payload = """{"type":"device-enrollment","version":1,"server_url":"https://other.example.com","user_code":"WDJB-MJHT"}"""
        assertThat(parse(payload)).isEqualTo(ScannedEnrollment.OtherStore("other.example.com"))
    }

    @Test
    fun `anything else is invalid`() {
        val invalid = listOf(
            "",
            "hello",
            "http://store.example.com/admin/device-tokens/approve?code=WDJB-MJHT",
            "$store/admin/device-tokens?code=WDJB-MJHT",
            "$store/admin/device-tokens/approve",
            "$store/admin/device-tokens/approve?code=WDJB-MJHA",
            "$store/admin/device-tokens/approve?code=WDJB-MJHT&code=BCDF-GHJK",
            "$store/admin/device-tokens/approve?code=WDJB-MJHT#x",
            """{"type":"store-setup","version":1,"server_url":"$store"}""",
            """{"type":"device-enrollment","version":2,"server_url":"$store","user_code":"WDJB-MJHT"}""",
            """{"type":"device-enrollment","version":1,"server_url":"http://insecure","user_code":"WDJB-MJHT"}""",
            "{not json",
            "x".repeat(5000),
        )
        for (text in invalid) assertThat(parse(text)).isEqualTo(ScannedEnrollment.Invalid)
    }
}
