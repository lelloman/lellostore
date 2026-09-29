plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}
android {
    namespace = "com.lelloman.store.notifications.client"
    compileSdk = 36
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlinOptions { jvmTarget = "11" }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { warningsAsErrors = true; disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi") }
}
dependencies {
    api(project(":notification-protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") { from(components["release"]); groupId = "com.lelloman.store"; artifactId = "notification-client"; version = "0.1.0" } }
        repositories { maven { url = uri(rootProject.layout.buildDirectory.dir("notification-repository")) } }
    }
}
