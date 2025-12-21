use sqlx::SqlitePool;
use std::io::Cursor;
use thiserror::Error;
use tracing::warn;
use zip::ZipArchive;

use crate::db;
use crate::error::AppError;

use super::aab::{AabConverter, AabError};
use super::apk::{ApkError, ApkParser};
use super::storage::{StorageError, StorageService};

#[derive(Debug, Error)]
pub enum UploadError {
    #[error("File too large (max: {max} bytes, got: {actual} bytes)")]
    FileTooLarge { max: u64, actual: u64 },

    #[error("Invalid file type: expected APK or AAB")]
    InvalidFileType,

    #[error("Version {version_code} already exists for {package_name}")]
    VersionExists {
        package_name: String,
        version_code: i64,
    },

    #[error("AAB conversion not available: {0}")]
    AabNotSupported(String),

    #[error("APK parsing failed: {0}")]
    ApkError(#[from] ApkError),

    #[error("AAB conversion failed: {0}")]
    AabError(#[from] AabError),

    #[error("Storage error: {0}")]
    StorageError(#[from] StorageError),

    #[error("Database error: {0}")]
    DatabaseError(#[from] AppError),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug)]
pub struct UploadResult {
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    pub app_name: String,
    pub is_new_app: bool,
}

pub struct UploadService {
    storage: StorageService,
    apk_parser: ApkParser,
    aab_converter: Option<AabConverter>,
    db: SqlitePool,
    max_size: u64,
}

impl UploadService {
    pub fn new(
        storage: StorageService,
        apk_parser: ApkParser,
        aab_converter: Option<AabConverter>,
        db: SqlitePool,
        max_size: u64,
    ) -> Self {
        Self {
            storage,
            apk_parser,
            aab_converter,
            db,
            max_size,
        }
    }

    /// Process an uploaded file (APK or AAB)
    pub async fn process_upload(
        &self,
        file_name: &str,
        data: Vec<u8>,
        override_name: Option<String>,
        override_description: Option<String>,
    ) -> Result<UploadResult, UploadError> {
        // 1. Validate file size
        let size = data.len() as u64;
        if size > self.max_size {
            return Err(UploadError::FileTooLarge {
                max: self.max_size,
                actual: size,
            });
        }

        // 2. Detect file type
        let file_type = detect_file_type(&data, file_name);

        // 3. Create temp directory for processing
        let temp_dir = self.storage.create_temp_dir()?;

        // 4. Get APK data (convert if AAB)
        let apk_data = match file_type {
            FileType::Apk => data,
            FileType::Aab => {
                let converter = self.aab_converter.as_ref().ok_or_else(|| {
                    UploadError::AabNotSupported(
                        "bundletool not configured. Set BUNDLETOOL_PATH and ensure Java is available.".to_string(),
                    )
                })?;

                // Write AAB to temp directory
                let aab_path = temp_dir.path().join("input.aab");
                tokio::fs::write(&aab_path, &data).await?;

                // Convert to APK
                let apk_path = converter.convert(&aab_path, temp_dir.path()).await?;

                // Read the resulting APK
                tokio::fs::read(&apk_path).await?
            }
            FileType::Unknown => {
                return Err(UploadError::InvalidFileType);
            }
        };

        // 5. Write APK to temp dir for parsing
        let temp_apk_path = temp_dir.path().join("app.apk");
        tokio::fs::write(&temp_apk_path, &apk_data).await?;

        // 6. Parse APK metadata
        let metadata = self.apk_parser.parse(&temp_apk_path).await?;

        // 7. Check for existing version
        if db::version_exists(&self.db, &metadata.package_name, metadata.version_code).await? {
            return Err(UploadError::VersionExists {
                package_name: metadata.package_name,
                version_code: metadata.version_code,
            });
        }

        // 8. Calculate SHA-256
        let sha256 = StorageService::calculate_sha256(&apk_data);

        // 9. Check if this is a new app
        let existing_app = db::get_app(&self.db, &metadata.package_name).await?;
        let is_new_app = existing_app.is_none();

        // 10. Save APK file
        let apk_path =
            self.storage
                .save_apk(&metadata.package_name, metadata.version_code, &apk_data)?;

        // 11. Save icon if available (best-effort)
        let icon_path = if let Some(icon_data) = &metadata.icon_data {
            match self.storage.save_icon(&metadata.package_name, icon_data) {
                Ok(path) => Some(path),
                Err(e) => {
                    warn!("Failed to save icon for {}: {}", metadata.package_name, e);
                    None
                }
            }
        } else {
            None
        };

        // 12. Update database (with cleanup on failure)
        let app_name = override_name
            .as_ref()
            .cloned()
            .unwrap_or_else(|| metadata.app_name.clone());

        let db_result = self
            .update_database(
                &metadata.package_name,
                metadata.version_code,
                &metadata.version_name,
                &apk_path,
                apk_data.len() as i64,
                &sha256,
                metadata.min_sdk,
                &app_name,
                override_name.as_deref(),
                override_description.as_deref(),
                icon_path.as_deref(),
                is_new_app,
            )
            .await;

        // If database update failed, clean up saved files
        if let Err(ref e) = db_result {
            warn!(
                "Database update failed for {}, cleaning up files: {}",
                metadata.package_name, e
            );
            self.cleanup_on_failure(&metadata.package_name, metadata.version_code, is_new_app);
        }

        db_result?;

        // 13. Temp directory is automatically cleaned up when dropped

        Ok(UploadResult {
            package_name: metadata.package_name,
            version_code: metadata.version_code,
            version_name: metadata.version_name,
            app_name,
            is_new_app,
        })
    }

    /// Update database with all app and version information.
    /// Uses a transaction to ensure atomicity.
    #[allow(clippy::too_many_arguments)]
    async fn update_database(
        &self,
        package_name: &str,
        version_code: i64,
        version_name: &str,
        apk_path: &str,
        size: i64,
        sha256: &str,
        min_sdk: i64,
        app_name: &str,
        override_name: Option<&str>,
        override_description: Option<&str>,
        icon_path: Option<&str>,
        is_new_app: bool,
    ) -> Result<(), UploadError> {
        // Start a transaction
        let mut tx = self.db.begin().await.map_err(AppError::Database)?;

        if is_new_app {
            db::insert_app_tx(
                &mut tx,
                package_name,
                app_name,
                override_description,
                icon_path,
            )
            .await?;
        } else {
            // Update icon only if we have a new one (and optionally name/description)
            db::update_app_tx(
                &mut tx,
                package_name,
                override_name,
                override_description,
                icon_path,
            )
            .await?;
        }

        // Insert version
        db::insert_app_version_tx(
            &mut tx,
            package_name,
            version_code,
            version_name,
            apk_path,
            size,
            sha256,
            min_sdk,
        )
        .await?;

        // Commit transaction
        tx.commit().await.map_err(AppError::Database)?;

        Ok(())
    }

    /// Clean up files if database operation fails
    fn cleanup_on_failure(&self, package_name: &str, version_code: i64, is_new_app: bool) {
        // Delete the APK we just saved
        if let Err(e) = self.storage.delete_apk(package_name, version_code) {
            warn!("Failed to clean up APK after DB failure: {}", e);
        }

        // If this was a new app, also delete the icon we saved
        if is_new_app {
            if let Err(e) = self.storage.delete_icon(package_name) {
                warn!("Failed to clean up icon after DB failure: {}", e);
            }
        }
    }
}

#[derive(Debug, PartialEq)]
enum FileType {
    Apk,
    Aab,
    Unknown,
}

/// Detect file type from magic bytes and/or filename
fn detect_file_type(data: &[u8], filename: &str) -> FileType {
    // Both APK and AAB are ZIP files, so check for ZIP header first
    if data.len() < 4 || &data[0..2] != b"PK" {
        return FileType::Unknown;
    }

    // Try to open as ZIP and check contents
    let cursor = Cursor::new(data);
    let archive = match ZipArchive::new(cursor) {
        Ok(a) => a,
        Err(_) => return FileType::Unknown,
    };

    // AAB contains BundleConfig.pb
    let has_bundle_config = archive.file_names().any(|name| name == "BundleConfig.pb");

    // APK contains AndroidManifest.xml
    let has_manifest = archive
        .file_names()
        .any(|name| name == "AndroidManifest.xml");

    if has_bundle_config {
        FileType::Aab
    } else if has_manifest {
        FileType::Apk
    } else {
        // Fall back to extension
        let lower = filename.to_lowercase();
        if lower.ends_with(".apk") {
            FileType::Apk
        } else if lower.ends_with(".aab") {
            FileType::Aab
        } else {
            FileType::Unknown
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use zip::write::SimpleFileOptions;

    fn create_fake_apk() -> Vec<u8> {
        let mut buf = Vec::new();
        {
            let cursor = Cursor::new(&mut buf);
            let mut zip = zip::ZipWriter::new(cursor);
            let options = SimpleFileOptions::default();
            zip.start_file("AndroidManifest.xml", options).unwrap();
            std::io::Write::write_all(&mut zip, b"fake manifest").unwrap();
            zip.finish().unwrap();
        }
        buf
    }

    fn create_fake_aab() -> Vec<u8> {
        let mut buf = Vec::new();
        {
            let cursor = Cursor::new(&mut buf);
            let mut zip = zip::ZipWriter::new(cursor);
            let options = SimpleFileOptions::default();
            zip.start_file("BundleConfig.pb", options).unwrap();
            std::io::Write::write_all(&mut zip, b"fake bundle config").unwrap();
            zip.finish().unwrap();
        }
        buf
    }

    #[test]
    fn test_detect_file_type_apk() {
        let data = create_fake_apk();
        assert_eq!(detect_file_type(&data, "app.apk"), FileType::Apk);
        assert_eq!(detect_file_type(&data, "app.zip"), FileType::Apk);
    }

    #[test]
    fn test_detect_file_type_aab() {
        let data = create_fake_aab();
        assert_eq!(detect_file_type(&data, "app.aab"), FileType::Aab);
        assert_eq!(detect_file_type(&data, "app.zip"), FileType::Aab);
    }

    #[test]
    fn test_detect_file_type_unknown() {
        assert_eq!(
            detect_file_type(b"not a zip", "test.txt"),
            FileType::Unknown
        );
        assert_eq!(detect_file_type(b"", "empty"), FileType::Unknown);
    }

    #[test]
    fn test_detect_file_type_by_extension_fallback() {
        // Create a ZIP without specific markers
        let mut buf = Vec::new();
        {
            let cursor = Cursor::new(&mut buf);
            let mut zip = zip::ZipWriter::new(cursor);
            let options = SimpleFileOptions::default();
            zip.start_file("random.txt", options).unwrap();
            std::io::Write::write_all(&mut zip, b"random content").unwrap();
            zip.finish().unwrap();
        }

        assert_eq!(detect_file_type(&buf, "app.apk"), FileType::Apk);
        assert_eq!(detect_file_type(&buf, "app.aab"), FileType::Aab);
        assert_eq!(detect_file_type(&buf, "app.zip"), FileType::Unknown);
    }
}
