//! Integration tests for the upload service
//!
//! The suite uses a deterministic fake aapt2 executable, so it runs in CI
//! without an Android SDK installation.

use sqlx::sqlite::SqlitePoolOptions;
use std::io::Cursor;
use std::path::PathBuf;
use tempfile::TempDir;
use zip::write::SimpleFileOptions;

use lellostore_backend::services::{ApkParser, StorageService, UploadError, UploadService};

/// Creates a minimal APK-shaped archive for testing.
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

#[cfg(unix)]
fn create_fake_aapt2(
    temp_dir: &TempDir,
    package_name: &str,
    version_code: i64,
    version_name: &str,
    app_name: &str,
) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;

    let path = temp_dir.path().join(format!("fake-aapt2-{version_code}"));
    let output = format!(
        "package: name='{package_name}' versionCode='{version_code}' versionName='{version_name}'\n\
         sdkVersion:'24'\n\
         application-label:'{app_name}'\n"
    );
    std::fs::write(&path, format!("#!/bin/sh\nprintf '%s' \"{output}\"\n")).unwrap();
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
    path
}

fn create_upload_file(temp_dir: &TempDir, name: &str) -> PathBuf {
    let path = temp_dir.path().join(name);
    std::fs::write(&path, create_fake_apk()).unwrap();
    path
}

/// Creates a minimal fake AAB for testing.
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

async fn setup_test_env() -> (TempDir, sqlx::SqlitePool, StorageService) {
    let temp_dir = TempDir::new().expect("Failed to create temp dir");
    let db_path = temp_dir.path().join("test.db");
    let storage_path = temp_dir.path().join("storage");

    std::fs::create_dir_all(&storage_path).unwrap();

    let database_url = format!("sqlite:{}?mode=rwc", db_path.display());

    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect(&database_url)
        .await
        .expect("Failed to create pool");

    // Run migrations
    sqlx::migrate!("./migrations")
        .run(&pool)
        .await
        .expect("Failed to run migrations");

    let storage = StorageService::new(storage_path);

    (temp_dir, pool, storage)
}

#[tokio::test]
async fn test_upload_file_too_large() {
    let (temp_dir, pool, storage) = setup_test_env().await;

    // Create upload service with very small max size
    let upload_service = UploadService::new(
        storage,
        ApkParser::new(PathBuf::from("/nonexistent/aapt2")), // Won't be used
        None,
        pool,
        100, // 100 bytes max
    );

    // Create data larger than max size
    let large_data = vec![0u8; 200];
    let upload_path = temp_dir.path().join("upload.apk");
    std::fs::write(&upload_path, large_data).unwrap();

    let result = upload_service
        .process_upload_file("test.apk", &upload_path, None, None)
        .await;

    match result {
        Err(UploadError::FileTooLarge { max, actual }) => {
            assert_eq!(max, 100);
            assert_eq!(actual, 200);
        }
        other => panic!("Expected FileTooLarge error, got: {:?}", other),
    }
}

#[tokio::test]
async fn test_upload_invalid_file_type() {
    let (temp_dir, pool, storage) = setup_test_env().await;

    let upload_service = UploadService::new(
        storage,
        ApkParser::new(PathBuf::from("/nonexistent/aapt2")),
        None,
        pool,
        100 * 1024 * 1024, // 100MB
    );

    // Create invalid data (not a ZIP)
    let invalid_data = b"this is not an apk or aab file".to_vec();
    let upload_path = temp_dir.path().join("upload.txt");
    std::fs::write(&upload_path, invalid_data).unwrap();

    let result = upload_service
        .process_upload_file("test.txt", &upload_path, None, None)
        .await;

    match result {
        Err(UploadError::InvalidFileType) => (),
        other => panic!("Expected InvalidFileType error, got: {:?}", other),
    }
}

#[tokio::test]
async fn test_upload_aab_without_converter() {
    let (temp_dir, pool, storage) = setup_test_env().await;

    let upload_service = UploadService::new(
        storage,
        ApkParser::new(PathBuf::from("/nonexistent/aapt2")),
        None, // No AAB converter
        pool,
        100 * 1024 * 1024,
    );

    let aab_data = create_fake_aab();
    let upload_path = temp_dir.path().join("upload.aab");
    std::fs::write(&upload_path, aab_data).unwrap();

    let result = upload_service
        .process_upload_file("test.aab", &upload_path, None, None)
        .await;

    match result {
        Err(UploadError::AabNotSupported(_)) => (),
        other => panic!("Expected AabNotSupported error, got: {:?}", other),
    }
}

/// Test the full upload flow, including database and file persistence.
#[cfg(unix)]
#[tokio::test]
async fn test_upload_apk_persists_app_version_and_file() {
    let (temp_dir, pool, storage) = setup_test_env().await;
    let parser = ApkParser::new(create_fake_aapt2(
        &temp_dir,
        "com.example.upload",
        1,
        "1.0.0",
        "Uploaded App",
    ));
    let upload_service = UploadService::new(storage, parser, None, pool.clone(), 100 * 1024 * 1024);
    let upload_path = create_upload_file(&temp_dir, "upload.apk");

    let result = upload_service
        .process_upload_file(
            "upload.apk",
            &upload_path,
            None,
            Some("Test description".to_string()),
        )
        .await
        .unwrap();

    assert!(result.is_new_app);
    assert_eq!(result.package_name, "com.example.upload");
    assert_eq!(result.version_code, 1);
    let app = lellostore_backend::db::get_app(&pool, "com.example.upload")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(app.name, "Uploaded App");
    assert_eq!(app.description.as_deref(), Some("Test description"));
    let versions = lellostore_backend::db::get_app_versions(&pool, "com.example.upload")
        .await
        .unwrap();
    assert_eq!(versions.len(), 1);
    assert!(temp_dir
        .path()
        .join("storage/apks/com.example.upload/1.apk")
        .is_file());
}

/// Test duplicate version rejection
#[cfg(unix)]
#[tokio::test]
async fn test_upload_duplicate_version() {
    let (temp_dir, pool, storage) = setup_test_env().await;
    let parser = ApkParser::new(create_fake_aapt2(
        &temp_dir,
        "com.example.duplicate",
        7,
        "7.0",
        "Duplicate App",
    ));
    let upload_service = UploadService::new(storage, parser, None, pool, 100 * 1024 * 1024);
    let upload_path = create_upload_file(&temp_dir, "duplicate.apk");
    upload_service
        .process_upload_file("duplicate.apk", &upload_path, None, None)
        .await
        .unwrap();

    let result = upload_service
        .process_upload_file("duplicate.apk", &upload_path, None, None)
        .await;

    assert!(matches!(
        result,
        Err(UploadError::VersionExists {
            package_name,
            version_code: 7,
        }) if package_name == "com.example.duplicate"
    ));
}

/// Test uploading a new version of an existing app
#[cfg(unix)]
#[tokio::test]
async fn test_upload_new_version() {
    let (temp_dir, pool, storage) = setup_test_env().await;
    let version_one = UploadService::new(
        storage.clone(),
        ApkParser::new(create_fake_aapt2(
            &temp_dir,
            "com.example.versioned",
            1,
            "1.0",
            "Versioned App",
        )),
        None,
        pool.clone(),
        100 * 1024 * 1024,
    );
    let first_upload = create_upload_file(&temp_dir, "version-1.apk");
    let first = version_one
        .process_upload_file("version-1.apk", &first_upload, None, None)
        .await
        .unwrap();
    assert!(first.is_new_app);

    let version_two = UploadService::new(
        storage,
        ApkParser::new(create_fake_aapt2(
            &temp_dir,
            "com.example.versioned",
            2,
            "2.0",
            "Versioned App",
        )),
        None,
        pool.clone(),
        100 * 1024 * 1024,
    );
    let second_upload = create_upload_file(&temp_dir, "version-2.apk");
    let second = version_two
        .process_upload_file("version-2.apk", &second_upload, None, None)
        .await
        .unwrap();

    assert!(!second.is_new_app);
    let versions = lellostore_backend::db::get_app_versions(&pool, "com.example.versioned")
        .await
        .unwrap();
    assert_eq!(
        versions
            .iter()
            .map(|version| version.version_code)
            .collect::<Vec<_>>(),
        vec![2, 1],
    );
}
