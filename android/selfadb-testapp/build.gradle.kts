plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lelloman.store.selfadbtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lelloman.store.selfadbtest"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}
