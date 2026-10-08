import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val defaultServerUrl: String = localProperties.getProperty("default.server.url", "")

fun gitOutput(vararg arguments: String): String = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", *arguments)
}.standardOutput.asText.get().trim()

val explicitVersionCode = providers.gradleProperty("storeVersionCode").orNull
val storeCommitCount = explicitVersionCode?.toInt() ?: run {
    check(gitOutput("rev-parse", "--is-shallow-repository") == "false") {
        "Provide -PstoreVersionCode=<release-code> or fetch full Git history."
    }
    gitOutput("rev-list", "--count", "HEAD").toInt()
}
val storeVersionMajor = providers.gradleProperty("storeVersionMajor").get().toInt()
val storeVersionMinor = providers.gradleProperty("storeVersionMinor").get().toInt()
require(storeVersionMajor >= 0 && storeVersionMinor >= 0 && storeCommitCount > 0)

val signingProperties = Properties().apply {
    val signingPropertiesFile = rootProject.file("signing.properties")
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

val companionAssets = layout.buildDirectory.dir("generated/companion-assets")
val includeRecoveryCompanion = providers.gradleProperty("includeRecoveryCompanion")
    .map(String::toBooleanStrict).getOrElse(signingProperties.containsKey("storeFile"))
check(!includeRecoveryCompanion || signingProperties.containsKey("storeFile")) {
    "Bundled recovery requires signing.properties so the app and companion share a release key."
}
val bundleRecoveryCompanion by tasks.registering(Sync::class) {
    dependsOn(":recovery:assembleRelease")
    from(project(":recovery").layout.buildDirectory.file("outputs/apk/release/recovery-release.apk"))
    into(companionAssets)
    rename { "lellostore-companion.apk" }
}

android {
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        warningsAsErrors = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
    namespace = "com.lelloman.store"
    compileSdk = 36
    testBuildType = providers.gradleProperty("deviceTestBuildType").orElse("debug").get()

    defaultConfig {
        applicationId = "com.lelloman.store"
        minSdk = 24
        targetSdk = 36
        versionCode = storeCommitCount
        versionName = providers.gradleProperty("storeVersionName")
            .orElse("$storeVersionMajor.$storeVersionMinor.$storeCommitCount").get()

        testInstrumentationRunner = "com.lelloman.store.HiltTestRunner"

        manifestPlaceholders["appAuthRedirectScheme"] = "com.lelloman.store"

        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
        buildConfigField("boolean", "RECOVERY_COMPANION_INCLUDED", includeRecoveryCompanion.toString())
    }
    signingConfigs {
        getByName("debug") {
            // Uses the default debug keystore.
        }
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
            // Keep experimental builds separate from the installed LelloStore app.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("releaseDebugSigned") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (includeRecoveryCompanion) sourceSets["main"].assets.srcDir(companionAssets)
}

if (includeRecoveryCompanion) tasks.named("preBuild").configure { dependsOn(bundleRecoveryCompanion) }

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.ktx)
    implementation(project(":paravoid-update-ipc"))
    // Modules
    implementation(project(":ui"))
    implementation(project(":domain"))
    implementation(project(":localdata"))
    implementation(project(":remoteapi"))
    implementation(project(":logger"))
    implementation(project(":recovery-protocol"))
    implementation(project(":remote-adb"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // AppAuth
    implementation(libs.appauth)

    // OkHttp (for Coil image loading with auth)
    implementation(libs.okhttp)

    // Self-ADB installation through the conventional local TCP endpoint.
    implementation(libs.libadb.android)
    // libadb detects this provider for TLS pairing without Android hidden APIs.
    implementation(libs.conscrypt.android)
    implementation(libs.sun.security.android)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Unit Tests
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)

    // Android Tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Module dependencies for instrumented tests
    androidTestImplementation(project(":localdata"))
    androidTestImplementation(project(":remoteapi"))
    androidTestImplementation(project(":domain"))
    androidTestImplementation(project(":ui"))
    // Additional dependencies for E2E test modules
    androidTestImplementation(libs.androidx.datastore.preferences)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestImplementation(libs.ktor.client.content.negotiation)
    androidTestImplementation(libs.ktor.serialization.kotlinx.json)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Robolectric supplies the desktop Conscrypt provider. The Android JNI provider
// from remote-adb must not shadow it in local JVM tests.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}
