package com.lelloman.store.localdata.auth

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric provides the real org.json implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceTokenFileTest {

    @get:Rule val folder = TemporaryFolder()

    private fun json(
        version: Any = 1,
        serverUrl: String = "https://store.example.com",
        name: String = "Living room TV",
        token: String = SECRET,
    ) = JSONObject().put("version", version).put("server_url", serverUrl).put("name", name).put("token", token).toString()

    @Test
    fun `valid file is parsed and the server is normalized`() {
        val file = folder.newFile(DeviceTokenFile.FILE_NAME).apply { writeText(json(serverUrl = "https://Store.Example.com:443/")) }

        val result = DeviceTokenFile.read(file)

        assertThat(result).isEqualTo(
            DeviceTokenFile.Result.Valid(DeviceToken("Living room TV", "https://store.example.com", SECRET)),
        )
    }

    @Test
    fun `missing file is absent`() {
        assertThat(DeviceTokenFile.read(folder.root.resolve("missing.json"))).isEqualTo(DeviceTokenFile.Result.Absent)
    }

    @Test
    fun `unsupported version is invalid`() {
        assertThat(DeviceTokenFile.parse(json(version = 2))).isEqualTo(DeviceTokenFile.Result.Invalid("unsupported_version"))
    }

    @Test
    fun `http server url is invalid`() {
        assertThat(DeviceTokenFile.parse(json(serverUrl = "http://store.example.com")))
            .isEqualTo(DeviceTokenFile.Result.Invalid("invalid_server_url"))
    }

    @Test
    fun `token with the wrong prefix is invalid`() {
        assertThat(DeviceTokenFile.parse(json(token = "lellostore_pub_" + "a".repeat(64))))
            .isEqualTo(DeviceTokenFile.Result.Invalid("invalid_token"))
        assertThat(DeviceTokenFile.parse(json(token = "lellostore_dev_short")))
            .isEqualTo(DeviceTokenFile.Result.Invalid("invalid_token"))
    }

    @Test
    fun `blank name is invalid`() {
        assertThat(DeviceTokenFile.parse(json(name = "  "))).isEqualTo(DeviceTokenFile.Result.Invalid("invalid_name"))
    }

    @Test
    fun `malformed json is invalid`() {
        assertThat(DeviceTokenFile.parse("{not json")).isEqualTo(DeviceTokenFile.Result.Invalid("malformed"))
    }

    @Test
    fun `string form never contains the secret`() {
        assertThat(DeviceToken("TV", "https://store.example.com", SECRET).toString()).doesNotContain(SECRET)
    }

    @Test
    fun `serialized content matches the web UI download and parses back`() {
        val token = DeviceToken("Mi Box (living room)", "https://store.example.com", SECRET)

        val text = DeviceTokenFile.serialize(token)

        assertThat(text).isEqualTo(
            "{\"version\":1,\"server_url\":\"https://store.example.com\",\"name\":\"Mi Box (living room)\",\"token\":\"$SECRET\"}",
        )
        assertThat(DeviceTokenFile.parse(text)).isEqualTo(DeviceTokenFile.Result.Valid(token))
    }

    @Test
    fun `serialized names escape JSON specials`() {
        val token = DeviceToken("TV \"den\" \\ 1\n\u0001", "https://store.example.com", SECRET)

        val text = DeviceTokenFile.serialize(token)

        assertThat(text).contains("\"name\":\"TV \\\"den\\\" \\\\ 1\\n\\u0001\"")
        assertThat(JSONObject(text).getString("name")).isEqualTo(token.name)
    }

    companion object {
        val SECRET = "lellostore_dev_" + "0123456789abcdef".repeat(4)
    }
}
