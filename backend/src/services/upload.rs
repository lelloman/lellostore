use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::path::Path;
use thiserror::Error;
use tokio::io::AsyncReadExt;
use tracing::warn;
use zip::ZipArchive;

use crate::db;
use crate::error::AppError;

use super::aab::{AabConverter, AabError};
use super::apk::{ApkError, ApkParser};
use super::storage::{StorageError, StorageService};

#[derive(Debug, Error)]
pub enum UploadError {
    #[error("{0}")]
    InvalidPayload(String),
    #[error("File too large (max: {max} bytes, got: {actual} bytes)")]
    FileTooLarge { max: u64, actual: u64 },

    #[error("Invalid file type: expected APK or AAB")]
    InvalidFileType,

    #[error("This is a Paravoid shell. Use the Paravoid distribution workflow once complete shell verification is enabled.")]
    UnsupportedShell,

    #[error("Version {version_code} already exists for {package_name}")]
    VersionExists {
        package_name: String,
        version_code: i64,
    },

    #[error("Replacement version code must be higher than the latest release in this channel")]
    ReplacementNotNewer,

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

#[derive(Debug, serde::Serialize)]
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
    pub(crate) fn storage_root(&self) -> &Path {
        self.storage.root()
    }
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

    /// Re-extract icons for catalog entries created by an older or less capable
    /// parser. Individual APK failures are logged and do not prevent startup.
    pub async fn repair_outdated_icons(&self) -> Result<usize, UploadError> {
        let outdated_apps = sqlx::query_as::<_, (String, i64)>(
            r#"
            SELECT apps.package_name, MAX(app_versions.version_code)
            FROM apps
            JOIN app_versions ON app_versions.package_name = apps.package_name
            WHERE apps.icon_path IS NULL OR apps.icon_revision < 1
            GROUP BY apps.package_name
            "#,
        )
        .fetch_all(&self.db)
        .await
        .map_err(AppError::Database)?;

        let mut repaired = 0;
        for (package_name, version_code) in outdated_apps {
            let apk_path = self.storage.get_apk_path(&package_name, version_code);
            let metadata = match self.apk_parser.parse(&apk_path).await {
                Ok(metadata) => metadata,
                Err(error) => {
                    warn!(
                        "Could not repair icon for {} from {}: {}",
                        package_name,
                        apk_path.display(),
                        error
                    );
                    continue;
                }
            };
            let Some(icon_data) = metadata.icon_data else {
                continue;
            };

            let icon_path = match self.storage.save_icon(&package_name, &icon_data) {
                Ok(path) => path,
                Err(error) => {
                    warn!(
                        "Could not save repaired icon for {}: {}",
                        package_name, error
                    );
                    continue;
                }
            };

            if let Err(error) = sqlx::query(
                "UPDATE apps SET icon_path = ?, icon_revision = 1, updated_at = datetime('now') WHERE package_name = ?",
            )
            .bind(&icon_path)
            .bind(&package_name)
            .execute(&self.db)
            .await
            {
                // Preserve the saved icon and retry the metadata update next startup.
                warn!("Could not record repaired icon for {}: {}", package_name, error);
                continue;
            }

            repaired += 1;
        }

        Ok(repaired)
    }

    /// Process an uploaded file (APK or AAB) from a bounded temporary file.
    pub async fn process_upload_file(
        &self,
        file_name: &str,
        upload_path: &Path,
        override_name: Option<String>,
        override_description: Option<String>,
        is_beta: bool,
    ) -> Result<UploadResult, UploadError> {
        self.process_upload_file_with_replacement(
            file_name,
            upload_path,
            override_name,
            override_description,
            is_beta,
            false,
        )
        .await
    }

    /// Replace only the latest release in the selected channel, retaining older history.
    #[allow(clippy::too_many_arguments)]
    pub async fn process_upload_file_with_replacement(
        &self,
        file_name: &str,
        upload_path: &Path,
        override_name: Option<String>,
        override_description: Option<String>,
        is_beta: bool,
        replace_latest: bool,
    ) -> Result<UploadResult, UploadError> {
        self.process_upload(
            file_name,
            upload_path,
            override_name,
            override_description,
            is_beta,
            replace_latest,
            false,
            None,
        )
        .await
    }

    pub async fn process_draft_upload(
        &self,
        file_name: &str,
        upload_path: &Path,
        override_name: Option<String>,
        override_description: Option<String>,
        is_beta: bool,
    ) -> Result<UploadResult, UploadError> {
        self.process_upload(
            file_name,
            upload_path,
            override_name,
            override_description,
            is_beta,
            false,
            true,
            None,
        )
        .await
    }

    pub async fn process_queued_upload(
        &self,
        job: &super::upload_jobs::UploadJob,
    ) -> Result<UploadResult, UploadError> {
        self.process_upload(
            &job.file_name,
            Path::new(&job.input_path),
            job.override_name.clone(),
            job.override_description.clone(),
            job.is_beta,
            false,
            true,
            Some(&job.id),
        )
        .await
    }

    #[allow(clippy::too_many_arguments)]
    async fn process_upload(
        &self,
        file_name: &str,
        upload_path: &Path,
        override_name: Option<String>,
        override_description: Option<String>,
        is_beta: bool,
        replace_latest: bool,
        draft: bool,
        job_id: Option<&str>,
    ) -> Result<UploadResult, UploadError> {
        // 1. Validate file size
        let size = tokio::fs::metadata(upload_path).await?.len();
        if size > self.max_size {
            return Err(UploadError::FileTooLarge {
                max: self.max_size,
                actual: size,
            });
        }

        // 2. Detect file type
        let file_type = detect_file_type(upload_path, file_name);

        // 3. Create temp directory for processing
        let temp_dir = self.storage.create_temp_dir()?;

        // 4. Get APK path (convert if AAB)
        let apk_path = match file_type {
            FileType::Apk => upload_path.to_path_buf(),
            FileType::Aab => {
                let converter = self.aab_converter.as_ref().ok_or_else(|| {
                    UploadError::AabNotSupported(
                        "bundletool not configured. Set BUNDLETOOL_PATH and ensure Java is available.".to_string(),
                    )
                })?;

                // Convert to APK
                converter
                    .convert(upload_path, temp_dir.path(), self.max_size)
                    .await?
            }
            FileType::Unknown => {
                return Err(UploadError::InvalidFileType);
            }
        };

        let apk_size = tokio::fs::metadata(&apk_path).await?.len();
        if apk_size > self.max_size {
            return Err(UploadError::FileTooLarge {
                max: self.max_size,
                actual: apk_size,
            });
        }

        // 6. Parse APK metadata
        if draft {
            let file = std::fs::File::open(&apk_path)?;
            let archive = ZipArchive::new(file).map_err(|_| UploadError::InvalidFileType)?;
            if archive
                .file_names()
                .any(|name| name.starts_with("assets/paravoid/"))
            {
                return Err(UploadError::UnsupportedShell);
            }
        }
        let metadata = self.apk_parser.parse(&apk_path).await?;

        // 7. Check for existing version
        if db::version_exists(&self.db, &metadata.package_name, metadata.version_code).await? {
            return Err(UploadError::VersionExists {
                package_name: metadata.package_name,
                version_code: metadata.version_code,
            });
        }

        if draft {
            let used: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM published_apk_identities WHERE package_name = ? AND version_code = ?)")
                .bind(&metadata.package_name).bind(metadata.version_code).fetch_one(&self.db).await.map_err(AppError::Database)?;
            if used {
                return Err(UploadError::VersionExists {
                    package_name: metadata.package_name,
                    version_code: metadata.version_code,
                });
            }
        }

        // 8. Calculate SHA-256
        let sha256 = calculate_sha256_file(&apk_path).await?;

        // 9. Check if this is a new app
        let existing_app = db::get_app(&self.db, &metadata.package_name).await?;
        let is_new_app = existing_app.is_none();

        // 10. Save APK file
        let stored_apk_path = match self.storage.save_apk_file(
            &metadata.package_name,
            metadata.version_code,
            &apk_path,
        ) {
            Ok(path) => path,
            Err(StorageError::AlreadyExists(_)) if job_id.is_some() => {
                let stored = self
                    .storage
                    .get_apk_path(&metadata.package_name, metadata.version_code);
                if calculate_sha256_file(&stored).await? != sha256 {
                    return Err(UploadError::VersionExists {
                        package_name: metadata.package_name,
                        version_code: metadata.version_code,
                    });
                }
                format!(
                    "apks/{}/{}.apk",
                    metadata.package_name, metadata.version_code
                )
            }
            Err(StorageError::AlreadyExists(_)) => {
                return Err(UploadError::VersionExists {
                    package_name: metadata.package_name,
                    version_code: metadata.version_code,
                });
            }
            Err(error) => return Err(error.into()),
        };

        // 11. Save icon if available (best-effort)
        let icon_path =
            if let Some(icon_data) = metadata.icon_data.as_ref().filter(|_| !draft || is_new_app) {
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
                &stored_apk_path,
                apk_size as i64,
                &sha256,
                metadata.min_sdk,
                &app_name,
                override_name.as_deref(),
                override_description.as_deref(),
                icon_path.as_deref(),
                is_new_app,
                is_beta,
                replace_latest,
                draft,
                job_id,
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

        if let Some(previous_version) = db_result? {
            if let Err(error) = self
                .storage
                .delete_apk(&metadata.package_name, previous_version)
            {
                warn!(
                    "Failed to delete superseded APK {}/{}: {}",
                    metadata.package_name, previous_version, error
                );
            }
        }

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
        is_beta: bool,
        replace_latest: bool,
        draft: bool,
        job_id: Option<&str>,
    ) -> Result<Option<i64>, UploadError> {
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
        } else if !draft {
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

        if icon_path.is_some() {
            sqlx::query("UPDATE apps SET icon_revision = 1 WHERE package_name = ?")
                .bind(package_name)
                .execute(&mut *tx)
                .await
                .map_err(AppError::Database)?;
        }

        // Acquire SQLite's write lock even when no app metadata changed.
        sqlx::query("UPDATE apps SET updated_at = datetime('now') WHERE package_name = ?")
            .bind(package_name)
            .execute(&mut *tx)
            .await
            .map_err(AppError::Database)?;
        let previous_version = if replace_latest {
            let latest: Option<i64> = sqlx::query_scalar(
                "SELECT MAX(version_code) FROM app_versions WHERE package_name = ? AND is_beta = ?",
            )
            .bind(package_name)
            .bind(is_beta)
            .fetch_one(&mut *tx)
            .await
            .map_err(AppError::Database)?;
            if latest.is_some_and(|latest| version_code <= latest) {
                return Err(UploadError::ReplacementNotNewer);
            }
            latest
        } else {
            None
        };

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
            is_beta,
        )
        .await?;

        if draft {
            sqlx::query("UPDATE app_versions SET publication_state = 'draft', proposed_name = ?, proposed_description = ?, published_at = NULL WHERE package_name = ? AND version_code = ?")
                .bind(override_name).bind(override_description).bind(package_name).bind(version_code)
                .execute(&mut *tx).await.map_err(AppError::Database)?;
        } else {
            sqlx::query("INSERT OR IGNORE INTO published_apk_identities(package_name, version_code, sha256) VALUES (?, ?, ?)")
                .bind(package_name).bind(version_code).bind(sha256).execute(&mut *tx).await.map_err(AppError::Database)?;
        }

        if let Some(previous_version) = previous_version {
            sqlx::query("DELETE FROM app_versions WHERE package_name = ? AND version_code = ?")
                .bind(package_name)
                .bind(previous_version)
                .execute(&mut *tx)
                .await
                .map_err(AppError::Database)?;
        }

        if let Some(id) = job_id {
            let result = serde_json::json!({"package_name": package_name, "version_code": version_code, "version_name": version_name, "app_name": app_name, "is_new_app": is_new_app});
            sqlx::query("UPDATE upload_jobs SET status = 'ready', result_json = ?, updated_at = datetime('now') WHERE id = ? AND status = 'validating'")
                .bind(result.to_string()).bind(id).execute(&mut *tx).await.map_err(AppError::Database)?;
        }
        // Commit transaction
        tx.commit().await.map_err(AppError::Database)?;

        Ok(previous_version)
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

/// Detect file type from the archive directory and/or filename.
fn detect_file_type(path: &Path, filename: &str) -> FileType {
    let file = match std::fs::File::open(path) {
        Ok(file) => file,
        Err(_) => return FileType::Unknown,
    };
    let archive = match ZipArchive::new(file) {
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

pub(crate) async fn calculate_sha256_file(path: &Path) -> Result<String, std::io::Error> {
    let mut file = tokio::fs::File::open(path).await?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];

    loop {
        let bytes_read = file.read(&mut buffer).await?;
        if bytes_read == 0 {
            break;
        }
        hasher.update(&buffer[..bytes_read]);
    }

    Ok(hex::encode(hasher.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use tempfile::tempdir;
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
        let temp = tempdir().unwrap();
        let path = temp.path().join("upload");
        std::fs::write(&path, data).unwrap();
        assert_eq!(detect_file_type(&path, "app.apk"), FileType::Apk);
        assert_eq!(detect_file_type(&path, "app.zip"), FileType::Apk);
    }

    #[test]
    fn test_detect_file_type_aab() {
        let data = create_fake_aab();
        let temp = tempdir().unwrap();
        let path = temp.path().join("upload");
        std::fs::write(&path, data).unwrap();
        assert_eq!(detect_file_type(&path, "app.aab"), FileType::Aab);
        assert_eq!(detect_file_type(&path, "app.zip"), FileType::Aab);
    }

    #[test]
    fn test_detect_file_type_unknown() {
        let temp = tempdir().unwrap();
        let path = temp.path().join("upload");
        std::fs::write(&path, b"not a zip").unwrap();
        assert_eq!(detect_file_type(&path, "test.txt"), FileType::Unknown);
        std::fs::write(&path, b"").unwrap();
        assert_eq!(detect_file_type(&path, "empty"), FileType::Unknown);
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

        let temp = tempdir().unwrap();
        let path = temp.path().join("upload");
        std::fs::write(&path, buf).unwrap();
        assert_eq!(detect_file_type(&path, "app.apk"), FileType::Apk);
        assert_eq!(detect_file_type(&path, "app.aab"), FileType::Aab);
        assert_eq!(detect_file_type(&path, "app.zip"), FileType::Unknown);
    }
}
