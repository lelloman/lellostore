#[cfg(test)]
use std::io::Cursor;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;
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

    #[error("AAB conversion timed out")]
    TimedOut,

    #[error("Converted APK is too large (max: {max} bytes, got at least: {actual} bytes)")]
    OutputTooLarge { max: u64, actual: u64 },

    #[error("Invalid AAB file: not a valid Android App Bundle")]
    InvalidAab,

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

pub struct AabConverter {
    bundletool_path: PathBuf,
    java_path: PathBuf,
    command_timeout: Duration,
}

const DEFAULT_BUNDLETOOL_TIMEOUT: Duration = Duration::from_secs(5 * 60);

impl AabConverter {
    /// Create a new AAB converter with explicit paths
    pub fn new(bundletool_path: PathBuf, java_path: PathBuf) -> Self {
        Self::with_timeout(bundletool_path, java_path, DEFAULT_BUNDLETOOL_TIMEOUT)
    }

    pub fn with_timeout(
        bundletool_path: PathBuf,
        java_path: PathBuf,
        command_timeout: Duration,
    ) -> Self {
        Self {
            bundletool_path,
            java_path,
            command_timeout,
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
    pub async fn convert(
        &self,
        aab_path: &Path,
        output_dir: &Path,
        max_output_size: u64,
    ) -> Result<PathBuf, AabError> {
        // Validate input is an AAB
        if !is_valid_aab(aab_path).await? {
            return Err(AabError::InvalidAab);
        }

        let apks_path = output_dir.join("output.apks");
        let apk_path = output_dir.join("universal.apk");

        // Run bundletool to create .apks file
        let mut command = Command::new(&self.java_path);
        command
            .arg("-jar")
            .arg(&self.bundletool_path)
            .arg("build-apks")
            .arg(format!("--bundle={}", aab_path.display()))
            .arg(format!("--output={}", apks_path.display()))
            .arg("--mode=universal")
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let output = tokio::time::timeout(self.command_timeout, command.output())
            .await
            .map_err(|_| AabError::TimedOut)??;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(AabError::ConversionFailed(stderr.to_string()));
        }

        // Extract universal.apk from the .apks file (which is a ZIP)
        extract_universal_apk(&apks_path, &apk_path, max_output_size).await?;

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
    let path = path.to_path_buf();
    tokio::task::spawn_blocking(move || {
        let file = std::fs::File::open(path)?;
        let archive = match ZipArchive::new(file) {
            Ok(archive) => archive,
            Err(_) => return Ok(false),
        };
        let has_bundle_config = archive.file_names().any(|name| name == "BundleConfig.pb");
        Ok(has_bundle_config)
    })
    .await
    .map_err(|error| AabError::ConversionFailed(format!("AAB validation task failed: {error}")))?
}

/// Extract universal.apk from the .apks archive
async fn extract_universal_apk(
    apks_path: &Path,
    output_path: &Path,
    max_size: u64,
) -> Result<(), AabError> {
    let apks_path = apks_path.to_path_buf();
    let output_path = output_path.to_path_buf();
    tokio::task::spawn_blocking(move || {
        let archive_file = std::fs::File::open(&apks_path)?;
        let mut archive = ZipArchive::new(archive_file)
            .map_err(|error| AabError::ConversionFailed(format!("Invalid .apks file: {error}")))?;

        for index in 0..archive.len() {
            let file = archive.by_index(index).map_err(|error| {
                AabError::ConversionFailed(format!("Failed to read .apks archive: {error}"))
            })?;
            if file.name() != "universal.apk" {
                continue;
            }
            if file.size() > max_size {
                return Err(AabError::OutputTooLarge {
                    max: max_size,
                    actual: file.size(),
                });
            }

            let mut output = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&output_path)?;
            let copied = match std::io::copy(&mut file.take(max_size + 1), &mut output) {
                Ok(copied) => copied,
                Err(error) => {
                    drop(output);
                    let _ = std::fs::remove_file(&output_path);
                    return Err(AabError::Io(error));
                }
            };
            if copied > max_size {
                drop(output);
                let _ = std::fs::remove_file(&output_path);
                return Err(AabError::OutputTooLarge {
                    max: max_size,
                    actual: copied,
                });
            }
            return Ok(());
        }

        Err(AabError::ConversionFailed(
            "universal.apk not found in .apks archive".to_string(),
        ))
    })
    .await
    .map_err(|error| AabError::ConversionFailed(format!("APK extraction task failed: {error}")))?
}

/// Synchronously extract universal.apk from archive data
#[cfg(test)]
fn extract_apk_from_archive(data: &[u8], max_size: u64) -> Result<Vec<u8>, AabError> {
    let cursor = Cursor::new(data);

    let mut archive = ZipArchive::new(cursor)
        .map_err(|e| AabError::ConversionFailed(format!("Invalid .apks file: {}", e)))?;

    // Find universal.apk in the archive
    for i in 0..archive.len() {
        let mut file = archive.by_index(i).map_err(|e| {
            AabError::ConversionFailed(format!("Failed to read .apks archive: {}", e))
        })?;

        if file.name() == "universal.apk" {
            if file.size() > max_size {
                return Err(AabError::OutputTooLarge {
                    max: max_size,
                    actual: file.size(),
                });
            }
            let mut apk_data = Vec::with_capacity(file.size() as usize);
            std::io::Read::take(&mut file, max_size + 1)
                .read_to_end(&mut apk_data)
                .map_err(|e| AabError::ConversionFailed(format!("Failed to extract APK: {}", e)))?;

            if apk_data.len() as u64 > max_size {
                return Err(AabError::OutputTooLarge {
                    max: max_size,
                    actual: apk_data.len() as u64,
                });
            }

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
    use std::time::{Duration, Instant};
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

    #[test]
    fn test_extract_universal_apk_enforces_decompressed_size_limit() {
        let mut archive_data = Vec::new();
        {
            let mut zip = zip::ZipWriter::new(Cursor::new(&mut archive_data));
            zip.start_file("universal.apk", SimpleFileOptions::default())
                .unwrap();
            std::io::Write::write_all(&mut zip, &[0_u8; 1025]).unwrap();
            zip.finish().unwrap();
        }

        let result = extract_apk_from_archive(&archive_data, 1024);

        assert!(matches!(result, Err(AabError::OutputTooLarge { .. })));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn bundletool_process_is_terminated_when_it_times_out() {
        use std::os::unix::fs::PermissionsExt;

        let temp = tempdir().unwrap();
        let fake_java = temp.path().join("slow-java");
        std::fs::write(&fake_java, "#!/bin/sh\nexec sleep 10\n").unwrap();
        std::fs::set_permissions(&fake_java, std::fs::Permissions::from_mode(0o755)).unwrap();
        let bundletool = temp.path().join("bundletool.jar");
        std::fs::write(&bundletool, b"fake").unwrap();
        let aab = temp.path().join("app.aab");
        let file = std::fs::File::create(&aab).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        zip.start_file("BundleConfig.pb", SimpleFileOptions::default())
            .unwrap();
        zip.finish().unwrap();
        let converter =
            AabConverter::with_timeout(bundletool, fake_java, Duration::from_millis(50));

        let started = Instant::now();
        let result = converter.convert(&aab, temp.path(), 1024).await;

        assert!(matches!(result, Err(AabError::TimedOut)));
        assert!(started.elapsed() < Duration::from_secs(2));
    }
}
