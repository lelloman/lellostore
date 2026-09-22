package com.lelloman.store.download

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.api.RemoteApiClient
import com.lelloman.store.domain.model.AppVersion
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import com.lelloman.store.domain.model.ApkAcquisition
import org.junit.rules.TemporaryFolder

class VerifiedApkProviderTest {
    @get:Rule val temp = TemporaryFolder()
    private val api = mockk<RemoteApiClient>()
    private val provider = VerifiedApkProvider(api)
    private val version = AppVersion(1, "1", 3, "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04", 24, Instant.fromEpochMilliseconds(0))

    @Before fun acquisitions() {
        coEvery { api.acquireApk("com.example", 1, any()) } answers {
            Result.success(ApkAcquisition("copy", "com.example", 1, version.size, version.sha256!!))
        }
    }

    @Test fun `verified destinations remain independently owned`() = runTest {
        coEvery { api.downloadAcquisition("copy") } answers { Result.success("apk".byteInputStream()) }
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
        coVerify(exactly = 0) { api.downloadAcquisition(any()) }
    }
    @Test fun `missing hash fails before download`() = runTest {
        val result = runCatching { provider.prepare("com.example", version.copy(sha256 = null), temp.newFile()) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { api.downloadAcquisition(any()) }
    }
    @Test fun `wrong hash short download and oversized download are rejected and removed`() = runTest {
        for (bytes in listOf("bad", "ap", "apkk")) {
            val file = temp.newFile()
            coEvery { api.downloadAcquisition("copy") } answers { Result.success(bytes.byteInputStream()) }
            assertThat(runCatching { provider.prepare("com.example", version, file) }.isFailure).isTrue()
            assertThat(file.exists()).isFalse()
        }
    }
    @Test fun `personalized metadata replaces the catalog artifact hash and size`() = runTest {
        val bytes = "personalized APK".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        coEvery { api.acquireApk(any(), any(), any()) } answers {
            Result.success(ApkAcquisition("personal", "com.example", 1, bytes.size.toLong(), hash))
        }
        coEvery { api.downloadAcquisition("personal") } answers { Result.success(bytes.inputStream()) }
        val file = temp.newFile()
        provider.prepare("com.example", version, file)
        assertThat(file.readBytes()).isEqualTo(bytes)
    }

    @Test fun `wrong acquisition identity fails before downloading`() = runTest {
        coEvery { api.acquireApk(any(), any(), any()) } answers {
            Result.success(ApkAcquisition("wrong", "another.app", 1, version.size, version.sha256!!))
        }
        assertThat(runCatching { provider.prepare("com.example", version, temp.newFile()) }.isFailure).isTrue()
        coVerify(exactly = 0) { api.downloadAcquisition(any()) }
    }

}
