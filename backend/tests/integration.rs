use axum::http::StatusCode;
use axum_test::TestServer;

mod common;

use common::{create_test_app, create_test_context, create_test_metrics_app};

/// Helper to insert a test app into the database
async fn insert_test_app(
    pool: &sqlx::SqlitePool,
    package_name: &str,
    name: &str,
    icon_path: Option<&str>,
) {
    // Force synchronous mode to ensure visibility across connections
    sqlx::query("PRAGMA synchronous = FULL")
        .execute(pool)
        .await
        .unwrap();

    sqlx::query(
        "INSERT INTO apps (package_name, name, description, icon_path, created_at, updated_at) VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))"
    )
    .bind(package_name)
    .bind(name)
    .bind("Test app description")
    .bind(icon_path)
    .execute(pool)
    .await
    .unwrap();
}

/// Helper to insert a test version into the database
async fn insert_test_version(
    pool: &sqlx::SqlitePool,
    package_name: &str,
    version_code: i64,
    version_name: &str,
    apk_path: &str,
    size: i64,
) {
    sqlx::query(
        "INSERT INTO app_versions (package_name, version_code, version_name, apk_path, size, sha256, min_sdk, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))"
    )
    .bind(package_name)
    .bind(version_code)
    .bind(version_name)
    .bind(apk_path)
    .bind(size)
    .bind("0000000000000000000000000000000000000000000000000000000000000000")
    .bind(21)
    .execute(pool)
    .await
    .unwrap();
}

#[tokio::test]
async fn test_health_endpoint() {
    let (_temp_dir, app) = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/health").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let body: serde_json::Value = response.json();
    assert_eq!(body["status"], "healthy");
}

#[tokio::test]
async fn test_apps_list_empty() {
    let (_temp_dir, app) = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/api/apps").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let body: serde_json::Value = response.json();
    assert_eq!(body["apps"], serde_json::json!([]));
}

#[tokio::test]
async fn test_app_not_found() {
    let (_temp_dir, app) = create_test_app().await;
    let server = TestServer::new(app).unwrap();

    let response = server.get("/api/apps/com.nonexistent").await;
    assert_eq!(response.status_code(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn test_apps_list_after_insert() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Insert app
    insert_test_app(&ctx.pool, "com.test.app", "Test App", None).await;

    // List should now include the app
    let response = server.get("/api/apps").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let body: serde_json::Value = response.json();
    let apps = body["apps"].as_array().unwrap();

    // Debug output
    if apps.is_empty() {
        panic!("Apps list is empty after insert! Body: {}", body);
    }

    assert_eq!(apps.len(), 1);
    assert_eq!(apps[0]["packageName"], "com.test.app");
    // Verify camelCase format
    assert!(apps[0]["iconUrl"].is_string());
    // latestVersion is null when no versions exist
    assert!(apps[0]["latestVersion"].is_null());
}

#[tokio::test]
async fn test_metrics_endpoint() {
    let app = create_test_metrics_app();
    let server = TestServer::new(app).unwrap();

    let response = server.get("/metrics").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    // Check that the content type is correct for Prometheus
    let content_type = response.headers().get("content-type").unwrap();
    assert!(content_type.to_str().unwrap().starts_with("text/plain"));

    // The metrics endpoint should return some content (metrics format)
    let body = response.text();
    // The registry will have metrics registered, check for the presence of our custom metrics
    // After register_metrics() is called, these will be in the output
    assert!(
        body.contains("lellostore") || body.contains("homelab"),
        "Expected metrics with lellostore or homelab prefix, got: {}",
        body
    );
}

// ============================================================================
// File Serving Tests
// ============================================================================

#[tokio::test]
async fn test_get_icon_success() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Create icon file
    let icons_dir = ctx.storage_path.join("icons");
    std::fs::create_dir_all(&icons_dir).unwrap();
    let icon_data = b"fake PNG data for testing";
    std::fs::write(icons_dir.join("com.example.app.png"), icon_data).unwrap();

    // Insert app with icon
    insert_test_app(
        &ctx.pool,
        "com.example.app",
        "Test App",
        Some("icons/com.example.app.png"),
    )
    .await;

    let response = server.get("/api/apps/com.example.app/icon").await;
    assert_eq!(response.status_code(), StatusCode::OK);

    let content_type = response.headers().get("content-type").unwrap();
    assert_eq!(content_type.to_str().unwrap(), "image/png");

    let body = response.as_bytes();
    assert_eq!(body.as_ref(), icon_data);
}

#[tokio::test]
async fn test_get_icon_not_found_no_app() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    let response = server.get("/api/apps/com.nonexistent/icon").await;
    assert_eq!(response.status_code(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn test_get_icon_not_found_no_icon() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Insert app without icon
    insert_test_app(&ctx.pool, "com.example.app", "Test App", None).await;

    let response = server.get("/api/apps/com.example.app/icon").await;
    assert_eq!(response.status_code(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn test_download_apk_success() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Create APK file
    let apk_dir = ctx.storage_path.join("apks").join("com.example.app");
    std::fs::create_dir_all(&apk_dir).unwrap();
    let apk_data = b"fake APK data for testing - this is test content";
    std::fs::write(apk_dir.join("1.apk"), apk_data).unwrap();

    // Insert app and version
    insert_test_app(&ctx.pool, "com.example.app", "Test App", None).await;
    insert_test_version(
        &ctx.pool,
        "com.example.app",
        1,
        "1.0.0",
        "apks/com.example.app/1.apk",
        apk_data.len() as i64,
    )
    .await;

    let response = server
        .get("/api/apps/com.example.app/versions/1/apk")
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);

    // Check headers
    let content_type = response.headers().get("content-type").unwrap();
    assert_eq!(
        content_type.to_str().unwrap(),
        "application/vnd.android.package-archive"
    );

    let accept_ranges = response.headers().get("accept-ranges").unwrap();
    assert_eq!(accept_ranges.to_str().unwrap(), "bytes");

    let content_disposition = response.headers().get("content-disposition").unwrap();
    assert!(content_disposition
        .to_str()
        .unwrap()
        .contains("com.example.app-1.0.0.apk"));

    // Check body
    let body = response.as_bytes();
    assert_eq!(body.as_ref(), apk_data);
}

#[tokio::test]
async fn test_download_apk_range_request() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Create APK file with known content
    let apk_dir = ctx.storage_path.join("apks").join("com.example.app");
    std::fs::create_dir_all(&apk_dir).unwrap();
    let apk_data = b"0123456789ABCDEFGHIJ"; // 20 bytes
    std::fs::write(apk_dir.join("1.apk"), apk_data).unwrap();

    // Insert app and version
    insert_test_app(&ctx.pool, "com.example.app", "Test App", None).await;
    insert_test_version(
        &ctx.pool,
        "com.example.app",
        1,
        "1.0.0",
        "apks/com.example.app/1.apk",
        apk_data.len() as i64,
    )
    .await;

    // Request first 10 bytes
    let response = server
        .get("/api/apps/com.example.app/versions/1/apk")
        .add_header("Range".parse().unwrap(), "bytes=0-9".parse().unwrap())
        .await;

    assert_eq!(response.status_code(), StatusCode::PARTIAL_CONTENT);

    let content_range = response.headers().get("content-range").unwrap();
    assert_eq!(content_range.to_str().unwrap(), "bytes 0-9/20");

    let body = response.as_bytes();
    assert_eq!(body.as_ref(), b"0123456789");
}

#[tokio::test]
async fn test_download_apk_range_suffix() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Create APK file
    let apk_dir = ctx.storage_path.join("apks").join("com.example.app");
    std::fs::create_dir_all(&apk_dir).unwrap();
    let apk_data = b"0123456789ABCDEFGHIJ"; // 20 bytes
    std::fs::write(apk_dir.join("1.apk"), apk_data).unwrap();

    // Insert app and version
    insert_test_app(&ctx.pool, "com.example.app", "Test App", None).await;
    insert_test_version(
        &ctx.pool,
        "com.example.app",
        1,
        "1.0.0",
        "apks/com.example.app/1.apk",
        apk_data.len() as i64,
    )
    .await;

    // Request last 5 bytes
    let response = server
        .get("/api/apps/com.example.app/versions/1/apk")
        .add_header("Range".parse().unwrap(), "bytes=-5".parse().unwrap())
        .await;

    assert_eq!(response.status_code(), StatusCode::PARTIAL_CONTENT);

    let content_range = response.headers().get("content-range").unwrap();
    assert_eq!(content_range.to_str().unwrap(), "bytes 15-19/20");

    let body = response.as_bytes();
    assert_eq!(body.as_ref(), b"FGHIJ");
}

#[tokio::test]
async fn test_download_apk_range_invalid() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Create APK file
    let apk_dir = ctx.storage_path.join("apks").join("com.example.app");
    std::fs::create_dir_all(&apk_dir).unwrap();
    let apk_data = b"0123456789"; // 10 bytes
    std::fs::write(apk_dir.join("1.apk"), apk_data).unwrap();

    // Insert app and version
    insert_test_app(&ctx.pool, "com.example.app", "Test App", None).await;
    insert_test_version(
        &ctx.pool,
        "com.example.app",
        1,
        "1.0.0",
        "apks/com.example.app/1.apk",
        apk_data.len() as i64,
    )
    .await;

    // Request range beyond file size
    let response = server
        .get("/api/apps/com.example.app/versions/1/apk")
        .add_header("Range".parse().unwrap(), "bytes=100-200".parse().unwrap())
        .await;

    assert_eq!(response.status_code(), StatusCode::RANGE_NOT_SATISFIABLE);

    let content_range = response.headers().get("content-range").unwrap();
    assert_eq!(content_range.to_str().unwrap(), "bytes */10");
}

#[tokio::test]
async fn test_download_apk_not_found() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Insert app but no version
    insert_test_app(&ctx.pool, "com.example.app", "Test App", None).await;

    let response = server
        .get("/api/apps/com.example.app/versions/999/apk")
        .await;
    assert_eq!(response.status_code(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn test_get_app_with_versions() {
    let ctx = create_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Insert app and multiple versions
    insert_test_app(&ctx.pool, "com.example.app", "Test App", None).await;
    insert_test_version(
        &ctx.pool,
        "com.example.app",
        1,
        "1.0.0",
        "apks/com.example.app/1.apk",
        1000,
    )
    .await;
    insert_test_version(
        &ctx.pool,
        "com.example.app",
        2,
        "2.0.0",
        "apks/com.example.app/2.apk",
        2000,
    )
    .await;

    // First verify the app exists in the database via the pool
    let count: (i64,) = sqlx::query_as("SELECT COUNT(*) FROM apps WHERE package_name = ?")
        .bind("com.example.app")
        .fetch_one(&ctx.pool)
        .await
        .unwrap();
    assert_eq!(count.0, 1, "App should exist in database");

    let response = server.get("/api/apps/com.example.app").await;

    // Debug: print the response body if not OK
    if response.status_code() != StatusCode::OK {
        let body = response.text();
        panic!(
            "Expected OK but got {:?}, body: {}",
            response.status_code(),
            body
        );
    }

    let body: serde_json::Value = response.json();
    // New format: flat structure with camelCase
    assert_eq!(body["packageName"], "com.example.app");
    assert_eq!(body["name"], "Test App");
    assert!(body["iconUrl"].is_string());

    let versions = body["versions"].as_array().unwrap();
    assert_eq!(versions.len(), 2);
    // Verify version fields are camelCase
    assert!(versions[0]["versionCode"].is_number());
    assert!(versions[0]["apkUrl"].is_string());
}
