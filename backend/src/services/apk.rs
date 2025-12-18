use image::imageops::FilterType;
use image::ImageFormat;
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use thiserror::Error;
use tokio::process::Command;
use tracing::warn;
use zip::ZipArchive;

#[derive(Debug, Error)]
pub enum ApkError {
    #[error("aapt2 not found. Please install Android SDK build-tools or set AAPT2_PATH")]
    Aapt2NotFound,

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

#[derive(Debug, Clone)]
pub struct ApkMetadata {
    pub package_name: String,
    pub version_code: i64,
    pub version_name: String,
    pub min_sdk: i64,
    pub app_name: String,
    pub icon_data: Option<Vec<u8>>,
}

pub struct ApkParser {
    aapt2_path: PathBuf,
}

impl ApkParser {
    /// Create a new APK parser with explicit aapt2 path
    pub fn new(aapt2_path: PathBuf) -> Self {
        Self { aapt2_path }
    }

    /// Detect aapt2 location from common paths or PATH
    pub fn detect_aapt2() -> Result<PathBuf, ApkError> {
        // Check common locations
        let common_paths = [
            // Android SDK locations
            "/usr/local/lib/android/sdk/build-tools/34.0.0/aapt2",
            "/usr/local/lib/android/sdk/build-tools/33.0.0/aapt2",
            "/opt/android-sdk/build-tools/34.0.0/aapt2",
            "/opt/android-sdk/build-tools/33.0.0/aapt2",
            // Homebrew on macOS
            "/opt/homebrew/bin/aapt2",
            // Linux package manager
            "/usr/bin/aapt2",
            "/usr/bin/aapt",
        ];

        for path in common_paths {
            let p = PathBuf::from(path);
            if p.exists() {
                return Ok(p);
            }
        }

        // Check ANDROID_HOME environment variable
        if let Ok(android_home) = std::env::var("ANDROID_HOME") {
            let build_tools = PathBuf::from(&android_home).join("build-tools");
            if build_tools.exists() {
                // Find the newest version
                if let Ok(entries) = std::fs::read_dir(&build_tools) {
                    let mut versions: Vec<_> = entries
                        .filter_map(|e| e.ok())
                        .filter(|e| e.path().is_dir())
                        .collect();
                    versions.sort_by_key(|e| std::cmp::Reverse(e.file_name()));

                    for version in versions {
                        let aapt2 = version.path().join("aapt2");
                        if aapt2.exists() {
                            return Ok(aapt2);
                        }
                    }
                }
            }
        }

        // Check PATH
        if let Ok(output) = std::process::Command::new("which").arg("aapt2").output() {
            if output.status.success() {
                let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if !path.is_empty() {
                    return Ok(PathBuf::from(path));
                }
            }
        }

        Err(ApkError::Aapt2NotFound)
    }

    /// Create parser with auto-detected aapt2
    pub fn auto_detect() -> Result<Self, ApkError> {
        let aapt2_path = Self::detect_aapt2()?;
        Ok(Self::new(aapt2_path))
    }

    /// Parse APK metadata using aapt2
    pub async fn parse(&self, apk_path: &Path) -> Result<ApkMetadata, ApkError> {
        // Run aapt2 dump badging
        let output = Command::new(&self.aapt2_path)
            .arg("dump")
            .arg("badging")
            .arg(apk_path)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
            .await?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(ApkError::Aapt2Failed(stderr.to_string()));
        }

        let stdout = String::from_utf8_lossy(&output.stdout);
        let parsed = parse_aapt2_output(&stdout)?;

        // Extract icon if path is available
        let icon_data = if let Some(icon_path) = &parsed.icon_path {
            match self.extract_icon(apk_path, icon_path).await {
                Ok(data) => Some(data),
                Err(e) => {
                    tracing::warn!("Failed to extract icon: {}", e);
                    None
                }
            }
        } else {
            None
        };

        Ok(ApkMetadata {
            package_name: parsed.package_name,
            version_code: parsed.version_code,
            version_name: parsed.version_name,
            min_sdk: parsed.min_sdk,
            app_name: parsed.app_name,
            icon_data,
        })
    }

    /// Extract icon from APK (which is a ZIP file)
    async fn extract_icon(&self, apk_path: &Path, icon_path: &str) -> Result<Vec<u8>, ApkError> {
        let apk_data = tokio::fs::read(apk_path).await?;

        // Open APK as ZIP
        let cursor = Cursor::new(apk_data);
        let mut archive =
            ZipArchive::new(cursor).map_err(|e| ApkError::InvalidApk(e.to_string()))?;

        // Find and read the icon file
        let mut icon_file = archive
            .by_name(icon_path)
            .map_err(|e| ApkError::IconError(format!("Icon not found: {}", e)))?;

        let mut icon_data = Vec::new();
        std::io::Read::read_to_end(&mut icon_file, &mut icon_data)
            .map_err(|e| ApkError::IconError(e.to_string()))?;

        // Convert to PNG and resize to 192x192
        let processed = process_icon(&icon_data)?;

        Ok(processed)
    }
}

/// Parsed output from aapt2 dump badging
struct ParsedAapt2Output {
    package_name: String,
    version_code: i64,
    version_name: String,
    min_sdk: i64,
    app_name: String,
    icon_path: Option<String>,
}

/// Parse aapt2 dump badging output
fn parse_aapt2_output(output: &str) -> Result<ParsedAapt2Output, ApkError> {
    let mut package_name = None;
    let mut version_code = None;
    let mut version_name = None;
    let mut min_sdk = None;
    let mut app_name = None;
    let mut icon_paths: Vec<(i32, String)> = Vec::new();

    for line in output.lines() {
        // package: name='com.example.app' versionCode='10' versionName='1.0.0' ...
        if line.starts_with("package:") {
            if let Some(name) = extract_quoted_value(line, "name=") {
                package_name = Some(name);
            }
            if let Some(code) = extract_quoted_value(line, "versionCode=") {
                version_code = code.parse().ok();
            }
            if let Some(name) = extract_quoted_value(line, "versionName=") {
                version_name = Some(name);
            }
        }

        // sdkVersion:'26'
        if line.starts_with("sdkVersion:") {
            if let Some(sdk) = extract_quoted_value_colon(line) {
                min_sdk = sdk.parse().ok();
            }
        }

        // application-label:'My App'
        if line.starts_with("application-label:") {
            if let Some(label) = extract_quoted_value_colon(line) {
                app_name = Some(label);
            }
        }

        // application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'
        if line.starts_with("application-icon-") {
            if let Some((density, path)) = parse_icon_line(line) {
                icon_paths.push((density, path));
            }
        }
    }

    // Choose the highest density icon
    icon_paths.sort_by(|a, b| b.0.cmp(&a.0));
    let icon_path = icon_paths.into_iter().next().map(|(_, path)| path);

    let package_name =
        package_name.ok_or_else(|| ApkError::ParseError("Missing package name".to_string()))?;
    let version_code: i64 =
        version_code.ok_or_else(|| ApkError::ParseError("Missing version code".to_string()))?;

    let version_name = version_name.unwrap_or_else(|| {
        warn!(
            "APK {} missing version_name, using version_code as fallback",
            package_name
        );
        version_code.to_string()
    });

    let min_sdk = min_sdk.unwrap_or_else(|| {
        warn!(
            "APK {} missing minSdkVersion, defaulting to 21 (Android 5.0)",
            package_name
        );
        21
    });

    let app_name = app_name.unwrap_or_else(|| {
        warn!(
            "APK {} missing application-label, using package name as fallback",
            package_name
        );
        package_name.clone()
    });

    // Validate icon path doesn't contain path traversal
    let icon_path = icon_path.filter(|path| {
        if path.contains("..") || path.starts_with('/') {
            warn!(
                "APK {} has suspicious icon path '{}', ignoring",
                package_name, path
            );
            false
        } else {
            true
        }
    });

    Ok(ParsedAapt2Output {
        package_name,
        version_code,
        version_name,
        min_sdk,
        app_name,
        icon_path,
    })
}

/// Extract a quoted value after a key like: name='value'
fn extract_quoted_value(line: &str, key: &str) -> Option<String> {
    let start = line.find(key)? + key.len();
    let rest = &line[start..];

    let quote_char = rest.chars().next()?;
    if quote_char != '\'' && quote_char != '"' {
        return None;
    }

    let value_start = 1;
    let value_end = rest[value_start..].find(quote_char)? + value_start;

    Some(rest[value_start..value_end].to_string())
}

/// Extract a quoted value after a colon like: sdkVersion:'26'
fn extract_quoted_value_colon(line: &str) -> Option<String> {
    let colon_pos = line.find(':')?;
    let rest = &line[colon_pos + 1..];

    let quote_char = rest.chars().next()?;
    if quote_char != '\'' && quote_char != '"' {
        return None;
    }

    let value_start = 1;
    let value_end = rest[value_start..].find(quote_char)? + value_start;

    Some(rest[value_start..value_end].to_string())
}

/// Parse an icon line like: application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'
fn parse_icon_line(line: &str) -> Option<(i32, String)> {
    // Extract density from "application-icon-640"
    let prefix = "application-icon-";
    if !line.starts_with(prefix) {
        return None;
    }

    let colon_pos = line.find(':')?;
    let density_str = &line[prefix.len()..colon_pos];
    let density: i32 = density_str.parse().ok()?;

    let path = extract_quoted_value_colon(line)?;

    Some((density, path))
}

/// Process icon: convert to PNG and resize to 192x192
fn process_icon(data: &[u8]) -> Result<Vec<u8>, ApkError> {
    // Try to load the image (supports PNG, WebP, etc.)
    let img = image::load_from_memory(data)
        .map_err(|e| ApkError::IconError(format!("Invalid image: {}", e)))?;

    // Resize to 192x192 (standard launcher icon size)
    let resized = img.resize_exact(192, 192, FilterType::Lanczos3);

    // Convert to PNG
    let mut output = Vec::new();
    resized
        .write_to(&mut Cursor::new(&mut output), ImageFormat::Png)
        .map_err(|e| ApkError::IconError(format!("Failed to encode PNG: {}", e)))?;

    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_quoted_value() {
        let line = "package: name='com.example.app' versionCode='10' versionName='1.0.0'";

        assert_eq!(
            extract_quoted_value(line, "name="),
            Some("com.example.app".to_string())
        );
        assert_eq!(
            extract_quoted_value(line, "versionCode="),
            Some("10".to_string())
        );
        assert_eq!(
            extract_quoted_value(line, "versionName="),
            Some("1.0.0".to_string())
        );
        assert_eq!(extract_quoted_value(line, "missing="), None);
    }

    #[test]
    fn test_extract_quoted_value_colon() {
        assert_eq!(
            extract_quoted_value_colon("sdkVersion:'26'"),
            Some("26".to_string())
        );
        assert_eq!(
            extract_quoted_value_colon("application-label:'My App'"),
            Some("My App".to_string())
        );
        assert_eq!(
            extract_quoted_value_colon("application-label:\"My App\""),
            Some("My App".to_string())
        );
    }

    #[test]
    fn test_parse_icon_line() {
        let line = "application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'";
        let result = parse_icon_line(line);
        assert_eq!(
            result,
            Some((640, "res/mipmap-xxxhdpi-v4/ic_launcher.png".to_string()))
        );

        let line2 = "application-icon-160:'res/mipmap-mdpi/ic_launcher.png'";
        let result2 = parse_icon_line(line2);
        assert_eq!(
            result2,
            Some((160, "res/mipmap-mdpi/ic_launcher.png".to_string()))
        );
    }

    #[test]
    fn test_parse_aapt2_output() {
        let output = r#"package: name='com.example.myapp' versionCode='42' versionName='2.1.0' compileSdkVersion='34'
sdkVersion:'26'
targetSdkVersion:'34'
application-label:'My Awesome App'
application-icon-160:'res/mipmap-mdpi-v4/ic_launcher.png'
application-icon-240:'res/mipmap-hdpi-v4/ic_launcher.png'
application-icon-320:'res/mipmap-xhdpi-v4/ic_launcher.png'
application-icon-480:'res/mipmap-xxhdpi-v4/ic_launcher.png'
application-icon-640:'res/mipmap-xxxhdpi-v4/ic_launcher.png'
"#;

        let parsed = parse_aapt2_output(output).unwrap();

        assert_eq!(parsed.package_name, "com.example.myapp");
        assert_eq!(parsed.version_code, 42);
        assert_eq!(parsed.version_name, "2.1.0");
        assert_eq!(parsed.min_sdk, 26);
        assert_eq!(parsed.app_name, "My Awesome App");
        assert_eq!(
            parsed.icon_path,
            Some("res/mipmap-xxxhdpi-v4/ic_launcher.png".to_string())
        );
    }

    #[test]
    fn test_parse_aapt2_output_minimal() {
        let output = "package: name='com.test' versionCode='1'\n";

        let parsed = parse_aapt2_output(output).unwrap();

        assert_eq!(parsed.package_name, "com.test");
        assert_eq!(parsed.version_code, 1);
        assert_eq!(parsed.version_name, "1"); // Falls back to version code
        assert_eq!(parsed.min_sdk, 21); // Default
        assert_eq!(parsed.app_name, "com.test"); // Falls back to package name
        assert_eq!(parsed.icon_path, None);
    }
}
