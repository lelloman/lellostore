plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.lelloman.store.notificationfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.lelloman.store.notificationfixture"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        val pin = providers.gradleProperty("fixtureStoreCertificate").orElse("0".repeat(64)).get()
        require(pin.matches(Regex("[0-9a-f]{64}")))
        buildConfigField("String", "STORE_CERTIFICATE", "\"$pin\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
}
dependencies {
    implementation(project(":notification-client"))
    implementation(libs.kotlinx.coroutines.core)
}
