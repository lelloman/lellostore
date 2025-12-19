# lellostore - Implementation Overview

This document outlines the implementation plan for lellostore, organized into sequential epics. Each epic represents a cohesive deliverable that can be developed, tested, and verified independently.

When an epic is started, a dedicated implementation plan file (`EPIC_XX_PLAN.md`) will be created with detailed tasks, acceptance criteria, and progress tracking.

## Epic Overview

| # | Epic | Status | Description | Dependencies |
|---|------|--------|-------------|--------------|
| 1 | Backend Foundation | Done | Rust/Axum project setup, database, basic API structure | None |
| 2 | APK Processing | Done | APK metadata extraction, file storage, AAB conversion | Epic 1 |
| 3 | Backend Authentication | Done | OIDC token validation, role-based access control | Epic 1 |
| 4 | Backend API Complete | Done | All REST endpoints fully functional | Epics 2, 3 |
| 5 | Frontend Foundation | Done | Vue 3 project setup, Vuetify, OIDC login | Epic 4 |
| 6 | Frontend Features | Done | Admin dashboard, upload, edit, delete functionality | Epic 5 |
| 7 | Frontend Embedding | Not Started | Bundle frontend into Rust binary | Epic 6 |
| 8 | Android Foundation | Not Started | Kotlin/Compose project setup, OIDC login | Epic 4 |
| 9 | Android Core Features | Not Started | App catalog, download, installation | Epic 8 |
| 10 | Android Update Detection | Not Started | Background polling, local notifications | Epic 9 |

**Status Legend**: Not Started | In Progress | Done

---

## Epic 1: Backend Foundation

**Goal**: Establish the Rust/Axum project with database connectivity and basic project structure.

### Tasks

1. **Project Setup**
   - Initialize Cargo workspace
   - Add dependencies: axum, tokio, sqlx, serde, tower-http
   - Configure SQLite with sqlx (compile-time checked queries)
   - Set up project structure:
     ```
     backend/
     ├── src/
     │   ├── main.rs
     │   ├── config.rs
     │   ├── db/
     │   │   ├── mod.rs
     │   │   └── models.rs
     │   ├── api/
     │   │   ├── mod.rs
     │   │   └── routes.rs
     │   └── services/
     │       └── mod.rs
     ├── migrations/
     └── Cargo.toml
     ```

2. **Database Setup**
   - Create SQLx migrations for `apps` and `app_versions` tables
   - Implement database models (structs with sqlx::FromRow)
   - Implement basic CRUD operations for apps and versions

3. **API Skeleton**
   - Set up Axum router with versioned API prefix (`/api`)
   - Implement health check endpoint (`GET /health`)
   - Configure CORS for development
   - Add request logging middleware (tower-http)

4. **Configuration**
   - Environment-based configuration (dotenv)
   - Config struct for: listen address, database path, storage path, OIDC settings

### Deliverable
A running Axum server that connects to SQLite, with health check endpoint and database migrations applied.

### Verification
- `cargo run` starts server on configured port
- `GET /health` returns 200 OK
- Database file created with correct schema

---

## Epic 2: APK Processing

**Goal**: Implement APK/AAB file handling, metadata extraction, and storage.

### Tasks

1. **File Storage Service**
   - Create storage directory structure:
     ```
     storage/
     ├── apks/
     │   └── {package_name}/
     │       └── {version_code}.apk
     └── icons/
         └── {package_name}.png
     ```
   - Implement file save/delete operations
   - Generate SHA-256 checksums on save

2. **APK Metadata Extraction**
   - Use `aapt2` or a Rust APK parsing library (e.g., `apk-parser`) to extract:
     - Package name
     - Version code
     - Version name
     - Min SDK version
     - App icon (largest resolution)
   - Handle extraction errors gracefully

3. **AAB to APK Conversion**
   - Shell out to `bundletool` for conversion:
     ```
     bundletool build-apks --bundle=input.aab --output=output.apks --mode=universal
     ```
   - Extract universal APK from the .apks archive
   - Clean up temporary files
   - Return meaningful errors if bundletool/Java not available

4. **Upload Processing Pipeline**
   - Accept multipart file upload
   - Detect file type (APK vs AAB by magic bytes or extension)
   - If AAB: convert to APK first
   - Extract metadata from APK
   - Store APK and icon
   - Calculate SHA-256
   - Insert/update database records

### Deliverable
A service that can process uploaded APK/AAB files, extract metadata, store files, and persist to database.

### Verification
- Unit tests for APK metadata extraction
- Integration test: upload APK → verify database records and stored files
- Integration test: upload AAB → verify conversion and storage

---

## Epic 3: Backend Authentication

**Goal**: Implement OIDC token validation and role-based access control.

### Tasks

1. **OIDC Configuration**
   - Config fields: issuer URL, expected audience, admin role claim name
   - Fetch OIDC discovery document (`.well-known/openid-configuration`)
   - Fetch and cache JWKS (JSON Web Key Set)
   - Implement JWKS refresh on signature validation failure

2. **Token Validation Middleware**
   - Extract Bearer token from Authorization header
   - Validate JWT signature using JWKS
   - Validate standard claims: exp, iat, iss, aud
   - Extract user info: subject, email, roles
   - Attach user context to request extensions

3. **Role-Based Access Control**
   - Define role extraction from token (configurable claim path)
   - Create middleware/extractor for admin-only routes
   - Return 403 Forbidden for non-admin users on admin endpoints

4. **Error Handling**
   - 401 Unauthorized: missing or invalid token
   - 403 Forbidden: valid token but insufficient permissions
   - Meaningful error messages (without leaking security details)

### Deliverable
Authentication middleware that validates OIDC tokens and enforces role-based access.

### Verification
- Unit tests for JWT validation logic
- Integration tests with mock OIDC provider
- Test: request without token → 401
- Test: request with invalid token → 401
- Test: request with valid user token to admin endpoint → 403
- Test: request with valid admin token → 200

---

## Epic 4: Backend API Complete

**Goal**: Implement all REST API endpoints with full functionality.

### Tasks

1. **User Endpoints**
   - `GET /api/apps` - List all apps with latest version info
   - `GET /api/apps/{packageName}` - Get app details with all versions
   - `GET /api/apps/{packageName}/icon` - Serve icon image
   - `GET /api/apps/{packageName}/versions/{versionCode}/apk` - Serve APK file
     - Support Range header for resumable downloads
     - Set appropriate Content-Type and Content-Disposition headers

2. **Admin Endpoints**
   - `POST /api/admin/apps` - Upload new app/version (multipart)
   - `PUT /api/admin/apps/{packageName}` - Update app metadata (JSON)
   - `DELETE /api/admin/apps/{packageName}` - Delete app and all versions
   - `DELETE /api/admin/apps/{packageName}/versions/{versionCode}` - Delete specific version
     - Auto-delete app if last version removed

3. **Response Formatting**
   - Consistent JSON response structure
   - Proper HTTP status codes
   - Error response format: `{ "error": "code", "message": "description" }`

4. **Input Validation**
   - Validate package name format
   - Validate version code is positive integer
   - Validate file size limits
   - Validate file types (APK/AAB only)

### Deliverable
Fully functional REST API matching the spec, with authentication enforced.

### Verification
- API tests for all endpoints (happy path and error cases)
- Test file upload with various APK sizes
- Test range requests for partial downloads
- Test concurrent uploads

---

## Epic 5: Frontend Foundation

**Goal**: Set up Vue 3 project with Vuetify and OIDC authentication.

### Tasks

1. **Project Setup**
   - Initialize Vite + Vue 3 + TypeScript project
   - Install and configure Vuetify 3
   - Install Pinia for state management
   - Install oidc-client-ts
   - Project structure:
     ```
     frontend/
     ├── src/
     │   ├── main.ts
     │   ├── App.vue
     │   ├── router/
     │   ├── stores/
     │   │   ├── auth.ts
     │   │   └── apps.ts
     │   ├── views/
     │   ├── components/
     │   └── services/
     │       ├── api.ts
     │       └── auth.ts
     ├── index.html
     └── vite.config.ts
     ```

2. **OIDC Authentication**
   - Configure oidc-client-ts with build-time settings
   - Implement login flow (redirect to OIDC provider)
   - Handle callback and token storage
   - Implement silent refresh
   - Implement logout

3. **Auth State Management**
   - Pinia store for auth state (user, tokens, loading)
   - Auth guard for protected routes
   - Automatic redirect to login if not authenticated

4. **API Client**
   - Axios or fetch wrapper with auth interceptor
   - Automatic token attachment to requests
   - Handle 401 responses (redirect to login)

5. **Base Layout**
   - App shell with Vuetify navigation
   - App bar with user info and logout button
   - Loading states

### Deliverable
Vue 3 app with working OIDC login, protected routes, and API client ready.

### Verification
- Login flow works end-to-end with OIDC provider
- Token refresh works before expiry
- Unauthenticated users redirected to login
- API calls include valid Bearer token

---

## Epic 6: Frontend Features

**Goal**: Implement the admin dashboard with full CRUD functionality.

### Tasks

1. **Apps List View**
   - Display all apps in a data table or card grid
   - Show: icon, name, package name, latest version, upload date
   - Search/filter functionality
   - Empty state when no apps

2. **App Detail View**
   - Show app metadata (name, description, package name)
   - List all versions with: version name, version code, size, upload date
   - Delete version button (with confirmation)
   - Delete app button (with confirmation)

3. **Upload Functionality**
   - Upload dialog/page with drag-and-drop zone
   - File type validation (APK/AAB only)
   - Optional name/description fields
   - Upload progress indicator
   - Success/error feedback

4. **Edit App Metadata**
   - Edit dialog for name and description
   - Form validation
   - Save/cancel actions

5. **Delete Functionality**
   - Confirmation dialogs for destructive actions
   - Handle deletion of app vs single version
   - Update UI after successful deletion

6. **Error Handling & Feedback**
   - Toast notifications for success/error
   - Loading states for all async operations
   - Graceful error display

### Deliverable
Fully functional admin dashboard for managing apps.

### Verification
- Can upload APK and see it in the list
- Can upload AAB and see converted APK in the list
- Can edit app name/description
- Can delete individual versions
- Can delete entire app
- All error cases show appropriate feedback

---

## Epic 7: Frontend Embedding

**Goal**: Bundle the Vue frontend into the Rust binary for single-binary deployment.

### Tasks

1. **Build Integration**
   - Add build script to compile frontend before Rust build
   - Use `rust-embed` or `include_dir` to embed `dist/` folder

2. **Static File Serving**
   - Serve embedded files at root path (`/`)
   - Serve `index.html` for SPA routes (fallback)
   - Set correct Content-Type headers
   - Enable gzip compression

3. **Build Configuration**
   - Production build optimizations for frontend
   - Single `cargo build --release` produces complete binary

### Deliverable
Single Rust binary that serves both API and frontend.

### Verification
- `cargo build --release` produces working binary
- Binary serves frontend at `/`
- API still accessible at `/api/*`
- SPA routing works (deep links)

---

## Epic 8: Android Foundation

**Goal**: Set up Android project with Jetpack Compose and OIDC authentication.

### Tasks

1. **Project Setup**
   - Create Android project (Kotlin, min SDK 26)
   - Configure Jetpack Compose
   - Add dependencies: Ktor Client, Room, DataStore, AppAuth
   - Project structure:
     ```
     android/
     ├── app/src/main/
     │   ├── java/com/lellostore/
     │   │   ├── MainActivity.kt
     │   │   ├── LellostoreApp.kt
     │   │   ├── ui/
     │   │   │   ├── screens/
     │   │   │   ├── components/
     │   │   │   └── theme/
     │   │   ├── data/
     │   │   │   ├── local/
     │   │   │   ├── remote/
     │   │   │   └── repository/
     │   │   └── auth/
     │   └── res/
     └── build.gradle.kts
     ```

2. **OIDC Authentication**
   - Configure AppAuth with build-time OIDC settings
   - Implement login flow (opens browser/custom tab)
   - Handle redirect callback
   - Store tokens in EncryptedSharedPreferences
   - Implement token refresh
   - Implement logout

3. **Navigation**
   - Compose Navigation setup
   - Auth-guarded navigation (redirect to login if not authenticated)
   - Screens: Login, App List, App Detail, Settings

4. **API Client**
   - Ktor Client configuration
   - Auth interceptor for Bearer token
   - Handle 401 responses
   - JSON serialization (kotlinx.serialization)

5. **Local Database**
   - Room database setup
   - Entities: CachedApp, CachedAppVersion, InstalledApp
   - DAOs for CRUD operations

6. **DataStore**
   - Server URL preference
   - Update check interval preference

### Deliverable
Android app with working OIDC login and basic navigation structure.

### Verification
- Login flow works with OIDC provider
- Tokens stored securely
- Token refresh works
- Logout clears tokens
- Navigation between screens works

---

## Epic 9: Android Core Features

**Goal**: Implement app catalog, download, and installation functionality.

### Tasks

1. **App List Screen**
   - Fetch and display apps from API
   - Cache apps in Room database
   - Pull-to-refresh
   - Search/filter functionality
   - Display: icon, name, version, update available indicator
   - Loading and error states

2. **App Detail Screen**
   - Display app info: icon, name, description, version history
   - Install/Update button (shows appropriate action)
   - Version selector (if multiple versions)
   - Show installed version vs available version

3. **Download Manager**
   - Download APK to cache directory
   - Show download progress (notification + in-app)
   - Support download cancellation
   - Resume interrupted downloads (Range header)
   - Verify SHA-256 checksum after download

4. **APK Installation**
   - Request REQUEST_INSTALL_PACKAGES permission if needed
   - Trigger package installer via FileProvider
   - Handle installation result (success/cancel/failure)
   - Clean up APK file after installation

5. **Installed App Tracking**
   - Query PackageManager for installed apps
   - Track which lellostore apps are installed and their versions
   - Detect app uninstallation

6. **Update Detection**
   - Compare installed version codes with server versions
   - Mark apps with available updates
   - "Updates Available" section/filter in app list

### Deliverable
Fully functional Android app for browsing, downloading, and installing apps.

### Verification
- App list loads from server
- App list works offline (from cache)
- Can download and install an APK
- Checksum verification works
- Download progress shown correctly
- Update available indicator shows correctly
- Can update an already installed app

---

## Epic 10: Android Update Detection

**Goal**: Implement background update checking with local notifications.

### Tasks

1. **WorkManager Setup**
   - Create periodic work request for update checks
   - Configurable interval (default: 24 hours)
   - Respect battery optimization / Doze mode
   - Run only on Wi-Fi (configurable)

2. **Update Check Worker**
   - Fetch app list from server
   - Compare with installed apps
   - Identify apps with available updates
   - Persist update info to database

3. **Notifications**
   - Create notification channel for updates
   - Request POST_NOTIFICATIONS permission (Android 13+)
   - Show notification when updates available
   - Notification tap opens app list (filtered to updates)
   - Group multiple updates into single notification

4. **Settings Screen**
   - Server URL configuration
   - Update check interval selection
   - Enable/disable background checks
   - Wi-Fi only toggle
   - Manual "Check for updates" button

5. **Edge Cases**
   - Handle server unreachable gracefully
   - Handle token expiry during background check
   - Don't notify for already-notified updates

### Deliverable
Background update detection with user notifications.

### Verification
- Background check runs at configured interval
- Notification shown when updates available
- Tapping notification opens app
- Settings changes take effect
- No notifications when no updates
- Works correctly after device restart

---

## Post-Implementation

### Documentation
- README with setup instructions
- API documentation (OpenAPI/Swagger)
- Deployment guide

### Testing
- Backend: unit tests, integration tests
- Frontend: component tests, E2E tests
- Android: unit tests, instrumented tests

### CI/CD
- GitHub Actions for builds
- Automated testing
- Release artifact generation
