package com.lelloman.store.download

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.model.AppVersion
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedApkProviderTest {
    @get:Rule val temp = TemporaryFolder()
    private val api = mockk<RemoteApiClient>()
    private val provider = VerifiedApkProvider(api)
    private val version = AppVersion(1, "1", 3, "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04", 24, Instant.fromEpochMilliseconds(0))

    @Test fun `verified destinations remain independently owned`() = runTest {
        coEvery { api.downloadApk("com.example", 1) } answers { Result.success("apk".byteInputStream()) }
        val local = temp.newFile()
        val remote = temp.newFile()
        provider.prepare("com.example", version, local)
        provider.prepare("com.example", version, remote)
        remote.delete()
        assertThat(local.readText()).isEqualTo("apk")
    }
    @Test fun `verified cache hit does not fetch again`() = runTest {
        val file = temp.newFile().apply { writeText("apk") }
        provider.prepare("com.example", version, file)
        coVerify(exactly = 0) { api.downloadApk(any(), any()) }
    }
    @Test fun `missing hash fails before download`() = runTest {
        val result = runCatching { provider.prepare("com.example", version.copy(sha256 = null), temp.newFile()) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { api.downloadApk(any(), any()) }
    }
    @Test fun `wrong hash short download and oversized download are rejected and removed`() = runTest {
        for (bytes in listOf("bad", "ap", "apkk")) {
            val file = temp.newFile()
            coEvery { api.downloadApk("com.example", 1) } answers { Result.success(bytes.byteInputStream()) }
            assertThat(runCatching { provider.prepare("com.example", version, file) }.isFailure).isTrue()
            assertThat(file.exists()).isFalse()
        }
    }
}
