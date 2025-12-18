use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use thiserror::Error;
use tokio::process::Command;
use zip::ZipArchive;

#[derive(Debug, Error)]
pub enum AabError {
    #[error("bundletool not found. Please set BUNDLETOOL_PATH to the bundletool.jar location")]
    BundletoolNotFound,

    #[error("Java not found. bundletool requires Java 11+")]
    JavaNotFound,

    #[error("AAB conversion failed: {0}")]
    ConversionFailed(String),

    #[error("Invalid AAB file: not a valid Android App Bundle")]
    InvalidAab,

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

pub struct AabConverter {
    bundletool_path: PathBuf,
    java_path: PathBuf,
}

impl AabConverter {
    /// Create a new AAB converter with explicit paths
    pub fn new(bundletool_path: PathBuf, java_path: PathBuf) -> Self {
        Self {
            bundletool_path,
            java_path,
        }
    }

    /// Detect Java location from common paths or PATH
    pub fn detect_java() -> Result<PathBuf, AabError> {
        // Check JAVA_HOME
        if let Ok(java_home) = std::env::var("JAVA_HOME") {
            let java = PathBuf::from(&java_home).join("bin/java");
            if java.exists() {
                return Ok(java);
            }
        }

        // Check common locations
        let common_paths = [
            "/usr/bin/java",
            "/usr/local/bin/java",
            "/opt/java/bin/java",
            "/opt/homebrew/bin/java",
        ];

        for path in common_paths {
            let p = PathBuf::from(path);
            if p.exists() {
                return Ok(p);
            }
        }

        // Check PATH
        if let Ok(output) = std::process::Command::new("which").arg("java").output() {
            if output.status.success() {
                let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if !path.is_empty() {
                    return Ok(PathBuf::from(path));
                }
            }
        }

        Err(AabError::JavaNotFound)
    }

    /// Create converter with auto-detected Java and explicit bundletool path
    pub fn with_bundletool(bundletool_path: PathBuf) -> Result<Self, AabError> {
        let java_path = Self::detect_java()?;

        if !bundletool_path.exists() {
            return Err(AabError::BundletoolNotFound);
        }

        Ok(Self::new(bundletool_path, java_path))
    }

    /// Convert AAB to universal APK
    /// Returns path to the generated APK (in output_dir)
    pub async fn convert(&self, aab_path: &Path, output_dir: &Path) -> Result<PathBuf, AabError> {
        // Validate input is an AAB
        if !is_valid_aab(aab_path).await? {
            return Err(AabError::InvalidAab);
        }

        let apks_path = output_dir.join("output.apks");
        let apk_path = output_dir.join("universal.apk");

        // Run bundletool to create .apks file
        let output = Command::new(&self.java_path)
            .arg("-jar")
            .arg(&self.bundletool_path)
            .arg("build-apks")
            .arg(format!("--bundle={}", aab_path.display()))
            .arg(format!("--output={}", apks_path.display()))
            .arg("--mode=universal")
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
            .await?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(AabError::ConversionFailed(stderr.to_string()));
        }

        // Extract universal.apk from the .apks file (which is a ZIP)
        extract_universal_apk(&apks_path, &apk_path).await?;

        // Clean up the .apks file
        let _ = tokio::fs::remove_file(&apks_path).await;

        Ok(apk_path)
    }

    /// Check if this converter is available (bundletool and java exist)
    pub fn is_available(&self) -> bool {
        self.bundletool_path.exists() && self.java_path.exists()
    }
}

/// Check if a file is a valid AAB by looking for BundleConfig.pb
async fn is_valid_aab(path: &Path) -> Result<bool, AabError> {
    let data = tokio::fs::read(path).await?;
    let cursor = Cursor::new(data);

    let archive = match ZipArchive::new(cursor) {
        Ok(a) => a,
        Err(_) => return Ok(false),
    };

    // AAB files contain BundleConfig.pb
    let has_bundle_config = archive.file_names().any(|name| name == "BundleConfig.pb");
    Ok(has_bundle_config)
}

/// Extract universal.apk from the .apks archive
async fn extract_universal_apk(apks_path: &Path, output_path: &Path) -> Result<(), AabError> {
    let data = tokio::fs::read(apks_path).await?;

    // Extract APK data synchronously to avoid Send issues with ZipFile
    let apk_data = extract_apk_from_archive(&data)?;

    tokio::fs::write(output_path, &apk_data).await?;

    Ok(())
}

/// Synchronously extract universal.apk from archive data
fn extract_apk_from_archive(data: &[u8]) -> Result<Vec<u8>, AabError> {
    let cursor = Cursor::new(data);

    let mut archive = ZipArchive::new(cursor)
        .map_err(|e| AabError::ConversionFailed(format!("Invalid .apks file: {}", e)))?;

    // Find universal.apk in the archive
    for i in 0..archive.len() {
        let mut file = archive.by_index(i).map_err(|e| {
            AabError::ConversionFailed(format!("Failed to read .apks archive: {}", e))
        })?;

        if file.name() == "universal.apk" {
            let mut apk_data = Vec::new();
            std::io::Read::read_to_end(&mut file, &mut apk_data)
                .map_err(|e| AabError::ConversionFailed(format!("Failed to extract APK: {}", e)))?;

            return Ok(apk_data);
        }
    }

    Err(AabError::ConversionFailed(
        "universal.apk not found in .apks archive".to_string(),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;
    use zip::write::SimpleFileOptions;

    #[tokio::test]
    async fn test_is_valid_aab_with_invalid_file() {
        let temp = tempdir().unwrap();
        let fake_aab = temp.path().join("fake.aab");

        // Write a fake file that's not a ZIP
        tokio::fs::write(&fake_aab, b"not a zip file")
            .await
            .unwrap();

        let result = is_valid_aab(&fake_aab).await.unwrap();
        assert!(!result);
    }

    #[tokio::test]
    async fn test_is_valid_aab_with_regular_zip() {
        let temp = tempdir().unwrap();
        let fake_aab = temp.path().join("fake.aab");

        // Create a ZIP without BundleConfig.pb
        let file = std::fs::File::create(&fake_aab).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = SimpleFileOptions::default();
        zip.start_file("test.txt", options).unwrap();
        std::io::Write::write_all(&mut zip, b"test content").unwrap();
        zip.finish().unwrap();

        let result = is_valid_aab(&fake_aab).await.unwrap();
        assert!(!result);
    }

    #[tokio::test]
    async fn test_is_valid_aab_with_valid_aab() {
        let temp = tempdir().unwrap();
        let fake_aab = temp.path().join("fake.aab");

        // Create a ZIP with BundleConfig.pb
        let file = std::fs::File::create(&fake_aab).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = SimpleFileOptions::default();
        zip.start_file("BundleConfig.pb", options).unwrap();
        std::io::Write::write_all(&mut zip, b"fake bundle config").unwrap();
        zip.finish().unwrap();

        let result = is_valid_aab(&fake_aab).await.unwrap();
        assert!(result);
    }
}
