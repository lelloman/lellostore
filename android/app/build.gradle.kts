import java.net.URI
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

// The issuer is discovered from default.server.url at login. App Links additionally
// require a host known at build time. Debug opt-in must supply a separate issuer.
val brokerDevIssuer = providers.gradleProperty("brokerDevIssuer").orElse(localProperties.getProperty("broker.dev.issuer", "")).get()
val brokerDebugEnabled = providers.gradleProperty("brokerEnabled").orElse(localProperties.getProperty("broker.enabled", "false")).get().toBooleanStrict()
require(brokerDevIssuer.isEmpty() || brokerDevIssuer.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?")))
check(!brokerDebugEnabled || (URI(brokerDevIssuer).let {
    it.scheme == "https" && !it.host.isNullOrBlank() && !it.host.equals("auth.lelloman.com", ignoreCase = true) && it.rawPath.isNullOrEmpty() &&
        it.rawQuery == null && it.rawFragment == null && it.rawUserInfo == null
})) { "Broker debug opt-in requires a separate HTTPS broker.dev.issuer" }

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
        minSdk = 30
        targetSdk = 36
        versionCode = storeCommitCount
        versionName = providers.gradleProperty("storeVersionName")
            .orElse("$storeVersionMajor.$storeVersionMinor.$storeCommitCount").get()

        testInstrumentationRunner = "com.lelloman.store.HiltTestRunner"

        manifestPlaceholders["appAuthRedirectScheme"] = "com.lelloman.store"
        manifestPlaceholders["authHost"] = "auth.lelloman.com"
        manifestPlaceholders["brokerEnabled"] = "false"
        buildConfigField("boolean", "SIGN_IN_BROKER_ENABLED", "false")
        buildConfigField("String", "SIGN_IN_BROKER_ISSUER", "\"https://auth.lelloman.com\"")

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
            manifestPlaceholders["authHost"] = if (brokerDebugEnabled) URI(brokerDevIssuer).host else "unconfigured.invalid"
            manifestPlaceholders["brokerEnabled"] = brokerDebugEnabled.toString()
            buildConfigField("boolean", "SIGN_IN_BROKER_ENABLED", brokerDebugEnabled.toString())
            buildConfigField("String", "SIGN_IN_BROKER_ISSUER", "\"$brokerDevIssuer\"")
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
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("com.lelloman.auth:broker:source")
    implementation("com.lelloman.auth:localdata:source")
    implementation("com.lelloman.auth:domain:source")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.ktx)
    implementation(project(":paravoid-update-ipc"))
    // Modules
    implementation(project(":ui"))
    implementation(project(":domain"))
    implementation(project(":localdata"))
    implementation(project(":remoteapi"))
    implementation(project(":logger"))
    // Androidoscopy diagnostic sessions: release builds expose tools only after an
    // explicit, approved session. Pinned to an androidoscopy commit built by JitPack.
    implementation("com.github.lelloman.androidoscopy:sdk:c6b4ae3698")
    implementation("com.github.lelloman.androidoscopy:sdk-ui:c6b4ae3698")
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
    androidTestImplementation("com.lelloman.auth:remoteapi:source")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
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

// An explicitly supplied synthetic fixture CA is trusted only by debug builds.
val brokerFixtureCa = providers.gradleProperty("brokerFixtureCaPath")
val brokerFixtureResources = layout.buildDirectory.dir("generated/brokerFixtureNetwork")
val generateBrokerFixtureNetwork by tasks.registering {
    inputs.property("caPath", brokerFixtureCa.orElse(""))
    if (brokerFixtureCa.isPresent) inputs.file(brokerFixtureCa)
    outputs.dir(brokerFixtureResources)
    doLast {
        val root = brokerFixtureResources.get().asFile
        root.deleteRecursively()
        if (brokerFixtureCa.isPresent) {
            val certificate = file(brokerFixtureCa.get()).readBytes()
            require(certificate.size <= 65536)
            root.resolve("raw").mkdirs()
            root.resolve("raw/broker_fixture_ca.pem").writeBytes(certificate)
            root.resolve("xml").mkdirs()
            root.resolve("xml/broker_fixture_network.xml").writeText("""
                <network-security-config>
                    <base-config cleartextTrafficPermitted="true"><trust-anchors><certificates src="system" /></trust-anchors></base-config>
                    <debug-overrides><trust-anchors><certificates src="@raw/broker_fixture_ca" /></trust-anchors></debug-overrides>
                </network-security-config>
            """.trimIndent())
        }
    }
}
android.sourceSets.getByName("debug").res.srcDir(brokerFixtureResources)
// A resource alias lets ordinary debug builds keep their existing network policy.
android.defaultConfig.manifestPlaceholders["brokerNetworkConfig"] = "@null"
if (brokerFixtureCa.isPresent) android.buildTypes.getByName("debug").manifestPlaceholders["brokerNetworkConfig"] = "@xml/broker_fixture_network"
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(generateBrokerFixtureNetwork) }
