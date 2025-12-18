# Epic 2: APK Processing - Implementation Plan

## Overview

**Goal**: Implement APK/AAB file handling, metadata extraction, and storage. This is the core feature that enables adding apps to the store.

**Deliverable**: A service that can process uploaded APK/AAB files, extract metadata (package name, version, icon, minSdk), store files, calculate checksums, and persist to database.

**Dependencies**: Epic 1 (Backend Foundation) - ✅ Complete

---

## Architecture Decisions

### APK Parsing Approach

**Decision**: Use `aapt2` command-line tool for APK metadata extraction.

**Rationale**:
- Pure Rust APK parsers exist but have limitations with icon extraction and complex manifests
- `aapt2` is the official Android tool, guaranteed to handle all APK edge cases
- Already available in Android SDK, commonly installed on dev machines
- Can fall back to bundled binary or Docker container for deployment
- Simpler implementation with more reliable results

**Alternative considered**: `apk-parser` Rust crate - rejected due to incomplete icon extraction support and potential manifest parsing edge cases.

### AAB Conversion

**Decision**: Use `bundletool` for AAB to APK conversion.

**Rationale**:
- Official Google tool for AAB processing
- Produces universal APKs that work on all devices
- Requires Java runtime (acceptable for self-hosted homelab)

### File Storage Structure

```
data/storage/
├── apks/
│   └── {package_name}/
│       └── {version_code}.apk
├── icons/
│   └── {package_name}.png
└── temp/
    └── {uuid}/           # Temporary processing directory
```

---

## Tasks

### 1. Dependencies & Infrastructure

#### 1.1 Add New Dependencies
```toml
[dependencies]
# File handling
sha2 = "0.10"                    # SHA-256 checksums
uuid = { version = "1", features = ["v4"] }  # Unique temp directories
zip = "2"                        # Extract icons from APK (which is a ZIP)
image = "0.25"                   # Image processing for icon resizing
tokio-util = { version = "0.7", features = ["io"] }  # Async file operations

# Multipart uploads
axum = { version = "0.7", features = ["multipart"] }

# Process execution
tokio = { version = "1", features = ["full", "process"] }
```

#### 1.2 Configuration Extensions
Add to `config.rs`:
```rust
pub struct Config {
    // ... existing fields ...
    pub aapt2_path: Option<PathBuf>,      // Path to aapt2 binary (auto-detect if None)
    pub bundletool_path: Option<PathBuf>, // Path to bundletool jar
    pub java_path: Option<PathBuf>,       // Path to java binary
    pub max_upload_size: u64,             // Max file size in bytes (default: 500MB)
}
```

Environment variables:
```env
AAPT2_PATH=/path/to/aapt2
BUNDLETOOL_PATH=/path/to/bundletool.jar
JAVA_PATH=/usr/bin/java
MAX_UPLOAD_SIZE=524288000
```

---

### 2. File Storage Service

#### 2.1 Storage Service (`src/services/storage.rs`)
Implement the following:

```rust
pub struct StorageService {
    base_path: PathBuf,
}

impl StorageService {
    /// Create a new temporary directory for processing
    pub fn create_temp_dir(&self) -> Result<TempDir, StorageError>;

    /// Save APK to permanent storage, returns the relative path
    pub async fn save_apk(
        &self,
        package_name: &str,
        version_code: i64,
        data: &[u8],
    ) -> Result<String, StorageError>;

    /// Save icon to permanent storage, returns the relative path
    pub async fn save_icon(
        &self,
        package_name: &str,
        data: &[u8],
    ) -> Result<String, StorageError>;

    /// Delete APK file
    pub async fn delete_apk(
        &self,
        package_name: &str,
        version_code: i64,
    ) -> Result<(), StorageError>;

    /// Delete icon file
    pub async fn delete_icon(&self, package_name: &str) -> Result<(), StorageError>;

    /// Delete all files for a package (all versions + icon)
    pub async fn delete_package(&self, package_name: &str) -> Result<(), StorageError>;

    /// Get absolute path for serving files
    pub fn get_apk_path(&self, package_name: &str, version_code: i64) -> PathBuf;
    pub fn get_icon_path(&self, package_name: &str) -> PathBuf;

    /// Calculate SHA-256 checksum
    pub fn calculate_sha256(data: &[u8]) -> String;
}
```

#### 2.2 Custom Error Types (`src/services/storage.rs`)
```rust
#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("File not found: {0}")]
    NotFound(String),

    #[error("Storage quota exceeded")]
    QuotaExceeded,
}
```

---

### 3. APK Metadata Extraction

#### 3.1 APK Parser Service (`src/services/apk.rs`)

```rust
#[derive(Debug, Clone)]
pub struct ApkMetadata {
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    pub min_sdk: i64,
    pub app_name: String,          // Extracted from manifest or label
    pub icon_data: Option<Vec<u8>>, // PNG bytes of largest icon
}

pub struct ApkParser {
    aapt2_path: PathBuf,
}

impl ApkParser {
    /// Detect aapt2 location (check common paths, PATH, etc.)
    pub fn detect_aapt2() -> Result<PathBuf, ApkError>;

    /// Parse APK metadata using aapt2
    pub async fn parse(&self, apk_path: &Path) -> Result<ApkMetadata, ApkError>;

    /// Extract icon from APK (APK is a ZIP file)
    /// Returns the largest PNG icon found
    async fn extract_icon(&self, apk_path: &Path, icon_path: &str) -> Result<Vec<u8>, ApkError>;
}
```

#### 3.2 aapt2 Output Parsing

`aapt2 dump badging <apk>` outputs data like:
```
package: name='com.example.app' versionCode='10' versionName='1.0.0' ...
sdkVersion:'26'
application-label:'My App'
application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'
```

Parse this output to extract:
- `package: name='...'` → package_name
- `versionCode='...'` → version_code
- `versionName='...'` → version_name
- `sdkVersion:'...'` → min_sdk
- `application-label:'...'` → app_name
- `application-icon-*` → choose highest density icon path

#### 3.3 Icon Extraction

1. APK is a ZIP file
2. Open APK as ZIP archive
3. Find the icon file at the path from aapt2 output
4. Extract and resize to standard size (192x192 PNG)
5. Convert to PNG if needed (some icons are WebP)

#### 3.4 Error Types
```rust
#[derive(Debug, thiserror::Error)]
pub enum ApkError {
    #[error("aapt2 not found: {0}")]
    Aapt2NotFound(String),

    #[error("aapt2 execution failed: {0}")]
    Aapt2Failed(String),

    #[error("Failed to parse APK metadata: {0}")]
    ParseError(String),

    #[error("Invalid APK file: {0}")]
    InvalidApk(String),

    #[error("Icon extraction failed: {0}")]
    IconError(String),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}
```

---

### 4. AAB Conversion

#### 4.1 AAB Converter Service (`src/services/aab.rs`)

```rust
pub struct AabConverter {
    bundletool_path: PathBuf,
    java_path: PathBuf,
}

impl AabConverter {
    /// Convert AAB to universal APK
    /// Returns path to the generated APK (in temp directory)
    pub async fn convert(&self, aab_path: &Path, output_dir: &Path) -> Result<PathBuf, AabError>;
}
```

#### 4.2 Conversion Process

1. Run bundletool:
   ```bash
   java -jar bundletool.jar build-apks \
     --bundle=input.aab \
     --output=output.apks \
     --mode=universal
   ```

2. The `.apks` file is a ZIP containing `universal.apk`
3. Extract `universal.apk` from the archive
4. Return path to extracted APK

#### 4.3 Error Types
```rust
#[derive(Debug, thiserror::Error)]
pub enum AabError {
    #[error("bundletool not found: {0}")]
    BundletoolNotFound(String),

    #[error("Java not found: {0}")]
    JavaNotFound(String),

    #[error("AAB conversion failed: {0}")]
    ConversionFailed(String),

    #[error("Invalid AAB file")]
    InvalidAab,

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}
```

---

### 5. Upload Processing Pipeline

#### 5.1 Upload Service (`src/services/upload.rs`)

```rust
pub struct UploadService {
    storage: StorageService,
    apk_parser: ApkParser,
    aab_converter: Option<AabConverter>,  // None if bundletool not available
    db: SqlitePool,
    max_size: u64,
}

#[derive(Debug)]
pub struct UploadResult {
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    pub app_name: String,
    pub is_new_app: bool,  // true if this is a new app, false if new version
}

impl UploadService {
    /// Process an uploaded file (APK or AAB)
    pub async fn process_upload(
        &self,
        file_name: &str,
        data: Vec<u8>,
        override_name: Option<String>,
        override_description: Option<String>,
    ) -> Result<UploadResult, UploadError>;
}
```

#### 5.2 Upload Processing Flow

1. **Validate file size** - Check against max_upload_size
2. **Detect file type** - Check magic bytes:
   - APK: `PK` (ZIP) + contains `AndroidManifest.xml`
   - AAB: `PK` (ZIP) + contains `BundleConfig.pb`
3. **Create temp directory** - For processing
4. **If AAB: Convert to APK**
   - If bundletool not available, return error
   - Convert and continue with the resulting APK
5. **Parse APK metadata** - Extract package, version, icon, etc.
6. **Check for existing version** - Query database
   - If version exists: return error (must delete first)
7. **Calculate SHA-256** - Of the final APK
8. **Save files**:
   - Save APK to `apks/{package_name}/{version_code}.apk`
   - Save icon to `icons/{package_name}.png` (overwrite if exists)
9. **Update database**:
   - If new app: INSERT into apps table
   - INSERT into app_versions table
10. **Clean up temp directory**
11. **Return result**

#### 5.3 Error Types
```rust
#[derive(Debug, thiserror::Error)]
pub enum UploadError {
    #[error("File too large (max: {max} bytes, got: {actual} bytes)")]
    FileTooLarge { max: u64, actual: u64 },

    #[error("Invalid file type: expected APK or AAB")]
    InvalidFileType,

    #[error("Version {version_code} already exists for {package_name}")]
    VersionExists { package_name: String, version_code: i64 },

    #[error("AAB conversion not available: bundletool not configured")]
    AabNotSupported,

    #[error("APK parsing failed: {0}")]
    ApkError(#[from] ApkError),

    #[error("AAB conversion failed: {0}")]
    AabError(#[from] AabError),

    #[error("Storage error: {0}")]
    StorageError(#[from] StorageError),

    #[error("Database error: {0}")]
    DatabaseError(#[from] sqlx::Error),
}
```

---

### 6. Database Operations

#### 6.1 Add Database Functions (`src/db/mod.rs`)

```rust
/// Insert a new app
pub async fn insert_app(
    pool: &SqlitePool,
    package_name: &str,
    name: &str,
    description: Option<&str>,
    icon_path: Option<&str>,
) -> Result<(), AppError>;

/// Update app metadata
pub async fn update_app(
    pool: &SqlitePool,
    package_name: &str,
    name: Option<&str>,
    description: Option<&str>,
) -> Result<(), AppError>;

/// Insert a new app version
pub async fn insert_app_version(
    pool: &SqlitePool,
    package_name: &str,
    version_code: i64,
    version_name: &str,
    apk_path: &str,
    size: i64,
    sha256: &str,
    min_sdk: i64,
) -> Result<(), AppError>;

/// Delete an app version
pub async fn delete_app_version(
    pool: &SqlitePool,
    package_name: &str,
    version_code: i64,
) -> Result<(), AppError>;

/// Delete an app and all its versions
pub async fn delete_app(pool: &SqlitePool, package_name: &str) -> Result<(), AppError>;

/// Check if version exists
pub async fn version_exists(
    pool: &SqlitePool,
    package_name: &str,
    version_code: i64,
) -> Result<bool, AppError>;

/// Count versions for an app
pub async fn count_versions(pool: &SqlitePool, package_name: &str) -> Result<i64, AppError>;
```

---

### 7. Module Organization

#### 7.1 Services Module Structure
```
src/services/
├── mod.rs          # Module exports
├── storage.rs      # File storage operations
├── apk.rs          # APK parsing with aapt2
├── aab.rs          # AAB to APK conversion
└── upload.rs       # Upload processing pipeline
```

#### 7.2 Update `src/services/mod.rs`
```rust
pub mod storage;
pub mod apk;
pub mod aab;
pub mod upload;

pub use storage::StorageService;
pub use apk::ApkParser;
pub use aab::AabConverter;
pub use upload::UploadService;
```

---

### 8. Testing

#### 8.1 Unit Tests

**APK Parser Tests** (`src/services/apk.rs`):
- Test aapt2 output parsing with various formats
- Test icon path selection (prefer higher density)
- Test error handling for missing aapt2

**Storage Tests** (`src/services/storage.rs`):
- Test SHA-256 calculation
- Test file path generation
- Test temp directory creation/cleanup

**Upload Tests** (`src/services/upload.rs`):
- Test file type detection (APK vs AAB vs invalid)
- Test file size validation

#### 8.2 Integration Tests

Create `tests/apk_processing.rs`:

```rust
#[tokio::test]
async fn test_upload_apk() {
    // Upload a real APK file
    // Verify database records created
    // Verify files stored correctly
    // Verify SHA-256 is correct
}

#[tokio::test]
async fn test_upload_duplicate_version() {
    // Upload an APK
    // Try to upload same version again
    // Expect error
}

#[tokio::test]
async fn test_upload_new_version() {
    // Upload version 1
    // Upload version 2
    // Verify both versions exist
    // Verify app record has correct latest version
}

// Only run if bundletool is available
#[tokio::test]
#[ignore]  // Run with: cargo test -- --ignored
async fn test_upload_aab() {
    // Upload an AAB file
    // Verify conversion happened
    // Verify resulting APK metadata is correct
}
```

#### 8.3 Test Fixtures

Create `tests/fixtures/` with:
- `test.apk` - A minimal valid APK for testing
- `test.aab` - A minimal valid AAB for testing (optional)

**Note**: Can generate minimal test APK with Android SDK tools or find public domain samples.

---

## Verification Checklist

### Build Verification
- [ ] `cargo build` completes without errors
- [ ] `cargo clippy` passes with no warnings
- [ ] `cargo fmt --check` passes
- [ ] `cargo test` passes (unit tests)
- [ ] `cargo test -- --ignored` passes if aapt2/bundletool available (integration tests)

### Functional Verification

With aapt2 available:
- [ ] Can parse APK metadata correctly
- [ ] Package name extracted correctly
- [ ] Version code/name extracted correctly
- [ ] Min SDK extracted correctly
- [ ] App name extracted correctly
- [ ] Icon extracted and resized correctly

File storage:
- [ ] APK saved to correct path
- [ ] Icon saved to correct path
- [ ] SHA-256 calculated correctly
- [ ] Temp directories cleaned up

Database:
- [ ] New app creates apps record
- [ ] New version creates app_versions record
- [ ] Duplicate version rejected
- [ ] App metadata can be overridden on upload

With bundletool available (optional):
- [ ] AAB converted to APK
- [ ] Resulting APK processed correctly

Error handling:
- [ ] Large file rejected with clear error
- [ ] Invalid file type rejected
- [ ] Missing aapt2 produces clear error
- [ ] Missing bundletool produces clear error for AAB

---

## Acceptance Criteria

1. **APK Upload Works**: Can upload a valid APK and see correct metadata extracted
2. **Version Tracking**: Multiple versions of same app stored correctly
3. **Duplicate Prevention**: Cannot upload same version twice
4. **Icon Extraction**: App icon extracted and stored as PNG
5. **Checksum Calculated**: SHA-256 stored for integrity verification
6. **AAB Support** (if bundletool available): AAB files converted and processed
7. **Error Messages**: Clear, actionable error messages for all failure cases
8. **Cleanup**: Temporary files cleaned up after processing

---

## Notes

### aapt2 Installation

On Ubuntu/Debian:
```bash
# Option 1: From Android SDK
sdkmanager "build-tools;34.0.0"
# aapt2 at: $ANDROID_HOME/build-tools/34.0.0/aapt2

# Option 2: From apt (older version but works)
apt install aapt
```

### bundletool Installation

```bash
# Download from GitHub releases
wget https://github.com/google/bundletool/releases/download/1.15.6/bundletool-all-1.15.6.jar
mv bundletool-all-1.15.6.jar /usr/local/bin/bundletool.jar
```

### Java Requirement

bundletool requires Java 11+:
```bash
apt install openjdk-17-jre-headless
```

---

## Progress Tracking

| Task | Status | Notes |
|------|--------|-------|
| 1.1 Add dependencies | Done | |
| 1.2 Configuration extensions | Done | |
| 2.1 Storage service | Done | |
| 2.2 Storage error types | Done | |
| 3.1 APK parser service | Done | |
| 3.2 aapt2 output parsing | Done | |
| 3.3 Icon extraction | Done | |
| 3.4 APK error types | Done | |
| 4.1 AAB converter service | Done | |
| 4.2 Conversion process | Done | |
| 4.3 AAB error types | Done | |
| 5.1 Upload service | Done | |
| 5.2 Upload processing flow | Done | |
| 5.3 Upload error types | Done | |
| 6.1 Database functions | Done | |
| 7.1 Services module structure | Done | |
| 8.1 Unit tests | Not Started | |
| 8.2 Integration tests | Not Started | |
| 8.3 Test fixtures | Not Started | |
| Verification | Not Started | |
