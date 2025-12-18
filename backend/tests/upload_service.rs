//! Integration tests for the upload service
//!
//! These tests require aapt2 to be available in PATH or configured.
//! Run with: cargo test --test upload_service -- --ignored

use sqlx::sqlite::SqlitePoolOptions;
use std::io::Cursor;
use std::path::PathBuf;
use tempfile::TempDir;
use zip::write::SimpleFileOptions;

use lellostore_backend::services::{ApkParser, StorageService, UploadError, UploadService};

/// Creates a minimal fake APK for testing.
/// Note: This won't pass aapt2 parsing, but can be used to test file type detection.
#[allow(dead_code)]
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
    let (_temp_dir, pool, storage) = setup_test_env().await;

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

    let result = upload_service
        .process_upload("test.apk", large_data, None, None)
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
    let (_temp_dir, pool, storage) = setup_test_env().await;

    let upload_service = UploadService::new(
        storage,
        ApkParser::new(PathBuf::from("/nonexistent/aapt2")),
        None,
        pool,
        100 * 1024 * 1024, // 100MB
    );

    // Create invalid data (not a ZIP)
    let invalid_data = b"this is not an apk or aab file".to_vec();

    let result = upload_service
        .process_upload("test.txt", invalid_data, None, None)
        .await;

    match result {
        Err(UploadError::InvalidFileType) => (),
        other => panic!("Expected InvalidFileType error, got: {:?}", other),
    }
}

#[tokio::test]
async fn test_upload_aab_without_converter() {
    let (_temp_dir, pool, storage) = setup_test_env().await;

    let upload_service = UploadService::new(
        storage,
        ApkParser::new(PathBuf::from("/nonexistent/aapt2")),
        None, // No AAB converter
        pool,
        100 * 1024 * 1024,
    );

    let aab_data = create_fake_aab();

    let result = upload_service
        .process_upload("test.aab", aab_data, None, None)
        .await;

    match result {
        Err(UploadError::AabNotSupported(_)) => (),
        other => panic!("Expected AabNotSupported error, got: {:?}", other),
    }
}

/// Test the full upload flow with a real APK.
/// Requires aapt2 to be available.
/// Run with: cargo test test_upload_real_apk -- --ignored
#[tokio::test]
#[ignore]
async fn test_upload_real_apk() {
    let (_temp_dir, pool, storage) = setup_test_env().await;

    // Try to detect aapt2
    let aapt2_path = match ApkParser::detect_aapt2() {
        Ok(path) => path,
        Err(e) => {
            eprintln!("aapt2 not found, skipping test: {}", e);
            return;
        }
    };

    let apk_parser = ApkParser::new(aapt2_path);
    let _upload_service =
        UploadService::new(storage, apk_parser, None, pool.clone(), 100 * 1024 * 1024);

    // This test would require a real APK file.
    // For now, we just verify the service can be created.
    // In a real test environment, you would:
    // 1. Read a test APK from tests/fixtures/
    // 2. Call process_upload
    // 3. Verify database records were created
    // 4. Verify files were stored correctly

    // Placeholder: this shows the test structure
    // let apk_data = std::fs::read("tests/fixtures/test.apk").expect("Test APK not found");
    // let result = upload_service.process_upload("test.apk", apk_data, None, None).await;
    // assert!(result.is_ok());

    println!("aapt2 found, upload service ready for real APK testing");
}

/// Test duplicate version rejection
/// Requires a real APK and aapt2
#[tokio::test]
#[ignore]
async fn test_upload_duplicate_version() {
    // This test would:
    // 1. Upload an APK
    // 2. Try to upload the same APK again
    // 3. Expect VersionExists error

    // Placeholder for when we have test fixtures
}

/// Test uploading a new version of an existing app
/// Requires real APKs with different version codes
#[tokio::test]
#[ignore]
async fn test_upload_new_version() {
    // This test would:
    // 1. Upload version 1 of an app
    // 2. Upload version 2 of the same app
    // 3. Verify both versions exist in database
    // 4. Verify is_new_app returns false for second upload
}
