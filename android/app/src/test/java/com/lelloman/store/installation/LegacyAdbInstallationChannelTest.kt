package com.lelloman.store.installation

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InputStream
import org.junit.Test

class LegacyAdbInstallationChannelTest {
    @Test
    fun `ADB install command permits upgrading an installed package`() {
        assertThat(adbInstallCommand(1234))
            .isEqualTo("exec:cmd package install -r -S 1234")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ADB install command rejects an empty APK`() {
        adbInstallCommand(0)
    }

    @Test
    fun `successful package manager response is installed`() {
        assertThat(parseAdbInstallResponse("Success"))
            .isEqualTo(ChannelInstallationResult.Installed)
    }

    @Test
    fun `package manager failure is definitive`() {
        val result = parseAdbInstallResponse("Failure [INSTALL_FAILED_INVALID_APK]")

        assertThat(result).isEqualTo(
            ChannelInstallationResult.Failed(
                reason = "Failure [INSTALL_FAILED_INVALID_APK]",
                canTryNextChannel = false,
            )
        )
    }

    @Test
    fun `empty package manager response is reported`() {
        assertThat(parseAdbInstallResponse(""))
            .isEqualTo(
                ChannelInstallationResult.Failed(
                    reason = "ADB package manager returned no result",
                    canTryNextChannel = false,
                )
            )
    }

    @Test
    fun `ADB text reader preserves payload when remote close is reported as exception`() {
        val input = object : InputStream() {
            private val payload = "uid=2000(shell)".toByteArray()
            private var consumed = false

            override fun read(): Int = error("Bulk read expected")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (consumed) throw IOException("Stream closed.")
                consumed = true
                payload.copyInto(buffer, offset)
                return payload.size
            }
        }

        assertThat(readAdbText(input)).isEqualTo("uid=2000(shell)")
    }
}
