package com.lelloman.store.remoteapi.dto

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

class DtoMappingTest {

    @Test
    fun `AppVersionDto toDomain maps correctly`() {
        val dto = AppVersionDto(
            versionCode = 10,
            versionName = "2.5.0",
            size = 5120000,
            sha256 = "abc123def456",
            minSdk = 26,
            uploadedAt = 1700000000000,
        )

        val domain = dto.toDomain()

        assertThat(domain.versionCode).isEqualTo(10)
        assertThat(domain.versionName).isEqualTo("2.5.0")
        assertThat(domain.size).isEqualTo(5120000)
        assertThat(domain.sha256).isEqualTo("abc123def456")
        assertThat(domain.minSdk).isEqualTo(26)
        assertThat(domain.uploadedAt).isEqualTo(Instant.fromEpochMilliseconds(1700000000000))
    }

    @Test
    fun `AppDto toDomain maps correctly`() {
        val dto = AppDto(
            packageName = "com.example.app",
            name = "Example App",
            description = "An example application",
            iconUrl = "https://example.com/icon.png",
            latestVersion = AppVersionDto(
                versionCode = 5,
                versionName = "1.0.0",
                size = 1024000,
                sha256 = "hash123",
                minSdk = 24,
                uploadedAt = 1700000000000,
            ),
        )

        val domain = dto.toDomain()

        assertThat(domain.packageName).isEqualTo("com.example.app")
        assertThat(domain.name).isEqualTo("Example App")
        assertThat(domain.description).isEqualTo("An example application")
        assertThat(domain.iconUrl).isEqualTo("https://example.com/icon.png")
        assertThat(domain.latestVersion.versionCode).isEqualTo(5)
        assertThat(domain.latestVersion.versionName).isEqualTo("1.0.0")
    }

    @Test
    fun `AppDto toDomain handles null description`() {
        val dto = AppDto(
            packageName = "com.example.app",
            name = "Example App",
            description = null,
            iconUrl = "https://example.com/icon.png",
            latestVersion = AppVersionDto(
                versionCode = 1,
                versionName = "1.0.0",
                size = 1024000,
                sha256 = "hash123",
                minSdk = 24,
                uploadedAt = 1700000000000,
            ),
        )

        val domain = dto.toDomain()

        assertThat(domain.description).isNull()
    }

    @Test
    fun `AppDetailDto toDomain maps correctly`() {
        val dto = AppDetailDto(
            packageName = "com.example.app",
            name = "Example App",
            description = "An example application",
            iconUrl = "https://example.com/icon.png",
            versions = listOf(
                AppVersionDto(
                    versionCode = 3,
                    versionName = "1.2.0",
                    size = 3072000,
                    sha256 = "hash3",
                    minSdk = 24,
                    uploadedAt = 1700200000000,
                ),
                AppVersionDto(
                    versionCode = 2,
                    versionName = "1.1.0",
                    size = 2560000,
                    sha256 = "hash2",
                    minSdk = 24,
                    uploadedAt = 1700100000000,
                ),
            ),
        )

        val domain = dto.toDomain()

        assertThat(domain.packageName).isEqualTo("com.example.app")
        assertThat(domain.name).isEqualTo("Example App")
        assertThat(domain.description).isEqualTo("An example application")
        assertThat(domain.iconUrl).isEqualTo("https://example.com/icon.png")
        assertThat(domain.versions).hasSize(2)
        assertThat(domain.versions[0].versionCode).isEqualTo(3)
        assertThat(domain.versions[1].versionCode).isEqualTo(2)
    }

    @Test
    fun `AppDetailDto toDomain handles empty versions list`() {
        val dto = AppDetailDto(
            packageName = "com.example.app",
            name = "Example App",
            description = null,
            iconUrl = "https://example.com/icon.png",
            versions = emptyList(),
        )

        val domain = dto.toDomain()

        assertThat(domain.versions).isEmpty()
    }
}
