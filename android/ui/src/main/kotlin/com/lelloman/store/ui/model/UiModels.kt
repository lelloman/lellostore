package com.lelloman.store.ui.model

/**
 * UI-specific models that mirror domain models.
 * These decouple the UI module from the domain module.
 */

// Theme
enum class ThemeMode {
    System,
    Light,
    Dark
}

// Auth
sealed interface AuthState {
    data object Loading : AuthState
    data object NotAuthenticated : AuthState
    data class Authenticated(val userEmail: String) : AuthState
}

sealed interface AuthResult {
    data object Success : AuthResult
    data object Cancelled : AuthResult
    data class Error(val message: String) : AuthResult
}

// Config
sealed interface SetServerUrlResult {
    data object Success : SetServerUrlResult
    data object InvalidUrl : SetServerUrlResult
}

// Apps
data class AppModel(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val latestVersionCode: Int,
    val latestVersionName: String,
)

data class InstalledAppModel(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
)

data class AppDetailModel(
    val packageName: String,
    val name: String,
    val description: String?,
    val iconUrl: String,
    val versions: List<AppVersionModel>,
)

data class AppVersionModel(
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val uploadedAtMillis: Long,
)
