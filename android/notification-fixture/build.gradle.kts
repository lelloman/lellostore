plugins {
    alias(libs.plugins.android.application)
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

    }
    sourceSets.getByName("test").resources.srcDir("../../backend/tests/fixtures/unifiedpush")
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies {
    testImplementation(libs.junit)
    testImplementation("com.google.crypto.tink:tink:1.23.0")
    implementation("org.unifiedpush.android:connector:3.3.5")
}
