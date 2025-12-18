use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use thiserror::Error;
use uuid::Uuid;

#[derive(Debug, Error)]
pub enum StorageError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("File not found: {0}")]
    NotFound(String),

    #[error("Invalid package name: {0}")]
    InvalidPackageName(String),
}

/// Validate that a package name is safe to use in file paths.
/// Android package names follow the format: [a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+
/// We check for path traversal characters and reasonable length.
fn validate_package_name(name: &str) -> Result<(), StorageError> {
    // Check for path traversal attempts
    if name.contains('/') || name.contains('\\') || name.contains("..") {
        return Err(StorageError::InvalidPackageName(
            "Package name contains path separators or traversal sequences".to_string(),
        ));
    }

    // Check for null bytes (could truncate paths)
    if name.contains('\0') {
        return Err(StorageError::InvalidPackageName(
            "Package name contains null bytes".to_string(),
        ));
    }

    // Check reasonable length (Android has a limit of ~255 chars for package name)
    if name.is_empty() || name.len() > 255 {
        return Err(StorageError::InvalidPackageName(
            "Package name must be 1-255 characters".to_string(),
        ));
    }

    // Basic format check: must contain at least one dot (com.example format)
    if !name.contains('.') {
        return Err(StorageError::InvalidPackageName(
            "Package name must contain at least one dot (e.g., com.example)".to_string(),
        ));
    }

    Ok(())
}

pub struct StorageService {
    base_path: PathBuf,
}

impl StorageService {
    pub fn new(base_path: PathBuf) -> Self {
        Self { base_path }
    }

    /// Create a new temporary directory for processing
    pub fn create_temp_dir(&self) -> Result<TempDir, StorageError> {
        let temp_base = self.base_path.join("temp");
        std::fs::create_dir_all(&temp_base)?;

        let dir_name = Uuid::new_v4().to_string();
        let temp_path = temp_base.join(&dir_name);
        std::fs::create_dir_all(&temp_path)?;

        Ok(TempDir { path: temp_path })
    }

    /// Save APK to permanent storage, returns the relative path
    pub fn save_apk(
        &self,
        package_name: &str,
        version_code: i64,
        data: &[u8],
    ) -> Result<String, StorageError> {
        validate_package_name(package_name)?;

        let apk_dir = self.base_path.join("apks").join(package_name);
        std::fs::create_dir_all(&apk_dir)?;

        let file_name = format!("{}.apk", version_code);
        let file_path = apk_dir.join(&file_name);
        std::fs::write(&file_path, data)?;

        Ok(format!("apks/{}/{}", package_name, file_name))
    }

    /// Save icon to permanent storage, returns the relative path
    pub fn save_icon(&self, package_name: &str, data: &[u8]) -> Result<String, StorageError> {
        validate_package_name(package_name)?;

        let icons_dir = self.base_path.join("icons");
        std::fs::create_dir_all(&icons_dir)?;

        let file_name = format!("{}.png", package_name);
        let file_path = icons_dir.join(&file_name);
        std::fs::write(&file_path, data)?;

        Ok(format!("icons/{}", file_name))
    }

    /// Delete APK file
    pub fn delete_apk(&self, package_name: &str, version_code: i64) -> Result<(), StorageError> {
        validate_package_name(package_name)?;

        let file_path = self
            .base_path
            .join("apks")
            .join(package_name)
            .join(format!("{}.apk", version_code));

        if file_path.exists() {
            std::fs::remove_file(&file_path)?;
        }

        // Clean up empty directory
        let dir_path = self.base_path.join("apks").join(package_name);
        if dir_path.exists() && dir_path.read_dir()?.next().is_none() {
            std::fs::remove_dir(&dir_path)?;
        }

        Ok(())
    }

    /// Delete icon file
    pub fn delete_icon(&self, package_name: &str) -> Result<(), StorageError> {
        validate_package_name(package_name)?;

        let file_path = self
            .base_path
            .join("icons")
            .join(format!("{}.png", package_name));

        if file_path.exists() {
            std::fs::remove_file(&file_path)?;
        }

        Ok(())
    }

    /// Delete all files for a package (all versions + icon)
    pub fn delete_package(&self, package_name: &str) -> Result<(), StorageError> {
        validate_package_name(package_name)?;

        // Delete all APKs
        let apk_dir = self.base_path.join("apks").join(package_name);
        if apk_dir.exists() {
            std::fs::remove_dir_all(&apk_dir)?;
        }

        // Delete icon
        self.delete_icon(package_name)?;

        Ok(())
    }

    /// Get absolute path for serving APK files
    pub fn get_apk_path(&self, package_name: &str, version_code: i64) -> PathBuf {
        self.base_path
            .join("apks")
            .join(package_name)
            .join(format!("{}.apk", version_code))
    }

    /// Get absolute path for serving icon files
    pub fn get_icon_path(&self, package_name: &str) -> PathBuf {
        self.base_path
            .join("icons")
            .join(format!("{}.png", package_name))
    }

    /// Calculate SHA-256 checksum
    pub fn calculate_sha256(data: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(data);
        let result = hasher.finalize();
        hex::encode(result)
    }
}

/// A temporary directory that is automatically deleted when dropped
pub struct TempDir {
    path: PathBuf,
}

impl TempDir {
    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_calculate_sha256() {
        let data = b"hello world";
        let hash = StorageService::calculate_sha256(data);
        assert_eq!(
            hash,
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        );
    }

    #[test]
    fn test_save_and_get_apk_path() {
        let temp = tempdir().unwrap();
        let storage = StorageService::new(temp.path().to_path_buf());

        let data = b"fake apk data";
        let path = storage.save_apk("com.example.app", 1, data).unwrap();

        assert_eq!(path, "apks/com.example.app/1.apk");

        let abs_path = storage.get_apk_path("com.example.app", 1);
        assert!(abs_path.exists());

        let read_data = std::fs::read(&abs_path).unwrap();
        assert_eq!(read_data, data);
    }

    #[test]
    fn test_save_and_get_icon_path() {
        let temp = tempdir().unwrap();
        let storage = StorageService::new(temp.path().to_path_buf());

        let data = b"fake icon data";
        let path = storage.save_icon("com.example.app", data).unwrap();

        assert_eq!(path, "icons/com.example.app.png");

        let abs_path = storage.get_icon_path("com.example.app");
        assert!(abs_path.exists());

        let read_data = std::fs::read(&abs_path).unwrap();
        assert_eq!(read_data, data);
    }

    #[test]
    fn test_delete_apk() {
        let temp = tempdir().unwrap();
        let storage = StorageService::new(temp.path().to_path_buf());

        storage.save_apk("com.example.app", 1, b"data").unwrap();
        storage.save_apk("com.example.app", 2, b"data").unwrap();

        // Delete version 1
        storage.delete_apk("com.example.app", 1).unwrap();
        assert!(!storage.get_apk_path("com.example.app", 1).exists());
        assert!(storage.get_apk_path("com.example.app", 2).exists());

        // Delete version 2 - directory should be cleaned up
        storage.delete_apk("com.example.app", 2).unwrap();
        assert!(!temp.path().join("apks/com.example.app").exists());
    }

    #[test]
    fn test_delete_package() {
        let temp = tempdir().unwrap();
        let storage = StorageService::new(temp.path().to_path_buf());

        storage.save_apk("com.example.app", 1, b"data").unwrap();
        storage.save_apk("com.example.app", 2, b"data").unwrap();
        storage.save_icon("com.example.app", b"icon").unwrap();

        storage.delete_package("com.example.app").unwrap();

        assert!(!storage.get_apk_path("com.example.app", 1).exists());
        assert!(!storage.get_apk_path("com.example.app", 2).exists());
        assert!(!storage.get_icon_path("com.example.app").exists());
    }

    #[test]
    fn test_temp_dir_cleanup() {
        let temp = tempdir().unwrap();
        let storage = StorageService::new(temp.path().to_path_buf());

        let temp_path;
        {
            let temp_dir = storage.create_temp_dir().unwrap();
            temp_path = temp_dir.path().to_path_buf();
            assert!(temp_path.exists());
        }
        // TempDir dropped, directory should be deleted
        assert!(!temp_path.exists());
    }

    #[test]
    fn test_validate_package_name_valid() {
        assert!(validate_package_name("com.example.app").is_ok());
        assert!(validate_package_name("org.test.myapp").is_ok());
        assert!(validate_package_name("a.b").is_ok());
    }

    #[test]
    fn test_validate_package_name_path_traversal() {
        assert!(validate_package_name("../etc/passwd").is_err());
        assert!(validate_package_name("com.example/../etc").is_err());
        assert!(validate_package_name("com/example/app").is_err());
        assert!(validate_package_name("com\\example\\app").is_err());
    }

    #[test]
    fn test_validate_package_name_empty_or_long() {
        assert!(validate_package_name("").is_err());
        assert!(validate_package_name(&"a".repeat(300)).is_err());
    }

    #[test]
    fn test_validate_package_name_no_dot() {
        assert!(validate_package_name("nodots").is_err());
    }

    #[test]
    fn test_validate_package_name_null_byte() {
        assert!(validate_package_name("com.example\0.app").is_err());
    }

    #[test]
    fn test_save_apk_rejects_invalid_package() {
        let temp = tempdir().unwrap();
        let storage = StorageService::new(temp.path().to_path_buf());

        let result = storage.save_apk("../etc/passwd", 1, b"data");
        assert!(result.is_err());
        assert!(matches!(
            result.unwrap_err(),
            StorageError::InvalidPackageName(_)
        ));
    }
}
