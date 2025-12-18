# lellostore - Project Specification

## Overview

lellostore is a private system for distributing Android applications to authorized users. It consists of three components:

1. **Backend** - Rust/Axum API server that hosts APK files and manages app metadata
2. **Frontend** - Vue 3 SPA for administrators to manage apps
3. **Android App** - Client application for browsing, downloading, and installing apps

All access requires authentication via OIDC.

## Goals

1. Provide a simple, user-friendly interface for browsing and installing APKs
2. Detect and notify users of available app updates
3. Ensure secure APK distribution with integrity verification
4. Restrict access to authorized users only

## Target Users

- **Admins**: Upload and manage applications via the web frontend
- **End Users**: Install and update applications via the Android app

## Core Features

### Backend (Rust/Axum)

1. **App Hosting**
   - Store and serve APK files
   - Manage app metadata (name, description, version, icon)
   - Expose REST API for Android client and admin frontend
   - Serve the Vue 3 frontend embedded as static files (single binary deployment)

2. **App Management**
   - Upload APK or AAB files
   - Convert AAB to universal APK using bundletool
   - Extract metadata from APK (package name, version, icon, minSdk)
   - Update app information
   - Remove apps from the catalog

3. **Authentication & Authorization**
   - Validate OIDC access tokens on all API requests
   - Role-based access control (admin vs regular user)
   - Admin role required for upload/edit/delete operations

### Frontend (Vue 3 SPA)

1. **Admin Dashboard**
   - View all apps in the catalog
   - Upload new APKs or AABs
   - Edit app metadata (name, description)
   - Delete apps or specific versions

2. **Authentication**
   - OIDC login flow (Authorization Code + PKCE)
   - Admin-only access

### Android App

1. **Authentication**
   - OIDC login flow via AppAuth (Authorization Code + PKCE)
   - Secure token storage (EncryptedSharedPreferences)
   - Automatic token refresh

2. **App Catalog**
   - Browse available applications
   - View app details (name, description, version, size, icon)
   - Search and filter apps
   - Pull-to-refresh

3. **APK Installation**
   - Download APKs from the server
   - Verify SHA-256 checksum before installation
   - Trigger Android's package installer
   - Track download progress

4. **Update Detection**
   - Compare installed app versions with server versions
   - Display available updates
   - One-tap update installation
   - Background polling for updates (configurable interval)
   - Local notification when updates are available

## Technical Architecture

### Backend

- **Language**: Rust
- **Framework**: Axum
- **Database**: SQLite (via sqlx)
- **File Storage**: Local filesystem
- **AAB Processing**: bundletool (requires Java runtime)

### Frontend

- **Framework**: Vue 3 (Composition API)
- **Build Tool**: Vite
- **UI Library**: Vuetify 3
- **State Management**: Pinia
- **OIDC Client**: oidc-client-ts
- **OIDC Configuration**: Build-time (issuer URL, client ID baked into bundle)

### Android App

- **Minimum SDK**: 26 (Android 8.0)
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Networking**: Ktor Client
- **Auth**: AppAuth for Android
- **Local Database**: Room
- **Preferences**: DataStore
- **OIDC Configuration**: Build-time (issuer URL, client ID)
- **Server URL**: Default at build-time, user-configurable at runtime

### Architecture Diagram

```
                                    ┌──────────────┐
                                    │    OIDC      │
                                    │   Provider   │
                                    └──────┬───────┘
                                           │
              ┌────────────────────────────┼────────────────────────────┐
              │                            │                            │
              ▼                            ▼                            ▼
┌────────────────────┐         ┌────────────────────┐         ┌────────────────────┐
│   Android App      │         │      Backend       │         │     Frontend       │
│                    │         │    (Rust/Axum)     │         │    (Vue 3 SPA)     │
│  ┌──────────────┐  │         │                    │         │                    │
│  │   Compose UI │  │  HTTPS  │  ┌──────────────┐  │  HTTPS  │  ┌──────────────┐  │
│  └──────────────┘  │◄───────►│  │   REST API   │  │◄───────►│  │  Vuetify UI  │  │
│  ┌──────────────┐  │         │  └──────────────┘  │         │  └──────────────┘  │
│  │  ViewModels  │  │         │  ┌──────────────┐  │         │  ┌──────────────┐  │
│  └──────────────┘  │         │  │   Services   │  │         │  │    Pinia     │  │
│  ┌──────────────┐  │         │  └──────────────┘  │         │  └──────────────┘  │
│  │ Repositories │  │         │  ┌──────────────┐  │         └────────────────────┘
│  └──────────────┘  │         │  │   Storage    │  │
│  ┌──────────────┐  │         │  │ (SQLite + FS)│  │
│  │  Room + Ktor │  │         │  └──────────────┘  │
│  └──────────────┘  │         └────────────────────┘
└────────────────────┘
```

### API Contract

All `/api/*` endpoints require a valid Bearer token (OIDC access token).

#### Apps (User)

##### GET /api/apps
Returns list of all available apps.

```json
{
  "apps": [
    {
      "packageName": "com.example.app",
      "name": "Example App",
      "description": "An example application",
      "iconUrl": "/api/apps/com.example.app/icon",
      "latestVersion": {
        "versionCode": 10,
        "versionName": "1.0.0",
        "size": 5242880
      }
    }
  ]
}
```

##### GET /api/apps/{packageName}
Returns detailed information about a specific app.

```json
{
  "packageName": "com.example.app",
  "name": "Example App",
  "description": "An example application",
  "iconUrl": "/api/apps/com.example.app/icon",
  "versions": [
    {
      "versionCode": 10,
      "versionName": "1.0.0",
      "apkUrl": "/api/apps/com.example.app/versions/10/apk",
      "size": 5242880,
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "minSdk": 26,
      "uploadedAt": "2025-12-18T10:00:00Z"
    }
  ]
}
```

##### GET /api/apps/{packageName}/icon
Returns the app icon image (PNG).

##### GET /api/apps/{packageName}/versions/{versionCode}/apk
Downloads the APK file. Supports range requests for resumable downloads.

#### Apps (Admin)

Admin endpoints require the user to have an admin role claim in their OIDC token.

##### POST /api/admin/apps
Upload a new app or new version. Accepts multipart form data with APK or AAB file.
- AAB files are converted to universal APK on upload
- Metadata (packageName, versionCode, versionName, minSdk, icon) is extracted automatically
- Optional fields: `name`, `description` (can override extracted/default values)
- If app exists: adds new version, inherits existing name/description unless overridden
- If version already exists: returns error (delete first to re-upload)

##### PUT /api/admin/apps/{packageName}
Update app metadata (name, description).

```json
{
  "name": "New App Name",
  "description": "Updated description"
}
```

##### DELETE /api/admin/apps/{packageName}
Remove an app and all its versions from the catalog.

##### DELETE /api/admin/apps/{packageName}/versions/{versionCode}
Remove a specific version of an app.

### Database Schema

```sql
CREATE TABLE apps (
    package_name TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    icon_path TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE app_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL REFERENCES apps(package_name) ON DELETE CASCADE,
    version_code INTEGER NOT NULL,
    version_name TEXT NOT NULL,
    apk_path TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    min_sdk INTEGER NOT NULL,
    uploaded_at TEXT NOT NULL,
    UNIQUE(package_name, version_code)
);
```

### Android App Data Storage

- **Room Database**: Cache app catalog, track installed app versions
- **EncryptedSharedPreferences**: OIDC tokens (access token, refresh token)
- **DataStore**: Server URL, user preferences
- **Cache Directory**: Downloaded APKs (cleaned after installation)

### Permissions Required (Android)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Non-Functional Requirements

### Security
- HTTPS required for all communication
- OIDC token validation on every API request
- SHA-256 checksum verification for APK downloads
- Secure token storage on Android (EncryptedSharedPreferences)
- Admin operations require admin role in OIDC token

### Performance
- App catalog API response < 500ms
- Support resumable downloads for large APKs

### Usability
- Material Design 3 / Material You on Android
- Vuetify Material Design on web
- Support for light/dark themes on both platforms

## Out of Scope

- iOS client
- Public/unauthenticated access
- APK signing or modification
- Delta/patch updates
- Per-user app visibility (all authenticated users see all apps)
- Push notifications via Firebase/FCM (uses local notifications from HTTP polling instead)

## Open Questions

None - all technical decisions have been made.

---

## Revision History

| Version | Date       | Changes                                              |
|---------|------------|------------------------------------------------------|
| 0.1     | 2025-12-18 | Initial draft                                        |
| 0.2     | 2025-12-18 | Added server component, focused scope                |
| 0.3     | 2025-12-18 | Rust/Axum + Vue3, OIDC auth, AAB support             |
| 0.4     | 2025-12-18 | Finalized tech stack: SQLite, Vuetify 3              |
| 1.0     | 2025-12-18 | Final review: added DB schema, clarified auth flows  |
| 1.1     | 2025-12-18 | Added background polling, clarified OIDC config, upload behavior |
