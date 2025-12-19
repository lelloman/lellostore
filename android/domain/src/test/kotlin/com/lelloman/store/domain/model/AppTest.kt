package com.lelloman.store.domain.model

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

class AppTest {

    @Test
    fun `App data class holds correct values`() {
        val version = AppVersion(
            versionCode = 10,
            versionName = "1.0.0",
            size = 1024 * 1024L,
            sha256 = "abc123",
            minSdk = 24,
            uploadedAt = Instant.parse("2024-01-15T10:30:00Z")
        )

        val app = App(
            packageName = "com.example.app",
            name = "Example App",
            description = "An example application",
            iconUrl = "https://example.com/icon.png",
            latestVersion = version
        )

        assertThat(app.packageName).isEqualTo("com.example.app")
        assertThat(app.name).isEqualTo("Example App")
        assertThat(app.description).isEqualTo("An example application")
        assertThat(app.iconUrl).isEqualTo("https://example.com/icon.png")
        assertThat(app.latestVersion).isEqualTo(version)
    }

    @Test
    fun `App can have null description`() {
        val version = AppVersion(
            versionCode = 1,
            versionName = "0.1.0",
            size = 512L,
            sha256 = "def456",
            minSdk = 21,
            uploadedAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        val app = App(
            packageName = "com.example.minimal",
            name = "Minimal App",
            description = null,
            iconUrl = "https://example.com/minimal.png",
            latestVersion = version
        )

        assertThat(app.description).isNull()
    }

    @Test
    fun `AppDetail can hold multiple versions`() {
        val versions = listOf(
            AppVersion(
                versionCode = 3,
                versionName = "1.2.0",
                size = 2048L,
                sha256 = "sha3",
                minSdk = 24,
                uploadedAt = Instant.parse("2024-03-01T00:00:00Z")
            ),
            AppVersion(
                versionCode = 2,
                versionName = "1.1.0",
                size = 1536L,
                sha256 = "sha2",
                minSdk = 24,
                uploadedAt = Instant.parse("2024-02-01T00:00:00Z")
            ),
            AppVersion(
                versionCode = 1,
                versionName = "1.0.0",
                size = 1024L,
                sha256 = "sha1",
                minSdk = 21,
                uploadedAt = Instant.parse("2024-01-01T00:00:00Z")
            )
        )

        val appDetail = AppDetail(
            packageName = "com.example.versioned",
            name = "Versioned App",
            description = "App with multiple versions",
            iconUrl = "https://example.com/versioned.png",
            versions = versions
        )

        assertThat(appDetail.versions).hasSize(3)
        assertThat(appDetail.versions.first().versionCode).isEqualTo(3)
    }

    @Test
    fun `InstalledApp holds installation info`() {
        val installedApp = InstalledApp(
            packageName = "com.example.installed",
            versionCode = 5,
            versionName = "2.0.1"
        )

        assertThat(installedApp.packageName).isEqualTo("com.example.installed")
        assertThat(installedApp.versionCode).isEqualTo(5)
        assertThat(installedApp.versionName).isEqualTo("2.0.1")
    }

    @Test
    fun `AvailableUpdate links app with installed version`() {
        val app = App(
            packageName = "com.example.updatable",
            name = "Updatable App",
            description = null,
            iconUrl = "https://example.com/updatable.png",
            latestVersion = AppVersion(
                versionCode = 10,
                versionName = "3.0.0",
                size = 4096L,
                sha256 = "newsha",
                minSdk = 24,
                uploadedAt = Instant.parse("2024-06-01T00:00:00Z")
            )
        )

        val update = AvailableUpdate(
            app = app,
            installedVersionCode = 8,
            installedVersionName = "2.5.0"
        )

        assertThat(update.app.latestVersion.versionCode).isGreaterThan(update.installedVersionCode)
        assertThat(update.installedVersionName).isEqualTo("2.5.0")
    }
}
