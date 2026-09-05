import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val signingProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val recoveryAssets = layout.buildDirectory.dir("generated/recovery-assets")
val bundleRecoveryStoreApk by tasks.registering(Copy::class) {
    dependsOn(":app:assembleRelease")
    from(project(":app").layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(recoveryAssets)
    rename { "lellostore-recovery.apk" }
    doLast {
        val apk = recoveryAssets.get().file("lellostore-recovery.apk").asFile
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }
        recoveryAssets.get().file("lellostore-recovery.apk.sha256").asFile.writeText(digest)
    }
}

android {
    namespace = "com.lelloman.store.recovery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lelloman.store.recovery"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        if (signingProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    sourceSets["main"].assets.srcDir(recoveryAssets)
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    lint {
        warningsAsErrors = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

tasks.named("preBuild").configure { dependsOn(bundleRecoveryStoreApk) }

dependencies {
    implementation(project(":recovery-protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
