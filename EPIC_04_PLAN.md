# Epic 4: Backend API Complete - Implementation Plan

## Overview

**Goal**: Implement all REST API endpoints with full functionality, including admin operations and file serving.

**Deliverable**: Fully functional REST API matching the spec, with authentication enforced on all protected endpoints.

**Dependencies**:
- Epic 1 (Backend Foundation) - ✅ Complete
- Epic 2 (APK Processing) - ✅ Complete
- Epic 3 (Backend Authentication) - ✅ Complete

---

## Current State Analysis

### Already Implemented

**User Endpoints (partial):**
- `GET /api/apps` - List all apps ✅
- `GET /api/apps/{packageName}` - Get app details with versions ✅

**Database Operations:**
- All CRUD operations for apps and versions ✅
- Transaction-aware variants ✅

**Services:**
- `UploadService` with full APK/AAB processing pipeline ✅
- `StorageService` for file management ✅
- `ApkParser` for metadata extraction ✅
- `AabConverter` for AAB→APK conversion ✅

### To Be Implemented

**User Endpoints:**
- `GET /api/apps/{packageName}/icon` - Serve icon image
- `GET /api/apps/{packageName}/versions/{versionCode}/apk` - Serve APK with Range support

**Admin Endpoints:**
- `POST /api/admin/apps` - Upload new app/version (multipart)
- `PUT /api/admin/apps/{packageName}` - Update app metadata
- `DELETE /api/admin/apps/{packageName}` - Delete app and all versions
- `DELETE /api/admin/apps/{packageName}/versions/{versionCode}` - Delete specific version

---

## Architecture Decisions

### File Serving Strategy

**Decision**: Use `tokio_util::io::ReaderStream` for streaming files efficiently.

**Rationale**:
- Memory efficient - doesn't load entire file into memory
- Supports Range headers for resumable downloads
- Compatible with Axum's streaming response

### Multipart Upload Handling

**Decision**: Use `axum::extract::Multipart` with streaming to temp file.

**Rationale**:
- Built into Axum, no extra dependencies
- Stream to disk to avoid memory issues with large files
- Validate size during streaming, abort early if too large

### Range Request Support

**Decision**: Implement RFC 7233 partial content support.

**Features**:
- Parse `Range: bytes=start-end` header
- Return `206 Partial Content` with `Content-Range` header
- Support single range requests (not multi-range)
- Return `416 Range Not Satisfiable` for invalid ranges

### Delete Cascade Behavior

**Decision**: When deleting the last version, auto-delete the app.

**Rationale**:
- Prevents orphaned app records with no versions
- Matches user expectation
- Can be overridden with future "archive" feature

---

## Tasks

### 1. File Serving Utilities

#### 1.1 Create File Response Helper (`src/api/file_response.rs`)

```rust
use axum::{
    body::Body,
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
};
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::io::ReaderStream;

pub struct FileResponse {
    file: File,
    content_type: &'static str,
    filename: Option<String>,
    size: u64,
    range: Option<(u64, u64)>,
}

impl FileResponse {
    pub async fn new(path: &Path, content_type: &'static str) -> Result<Self, std::io::Error>;
    pub fn with_filename(self, name: String) -> Self;
    pub fn with_range(self, start: u64, end: u64) -> Self;
}

impl IntoResponse for FileResponse {
    fn into_response(self) -> Response;
}
```

#### 1.2 Range Header Parser

```rust
/// Parse Range header: "bytes=start-end" or "bytes=start-"
pub fn parse_range_header(header: &str, file_size: u64) -> Result<(u64, u64), RangeError>;

pub enum RangeError {
    InvalidFormat,
    RangeNotSatisfiable,
}
```

---

### 2. User Endpoints

#### 2.1 Icon Endpoint (`GET /api/apps/{packageName}/icon`)

```rust
pub async fn get_icon(
    State(state): State<AppState>,
    Path(package_name): Path<String>,
) -> Result<impl IntoResponse, AppError> {
    // 1. Get app from database
    // 2. Check icon_path exists
    // 3. Return FileResponse with image/png content type
    // 4. Handle missing icon with 404
}
```

**Response:**
- `200 OK` with `Content-Type: image/png`
- `404 Not Found` if app or icon doesn't exist

#### 2.2 APK Download Endpoint (`GET /api/apps/{packageName}/versions/{versionCode}/apk`)

```rust
pub async fn download_apk(
    State(state): State<AppState>,
    Path((package_name, version_code)): Path<(String, i64)>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, AppError> {
    // 1. Get version from database
    // 2. Parse Range header if present
    // 3. Return FileResponse with:
    //    - Content-Type: application/vnd.android.package-archive
    //    - Content-Disposition: attachment; filename="app-1.0.0.apk"
    //    - Accept-Ranges: bytes
    //    - Content-Range (if partial)
}
```

**Response:**
- `200 OK` for full download
- `206 Partial Content` for range request
- `416 Range Not Satisfiable` for invalid range
- `404 Not Found` if version doesn't exist

---

### 3. Admin Endpoints

#### 3.1 Upload Endpoint (`POST /api/admin/apps`)

```rust
#[derive(Debug, Deserialize)]
pub struct UploadParams {
    name: Option<String>,
    description: Option<String>,
}

pub async fn upload_app(
    State(state): State<AppState>,
    admin: AdminUser,  // Extractor ensures admin role
    mut multipart: Multipart,
) -> Result<Json<UploadResponse>, AppError> {
    // 1. Extract file from multipart
    // 2. Extract optional name/description fields
    // 3. Stream file to temp location
    // 4. Call UploadService.process_upload()
    // 5. Return result with created app/version info
}

#[derive(Debug, Serialize)]
pub struct UploadResponse {
    package_name: String,
    version_code: i64,
    version_name: String,
    app_name: String,
    is_new_app: bool,
}
```

**Request:**
- `Content-Type: multipart/form-data`
- Fields: `file` (required), `name` (optional), `description` (optional)

**Response:**
- `201 Created` with JSON body
- `400 Bad Request` for validation errors
- `409 Conflict` for duplicate version
- `413 Payload Too Large` if file exceeds limit

#### 3.2 Update Metadata Endpoint (`PUT /api/admin/apps/{packageName}`)

```rust
#[derive(Debug, Deserialize)]
pub struct UpdateAppRequest {
    name: Option<String>,
    description: Option<String>,
}

pub async fn update_app(
    State(state): State<AppState>,
    admin: AdminUser,
    Path(package_name): Path<String>,
    Json(request): Json<UpdateAppRequest>,
) -> Result<Json<Value>, AppError> {
    // 1. Verify app exists
    // 2. Update name/description if provided
    // 3. Return updated app
}
```

**Response:**
- `200 OK` with updated app
- `404 Not Found` if app doesn't exist

#### 3.3 Delete App Endpoint (`DELETE /api/admin/apps/{packageName}`)

```rust
pub async fn delete_app(
    State(state): State<AppState>,
    admin: AdminUser,
    Path(package_name): Path<String>,
) -> Result<StatusCode, AppError> {
    // 1. Verify app exists
    // 2. Get all versions to delete files
    // 3. Delete all APK files
    // 4. Delete icon
    // 5. Delete from database (cascades to versions)
    // 6. Return 204 No Content
}
```

**Response:**
- `204 No Content` on success
- `404 Not Found` if app doesn't exist

#### 3.4 Delete Version Endpoint (`DELETE /api/admin/apps/{packageName}/versions/{versionCode}`)

```rust
pub async fn delete_version(
    State(state): State<AppState>,
    admin: AdminUser,
    Path((package_name, version_code)): Path<(String, i64)>,
) -> Result<Json<DeleteVersionResponse>, AppError> {
    // 1. Verify version exists
    // 2. Delete APK file
    // 3. Delete from database
    // 4. Check if this was last version
    // 5. If last version, also delete app and icon
    // 6. Return response indicating if app was also deleted
}

#[derive(Debug, Serialize)]
pub struct DeleteVersionResponse {
    deleted_version: bool,
    deleted_app: bool,  // true if last version was deleted
}
```

**Response:**
- `200 OK` with JSON indicating what was deleted
- `404 Not Found` if version doesn't exist

---

### 4. Router Updates

#### 4.1 Update Routes (`src/api/routes.rs`)

```rust
/// User API routes (requires authentication)
fn user_routes(auth_state: AuthState) -> Router<AppState> {
    Router::new()
        .route("/apps", get(handlers::list_apps))
        .route("/apps/{package_name}", get(handlers::get_app))
        .route("/apps/{package_name}/icon", get(handlers::get_icon))
        .route(
            "/apps/{package_name}/versions/{version_code}/apk",
            get(handlers::download_apk),
        )
        .layer(middleware::from_fn_with_state(auth_state, auth_middleware))
}

/// Admin routes (requires authentication and admin role)
fn admin_routes(auth_state: AuthState) -> Router<AppState> {
    Router::new()
        .route("/apps", post(handlers::upload_app))
        .route("/apps/{package_name}", put(handlers::update_app))
        .route("/apps/{package_name}", delete(handlers::delete_app))
        .route(
            "/apps/{package_name}/versions/{version_code}",
            delete(handlers::delete_version),
        )
        .layer(middleware::from_fn_with_state(auth_state, auth_middleware))
}
```

---

### 5. Error Handling Updates

#### 5.1 Extend AppError (`src/error.rs`)

```rust
#[derive(Debug, Error)]
pub enum AppError {
    // Existing...

    #[error("File too large")]
    PayloadTooLarge,

    #[error("Invalid file type")]
    InvalidFileType,

    #[error("Version already exists")]
    Conflict(String),

    #[error("Range not satisfiable")]
    RangeNotSatisfiable,
}
```

#### 5.2 HTTP Status Code Mapping

```rust
impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            AppError::NotFound(_) => (StatusCode::NOT_FOUND, self.to_string()),
            AppError::PayloadTooLarge => (StatusCode::PAYLOAD_TOO_LARGE, self.to_string()),
            AppError::InvalidFileType => (StatusCode::BAD_REQUEST, self.to_string()),
            AppError::Conflict(_) => (StatusCode::CONFLICT, self.to_string()),
            AppError::RangeNotSatisfiable => (StatusCode::RANGE_NOT_SATISFIABLE, self.to_string()),
            AppError::Database(_) | AppError::Internal(_) => {
                (StatusCode::INTERNAL_SERVER_ERROR, "Internal server error".to_string())
            }
        };

        let body = json!({ "error": message });
        (status, Json(body)).into_response()
    }
}
```

---

### 6. AppState Updates

#### 6.1 Add UploadService to AppState

```rust
// src/api/mod.rs
#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Arc<Config>,
    pub auth: Option<AuthState>,
    pub upload_service: Arc<UploadService>,  // NEW
    pub storage: Arc<StorageService>,        // NEW
}
```

---

### 7. Input Validation

#### 7.1 Package Name Validation

```rust
fn validate_package_name(name: &str) -> Result<(), AppError> {
    // Already exists in storage.rs, expose it
}
```

#### 7.2 Version Code Validation

```rust
fn validate_version_code(code: i64) -> Result<(), AppError> {
    if code <= 0 {
        return Err(AppError::BadRequest("Version code must be positive".to_string()));
    }
    Ok(())
}
```

---

### 8. Dependencies

#### 8.1 Add Dependencies to Cargo.toml

```toml
[dependencies]
# For file streaming
tokio-util = { version = "0.7", features = ["io"] }
```

---

### 9. Testing

#### 9.1 Unit Tests

**File Response Tests:**
- Range header parsing (valid, invalid, edge cases)
- Content-Range header formatting
- Partial content response

**Validation Tests:**
- Package name validation
- Version code validation

#### 9.2 Integration Tests (`tests/api.rs`)

```rust
#[tokio::test]
async fn test_upload_apk() {
    // Upload valid APK
    // Verify 201 Created
    // Verify app in database
    // Verify file on disk
}

#[tokio::test]
async fn test_upload_duplicate_version() {
    // Upload APK
    // Upload same version again
    // Verify 409 Conflict
}

#[tokio::test]
async fn test_download_apk() {
    // Upload APK first
    // Download it
    // Verify content matches
}

#[tokio::test]
async fn test_download_apk_range() {
    // Upload APK first
    // Download with Range header
    // Verify 206 Partial Content
    // Verify Content-Range header
    // Verify partial content
}

#[tokio::test]
async fn test_delete_version() {
    // Upload app with 2 versions
    // Delete one version
    // Verify app still exists
    // Delete last version
    // Verify app also deleted
}

#[tokio::test]
async fn test_update_app_metadata() {
    // Upload app
    // Update name/description
    // Verify changes persisted
}

#[tokio::test]
async fn test_get_icon() {
    // Upload APK with icon
    // Fetch icon
    // Verify image data
}

#[tokio::test]
async fn test_admin_endpoints_require_admin() {
    // Request admin endpoint with user token
    // Verify 403 Forbidden
}
```

---

## Verification Checklist

### Build Verification
- [ ] `cargo build` completes without errors
- [ ] `cargo clippy` passes with no warnings
- [ ] `cargo fmt --check` passes
- [ ] `cargo test` passes

### User Endpoints
- [ ] `GET /api/apps/{packageName}/icon` returns PNG image
- [ ] `GET /api/apps/{packageName}/icon` returns 404 for missing icon
- [ ] `GET /api/apps/{packageName}/versions/{versionCode}/apk` returns APK
- [ ] APK download includes correct headers (Content-Type, Content-Disposition, Accept-Ranges)
- [ ] Range request returns 206 with partial content
- [ ] Invalid range returns 416

### Admin Endpoints
- [ ] `POST /api/admin/apps` uploads APK successfully
- [ ] `POST /api/admin/apps` uploads AAB and converts to APK
- [ ] Upload with duplicate version returns 409
- [ ] Upload with oversized file returns 413
- [ ] Upload with invalid file type returns 400
- [ ] `PUT /api/admin/apps/{packageName}` updates metadata
- [ ] Update non-existent app returns 404
- [ ] `DELETE /api/admin/apps/{packageName}` removes app and files
- [ ] `DELETE /api/admin/apps/{packageName}/versions/{versionCode}` removes version
- [ ] Deleting last version also deletes app

### Authentication
- [ ] All `/api/*` endpoints require authentication (when auth configured)
- [ ] All `/api/admin/*` endpoints require admin role
- [ ] Non-admin user gets 403 on admin endpoints

### Error Handling
- [ ] All errors return JSON with `error` field
- [ ] No sensitive information leaked in errors
- [ ] Correct HTTP status codes for all error cases

---

## Progress Tracking

| Task | Status | Notes |
|------|--------|-------|
| 1.1 File response helper | Not Started | |
| 1.2 Range header parser | Not Started | |
| 2.1 Icon endpoint | Not Started | |
| 2.2 APK download endpoint | Not Started | |
| 3.1 Upload endpoint | Not Started | |
| 3.2 Update metadata endpoint | Not Started | |
| 3.3 Delete app endpoint | Not Started | |
| 3.4 Delete version endpoint | Not Started | |
| 4.1 Router updates | Not Started | |
| 5.1 Error handling updates | Not Started | |
| 6.1 AppState updates | Not Started | |
| 7.1-7.2 Input validation | Not Started | |
| 8.1 Dependencies | Not Started | |
| 9.1 Unit tests | Not Started | |
| 9.2 Integration tests | Not Started | |
| Verification | Not Started | |

---

## Notes

### Multipart Streaming

For large file uploads, we should stream the multipart body to disk rather than buffering in memory:

```rust
while let Some(field) = multipart.next_field().await? {
    if field.name() == Some("file") {
        let mut file = tokio::fs::File::create(&temp_path).await?;
        let mut size = 0u64;

        while let Some(chunk) = field.chunk().await? {
            size += chunk.len() as u64;
            if size > max_size {
                return Err(AppError::PayloadTooLarge);
            }
            file.write_all(&chunk).await?;
        }
    }
}
```

### Content-Disposition Header

For APK downloads, use:
```
Content-Disposition: attachment; filename="com.example.app-1.0.0.apk"
```

This triggers download behavior in browsers and provides a meaningful filename.

### Concurrent Upload Handling

The current implementation handles concurrent uploads safely because:
1. Each upload uses a unique temp directory
2. Final file paths include version code (unique per app)
3. Database has unique constraint on (package_name, version_code)

### Testing Without Auth

For integration tests without a real OIDC server, tests can:
1. Use `auth: None` in AppState (routes become public)
2. Mock the auth middleware in tests
3. Create test tokens with a test signing key
