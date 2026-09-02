//! End-to-end tests with authentication
//!
//! These tests use an embedded mock OIDC server to test the full authentication flow.
//! Each test is designed to be LONG - testing many operations in sequence rather than
//! one operation per test.

use axum::http::StatusCode;
use axum_test::TestServer;
use std::sync::Arc;

#[allow(dead_code)]
mod common;
mod mock_oidc;

use common::TestContext;
use mock_oidc::MockOidc;

/// Create a test context with authentication enabled
async fn create_auth_test_context() -> (TestContext, MockOidc) {
    use lellostore_backend::api::{routes::create_router, AppState};
    use lellostore_backend::auth::{AuthState, JwksCache, TokenValidator};
    use lellostore_backend::config::{Config, OidcConfig};
    use lellostore_backend::services::{ApkParser, StorageService, UploadService};
    use sqlx::sqlite::SqlitePoolOptions;
    use tempfile::TempDir;

    // Start mock OIDC
    let mock_oidc = MockOidc::start().await;

    let temp_dir = TempDir::new().expect("Failed to create temp dir");
    let db_path = temp_dir.path().join("test.db");
    let storage_path = temp_dir.path().join("storage");

    std::fs::create_dir_all(&storage_path).unwrap();
    std::fs::create_dir_all(storage_path.join("apks")).unwrap();
    std::fs::create_dir_all(storage_path.join("icons")).unwrap();

    let database_url = format!("sqlite:{}?mode=rwc&cache=shared", db_path.display());

    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect(&database_url)
        .await
        .expect("Failed to create pool");

    sqlx::migrate!("./migrations")
        .run(&pool)
        .await
        .expect("Failed to run migrations");

    let config = Config {
        listen_addr: "127.0.0.1:0".parse().unwrap(),
        metrics_addr: "127.0.0.1:0".parse().unwrap(),
        database_url,
        database_path: db_path,
        storage_path: storage_path.clone(),
        oidc: OidcConfig {
            issuer_url: mock_oidc.issuer_url(),
            audience: "lellostore".to_string(),
            admin_role: "admin".to_string(),
            role_claim_path: "realm_access.roles".to_string(),
        },
        aapt2_path: None,
        bundletool_path: None,
        java_path: None,
        max_upload_size: 100 * 1024 * 1024,
    };

    // Initialize auth using mock OIDC
    let client = reqwest::Client::new();
    let discovery = lellostore_backend::auth::fetch_discovery(&client, &mock_oidc.issuer_url())
        .await
        .unwrap();
    let jwks = Arc::new(JwksCache::new(discovery.jwks_uri, client).await.unwrap());
    let validator = Arc::new(TokenValidator::new(
        jwks,
        discovery.issuer,
        "lellostore".to_string(),
    ));
    let auth_state = AuthState::new(
        validator,
        "realm_access.roles".to_string(),
        "admin".to_string(),
    )
    .with_user_registry(pool.clone());

    let storage = Arc::new(StorageService::new(storage_path.clone()));
    #[cfg(unix)]
    let apk_parser = ApkParser::new(create_fake_aapt2(temp_dir.path()));
    #[cfg(not(unix))]
    let apk_parser = ApkParser::new(std::path::PathBuf::from("aapt2"));
    let upload_service = Arc::new(UploadService::new(
        (*storage).clone(),
        apk_parser,
        None,
        pool.clone(),
        config.max_upload_size,
    ));

    let state = AppState {
        db: pool.clone(),
        config: Arc::new(config),
        auth: Some(auth_state),
        upload_service,
        storage,
    };

    let router = create_router(state);

    let ctx = TestContext {
        temp_dir,
        router,
        pool,
        storage_path,
    };

    (ctx, mock_oidc)
}

/// Helper to create a minimal valid APK file
fn create_test_apk(package_name: &str, version_code: u32) -> Vec<u8> {
    use std::io::Write;
    use zip::write::FileOptions;
    use zip::ZipWriter;

    let mut buffer = Vec::new();
    {
        let mut zip = ZipWriter::new(std::io::Cursor::new(&mut buffer));

        // Create a minimal AndroidManifest.xml (binary XML format marker)
        let options = FileOptions::<()>::default();
        zip.start_file("AndroidManifest.xml", options).unwrap();
        // Binary XML header + minimal content
        let manifest = format!(
            r#"<?xml version="1.0"?>
<manifest package="{}" versionCode="{}" versionName="1.0.0">
</manifest>"#,
            package_name, version_code
        );
        zip.write_all(manifest.as_bytes()).unwrap();

        // Add classes.dex marker
        zip.start_file("classes.dex", options).unwrap();
        zip.write_all(b"dex\n035\0").unwrap();

        zip.finish().unwrap();
    }
    buffer
}

#[cfg(unix)]
fn create_fake_aapt2(temp_dir: &std::path::Path) -> std::path::PathBuf {
    use std::os::unix::fs::PermissionsExt;

    let path = temp_dir.join("fake-aapt2");
    std::fs::write(
        &path,
        "#!/bin/sh\ncat <<'EOF'\npackage: name='com.test.app' versionCode='1' versionName='1.0.0'\nsdkVersion:'24'\napplication-label:'Test App'\nEOF\n",
    )
    .unwrap();
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
    path
}

// =============================================================================
// LONG E2E TESTS - Each test performs many operations in sequence
// =============================================================================

/// Test the complete app lifecycle: upload, list, get, update, versions, delete
#[tokio::test]
async fn test_complete_app_lifecycle_with_auth() {
    let (ctx, mock_oidc) = create_auth_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Get tokens
    let admin_token = mock_oidc.get_admin_token();
    let user_token = mock_oidc.get_user_token();

    // =========================================================================
    // PHASE 1: Verify empty state
    // =========================================================================

    // User can list apps (empty)
    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
    let body: serde_json::Value = response.json();
    assert_eq!(body["apps"].as_array().unwrap().len(), 0);

    // =========================================================================
    // PHASE 2: Test authorization - user cannot access admin endpoints
    // =========================================================================

    // User cannot upload (should be 403 Forbidden)
    let apk_data = create_test_apk("com.test.app", 1);
    let response = server
        .post("/api/admin/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .multipart(axum_test::multipart::MultipartForm::new().add_part(
            "file",
            axum_test::multipart::Part::bytes(apk_data.clone()).file_name("test.apk"),
        ))
        .await;
    assert_eq!(
        response.status_code(),
        StatusCode::FORBIDDEN,
        "User should not be able to upload"
    );

    // =========================================================================
    // PHASE 3: Admin uploads first app
    // =========================================================================

    let response = server
        .post("/api/admin/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", admin_token).parse().unwrap(),
        )
        .multipart(axum_test::multipart::MultipartForm::new().add_part(
            "file",
            axum_test::multipart::Part::bytes(apk_data.clone()).file_name("test.apk"),
        ))
        .await;
    assert_eq!(response.status_code(), StatusCode::CREATED);
    let uploaded: serde_json::Value = response.json();
    assert_eq!(uploaded["package_name"], "com.test.app");
    assert_eq!(uploaded["version"]["version_code"], 1);

    let grant = server
        .put("/api/admin/users/test-user/apps/com.test.app")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", admin_token).parse().unwrap(),
        )
        .json(&serde_json::json!({"access_level": "stable"}))
        .await;
    assert_eq!(grant.status_code(), StatusCode::NO_CONTENT);

    // =========================================================================
    // PHASE 4: Verify app appears in list (if upload succeeded)
    // =========================================================================

    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
    let body: serde_json::Value = response.json();
    assert_eq!(body["apps"].as_array().unwrap().len(), 1);
    assert_eq!(body["apps"][0]["package_name"], "com.test.app");

    // =========================================================================
    // PHASE 5: Test unauthenticated access is denied
    // =========================================================================

    let response = server.get("/api/apps").await;
    assert_eq!(
        response.status_code(),
        StatusCode::UNAUTHORIZED,
        "Unauthenticated request should be denied"
    );

    let response = server.post("/api/admin/apps").await;
    assert_eq!(response.status_code(), StatusCode::UNAUTHORIZED);

    // =========================================================================
    // PHASE 6: Test with invalid token
    // =========================================================================

    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            "Bearer invalid.token.here".parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::UNAUTHORIZED);

    // =========================================================================
    // PHASE 7: Verify health check still works without auth
    // =========================================================================

    let response = server.get("/health").await;
    assert_eq!(response.status_code(), StatusCode::OK);
}

/// Test multiple apps and versions with database operations
#[tokio::test]
async fn test_multi_app_database_operations() {
    let (ctx, mock_oidc) = create_auth_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();
    let admin_token = mock_oidc.get_admin_token();
    let user_token = mock_oidc.get_user_token();

    // Insert test data directly into database (bypassing aapt2 requirement)
    sqlx::query(
        "INSERT INTO apps (package_name, name, description, icon_path, created_at, updated_at)
         VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))",
    )
    .bind("com.example.app1")
    .bind("App One")
    .bind("First test app")
    .bind::<Option<&str>>(None)
    .execute(&ctx.pool)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO apps (package_name, name, description, icon_path, created_at, updated_at)
         VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))",
    )
    .bind("com.example.app2")
    .bind("App Two")
    .bind("Second test app")
    .bind::<Option<&str>>(None)
    .execute(&ctx.pool)
    .await
    .unwrap();

    sqlx::query(
        "INSERT INTO apps (package_name, name, description, icon_path, created_at, updated_at)
         VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))",
    )
    .bind("com.example.app3")
    .bind("App Three")
    .bind("Third test app")
    .bind::<Option<&str>>(None)
    .execute(&ctx.pool)
    .await
    .unwrap();

    sqlx::query("INSERT INTO known_users (subject, email) VALUES ('test-user', 'user@test.local')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    for package_name in ["com.example.app1", "com.example.app2", "com.example.app3"] {
        sqlx::query(
            "INSERT INTO user_app_grants (user_subject, package_name, access_level) VALUES ('test-user', ?, 'beta')",
        )
        .bind(package_name)
        .execute(&ctx.pool)
        .await
        .unwrap();
    }

    // Add versions to app1
    for i in 1..=3 {
        sqlx::query(
            "INSERT INTO app_versions (package_name, version_code, version_name, apk_path, size, sha256, min_sdk, uploaded_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))"
        )
        .bind("com.example.app1")
        .bind(i as i64)
        .bind(format!("1.0.{}", i))
        .bind(format!("apks/com.example.app1/{}.apk", i))
        .bind(1000 * i as i64)
        .bind("0".repeat(64))
        .bind(21)
        .execute(&ctx.pool)
        .await
        .unwrap();
    }

    // Add one version to app2
    sqlx::query(
        "INSERT INTO app_versions (package_name, version_code, version_name, apk_path, size, sha256, min_sdk, uploaded_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))"
    )
    .bind("com.example.app2")
    .bind(1i64)
    .bind("1.0.0")
    .bind("apks/com.example.app2/1.apk")
    .bind(5000i64)
    .bind("0".repeat(64))
    .bind(26)
    .execute(&ctx.pool)
    .await
    .unwrap();

    // =========================================================================
    // PHASE 1: List all apps
    // =========================================================================

    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
    let body: serde_json::Value = response.json();
    let apps = body["apps"].as_array().unwrap();
    assert_eq!(apps.len(), 3, "Should have 3 apps");

    // Verify app1 has latest version info
    let app1 = apps
        .iter()
        .find(|a| a["package_name"] == "com.example.app1")
        .unwrap();
    assert_eq!(app1["name"], "App One");
    assert!(app1["latest_version"].is_object());
    assert_eq!(app1["latest_version"]["version_code"], 3);

    // Verify app3 has no versions
    let app3 = apps
        .iter()
        .find(|a| a["package_name"] == "com.example.app3")
        .unwrap();
    assert!(
        app3["latest_version"].is_null(),
        "App3 should have no versions"
    );

    // =========================================================================
    // PHASE 2: Get app details with versions
    // =========================================================================

    let response = server
        .get("/api/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
    let body: serde_json::Value = response.json();
    assert_eq!(body["package_name"], "com.example.app1");
    assert_eq!(body["name"], "App One");
    let versions = body["versions"].as_array().unwrap();
    assert_eq!(versions.len(), 3, "App1 should have 3 versions");

    // Versions should be sorted by version_code descending
    assert_eq!(versions[0]["version_code"], 3);
    assert_eq!(versions[1]["version_code"], 2);
    assert_eq!(versions[2]["version_code"], 1);

    // =========================================================================
    // PHASE 3: Admin updates app metadata
    // =========================================================================

    let response = server
        .put("/api/admin/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", admin_token).parse().unwrap(),
        )
        .json(&serde_json::json!({
            "name": "Updated App One",
            "description": "New description"
        }))
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
    let body: serde_json::Value = response.json();
    assert_eq!(body["name"], "Updated App One");
    assert_eq!(body["description"], "New description");

    // Verify change persisted
    let response = server
        .get("/api/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    let body: serde_json::Value = response.json();
    assert_eq!(body["name"], "Updated App One");

    // =========================================================================
    // PHASE 4: User cannot update app
    // =========================================================================

    let response = server
        .put("/api/admin/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .json(&serde_json::json!({
            "name": "Hacker Was Here"
        }))
        .await;
    assert_eq!(response.status_code(), StatusCode::FORBIDDEN);

    // Verify name unchanged
    let response = server
        .get("/api/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    let body: serde_json::Value = response.json();
    assert_eq!(
        body["name"], "Updated App One",
        "Name should not have changed"
    );

    // =========================================================================
    // PHASE 5: Admin deletes a version
    // =========================================================================

    let response = server
        .delete("/api/admin/apps/com.example.app1/versions/1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", admin_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::NO_CONTENT);

    // Verify version deleted
    let response = server
        .get("/api/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    let body: serde_json::Value = response.json();
    let versions = body["versions"].as_array().unwrap();
    assert_eq!(versions.len(), 2, "Should have 2 versions after deletion");

    // =========================================================================
    // PHASE 6: Admin deletes entire app
    // =========================================================================

    let response = server
        .delete("/api/admin/apps/com.example.app2")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", admin_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::NO_CONTENT);

    // Verify app deleted
    let response = server
        .get("/api/apps/com.example.app2")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::NOT_FOUND);

    // Verify list updated
    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    let body: serde_json::Value = response.json();
    let apps = body["apps"].as_array().unwrap();
    assert_eq!(apps.len(), 2, "Should have 2 apps after deletion");

    // =========================================================================
    // PHASE 7: User cannot delete
    // =========================================================================

    let response = server
        .delete("/api/admin/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::FORBIDDEN);

    let response = server
        .delete("/api/admin/apps/com.example.app1/versions/2")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::FORBIDDEN);

    // Verify nothing deleted
    let response = server
        .get("/api/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);

    // =========================================================================
    // PHASE 8: Delete remaining versions to auto-delete app
    // =========================================================================

    // Delete version 2
    let response = server
        .delete("/api/admin/apps/com.example.app1/versions/2")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", admin_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::NO_CONTENT);

    // Delete version 3 (last one) - should also delete the app
    let response = server
        .delete("/api/admin/apps/com.example.app1/versions/3")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", admin_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::NO_CONTENT);

    // Verify app auto-deleted
    let response = server
        .get("/api/apps/com.example.app1")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    assert_eq!(
        response.status_code(),
        StatusCode::NOT_FOUND,
        "App should be auto-deleted when last version is removed"
    );

    // =========================================================================
    // PHASE 9: Final state check
    // =========================================================================

    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", user_token).parse().unwrap(),
        )
        .await;
    let body: serde_json::Value = response.json();
    let apps = body["apps"].as_array().unwrap();
    assert_eq!(apps.len(), 1, "Should have only app3 remaining");
    assert_eq!(apps[0]["package_name"], "com.example.app3");
}

/// Test token expiration and refresh scenarios
#[tokio::test]
async fn test_token_expiration_handling() {
    let (ctx, mock_oidc) = create_auth_test_context().await;
    let server = TestServer::new(ctx.router).unwrap();

    // Get a valid token
    let valid_token = mock_oidc.get_user_token();

    // Test with valid token works
    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", valid_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);

    // Test with expired token
    let expired_token = mock_oidc.get_expired_token();
    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", expired_token).parse().unwrap(),
        )
        .await;
    assert_eq!(
        response.status_code(),
        StatusCode::UNAUTHORIZED,
        "Expired token should be rejected"
    );

    // Test with wrong audience
    let wrong_aud_token = mock_oidc.get_token_with_audience("wrong-app");
    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", wrong_aud_token).parse().unwrap(),
        )
        .await;
    assert_eq!(
        response.status_code(),
        StatusCode::UNAUTHORIZED,
        "Wrong audience should be rejected"
    );

    // Valid token should still work
    let response = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", valid_token).parse().unwrap(),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
}

#[tokio::test]
async fn failed_database_delete_does_not_remove_app_files() {
    let (ctx, mock_oidc) = create_auth_test_context().await;
    let apk_dir = ctx.storage_path.join("apks/com.example.atomic");
    std::fs::create_dir_all(&apk_dir).unwrap();
    let apk_path = apk_dir.join("1.apk");
    std::fs::write(&apk_path, b"apk contents").unwrap();

    sqlx::query(
        "INSERT INTO apps (package_name, name, created_at, updated_at) \
         VALUES ('com.example.atomic', 'Atomic App', datetime('now'), datetime('now'))",
    )
    .execute(&ctx.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO app_versions \
         (package_name, version_code, version_name, apk_path, size, sha256, min_sdk, uploaded_at) \
         VALUES ('com.example.atomic', 1, '1.0', 'apks/com.example.atomic/1.apk', 12, 'hash', 21, datetime('now'))",
    )
    .execute(&ctx.pool)
    .await
    .unwrap();
    sqlx::query(
        "CREATE TRIGGER prevent_app_delete BEFORE DELETE ON apps \
         BEGIN SELECT RAISE(ABORT, 'forced delete failure'); END",
    )
    .execute(&ctx.pool)
    .await
    .unwrap();

    let server = TestServer::new(ctx.router).unwrap();
    let response = server
        .delete("/api/admin/apps/com.example.atomic")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {}", mock_oidc.get_admin_token())
                .parse()
                .unwrap(),
        )
        .await;

    assert_eq!(response.status_code(), StatusCode::INTERNAL_SERVER_ERROR);
    let app_count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM apps WHERE package_name = 'com.example.atomic'")
            .fetch_one(&ctx.pool)
            .await
            .unwrap();
    assert_eq!(app_count, 1);
    assert!(
        apk_path.exists(),
        "a database failure must not leave a record pointing to a deleted APK",
    );
}

#[tokio::test]
async fn admin_manages_audited_dynamic_app_access_and_release_channels() {
    let (ctx, mock_oidc) = create_auth_test_context().await;
    sqlx::query("INSERT INTO apps (package_name, name) VALUES ('com.example.media', 'Media')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    sqlx::query(
        r#"INSERT INTO app_versions
           (package_name, version_code, version_name, apk_path, size, sha256, min_sdk)
           VALUES ('com.example.media', 7, '7.0', 'media.apk', 10, 'original-hash', 24)"#,
    )
    .execute(&ctx.pool)
    .await
    .unwrap();

    let server = TestServer::new(ctx.router).unwrap();
    let admin_token = mock_oidc.get_admin_token();
    let user_token = mock_oidc.get_user_token();

    // Successful authenticated requests populate the small OIDC-backed user directory.
    assert_eq!(
        server
            .get("/api/apps")
            .add_header(
                "Authorization".parse().unwrap(),
                format!("Bearer {user_token}").parse().unwrap(),
            )
            .await
            .status_code(),
        StatusCode::OK
    );

    let denied = server
        .post("/api/admin/app-groups")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"name": "Media apps"}))
        .await;
    assert_eq!(denied.status_code(), StatusCode::FORBIDDEN);

    let created = server
        .post("/api/admin/app-groups")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"name": "Media apps"}))
        .await;
    assert_eq!(created.status_code(), StatusCode::CREATED);
    let group: serde_json::Value = created.json();
    let group_id = group["id"].as_i64().unwrap();

    let duplicate = server
        .post("/api/admin/app-groups")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"name": "media APPS"}))
        .await;
    assert_eq!(duplicate.status_code(), StatusCode::CONFLICT);

    let set_group_grant = server
        .put(&format!(
            "/api/admin/app-groups/{group_id}/apps/com.example.media"
        ))
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"access_level": "stable"}))
        .await;
    assert_eq!(set_group_grant.status_code(), StatusCode::NO_CONTENT);

    let add_member = server
        .put(&format!("/api/admin/app-groups/{group_id}/users/test-user"))
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .await;
    assert_eq!(add_member.status_code(), StatusCode::NO_CONTENT);

    let stable_access = server
        .get("/api/admin/users/test-user/access")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .await;
    assert_eq!(stable_access.status_code(), StatusCode::OK);
    let access: serde_json::Value = stable_access.json();
    assert_eq!(access["effective_access"][0]["access_level"], "stable");
    assert_eq!(access["groups"][0]["name"], "Media apps");

    let set_direct_beta = server
        .put("/api/admin/users/test-user/apps/com.example.media")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"access_level": "beta"}))
        .await;
    assert_eq!(set_direct_beta.status_code(), StatusCode::NO_CONTENT);

    let beta_access = server
        .get("/api/admin/users/test-user/access")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .await;
    let access: serde_json::Value = beta_access.json();
    assert_eq!(access["effective_access"][0]["access_level"], "beta");

    let mark_beta = server
        .put("/api/admin/apps/com.example.media/versions/7")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"is_beta": true}))
        .await;
    assert_eq!(mark_beta.status_code(), StatusCode::NO_CONTENT);
    let row: (bool, String, String) = sqlx::query_as(
        "SELECT is_beta, apk_path, sha256 FROM app_versions WHERE package_name = 'com.example.media' AND version_code = 7",
    )
    .fetch_one(&ctx.pool)
    .await
    .unwrap();
    assert_eq!(
        row,
        (true, "media.apk".to_string(), "original-hash".to_string())
    );

    // Promotion changes only model visibility, retaining the same artifact.
    let promote = server
        .put("/api/admin/apps/com.example.media/versions/7")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"is_beta": false}))
        .await;
    assert_eq!(promote.status_code(), StatusCode::NO_CONTENT);
    let row: (bool, String, String) = sqlx::query_as(
        "SELECT is_beta, apk_path, sha256 FROM app_versions WHERE package_name = 'com.example.media' AND version_code = 7",
    )
    .fetch_one(&ctx.pool)
    .await
    .unwrap();
    assert_eq!(
        row,
        (false, "media.apk".to_string(), "original-hash".to_string())
    );

    let audit_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM access_audit_log")
        .fetch_one(&ctx.pool)
        .await
        .unwrap();
    assert_eq!(audit_count, 6);
}

#[tokio::test]
async fn app_authorization_filters_metadata_and_is_rechecked_for_downloads() {
    let (ctx, mock_oidc) = create_auth_test_context().await;
    sqlx::query("INSERT INTO apps (package_name, name) VALUES ('com.example.private', 'Private')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    for (version_code, is_beta, apk_path) in
        [(1_i64, false, "stable.apk"), (2_i64, true, "beta.apk")]
    {
        std::fs::write(
            ctx.storage_path.join(apk_path),
            format!("apk-{version_code}"),
        )
        .unwrap();
        sqlx::query(
            r#"INSERT INTO app_versions
               (package_name, version_code, version_name, apk_path, size, sha256, min_sdk, is_beta)
               VALUES ('com.example.private', ?, ?, ?, 5, 'hash', 24, ?)"#,
        )
        .bind(version_code)
        .bind(version_code.to_string())
        .bind(apk_path)
        .bind(is_beta)
        .execute(&ctx.pool)
        .await
        .unwrap();
    }

    let server = TestServer::new(ctx.router).unwrap();
    let admin_token = mock_oidc.get_admin_token();
    let user_token = mock_oidc.get_user_token();

    let empty = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .await;
    assert!(empty.json::<serde_json::Value>()["apps"]
        .as_array()
        .unwrap()
        .is_empty());

    let hidden_detail = server
        .get("/api/apps/com.example.private")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .await;
    assert_eq!(hidden_detail.status_code(), StatusCode::NOT_FOUND);

    let admin_catalogue = server
        .get("/api/admin/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .await;
    assert_eq!(
        admin_catalogue.json::<serde_json::Value>()["apps"]
            .as_array()
            .unwrap()
            .len(),
        1
    );

    let stable_grant = server
        .put("/api/admin/users/test-user/apps/com.example.private")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"access_level": "stable"}))
        .await;
    assert_eq!(stable_grant.status_code(), StatusCode::NO_CONTENT);

    let stable_catalogue = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .await;
    let body: serde_json::Value = stable_catalogue.json();
    assert_eq!(body["apps"][0]["access_level"], "stable");
    assert_eq!(body["apps"][0]["latest_version"]["version_code"], 1);

    let stable_detail = server
        .get("/api/apps/com.example.private")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .await;
    let body: serde_json::Value = stable_detail.json();
    assert_eq!(body["versions"].as_array().unwrap().len(), 1);
    assert_eq!(body["versions"][0]["version_code"], 1);

    let hidden_beta_apk = server
        .get("/api/apps/com.example.private/versions/2/apk")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .await;
    assert_eq!(hidden_beta_apk.status_code(), StatusCode::NOT_FOUND);

    let beta_grant = server
        .put("/api/admin/users/test-user/apps/com.example.private")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .json(&serde_json::json!({"access_level": "beta"}))
        .await;
    assert_eq!(beta_grant.status_code(), StatusCode::NO_CONTENT);
    let beta_apk = server
        .get("/api/apps/com.example.private/versions/2/apk")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .await;
    assert_eq!(beta_apk.status_code(), StatusCode::OK);

    let revoke = server
        .delete("/api/admin/users/test-user/apps/com.example.private")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {admin_token}").parse().unwrap(),
        )
        .await;
    assert_eq!(revoke.status_code(), StatusCode::NO_CONTENT);

    // A stale APK URL cannot bypass the newly revoked grant.
    let revoked_download = server
        .get("/api/apps/com.example.private/versions/1/apk")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .await;
    assert_eq!(revoked_download.status_code(), StatusCode::NOT_FOUND);
    let revoked_catalogue = server
        .get("/api/apps")
        .add_header(
            "Authorization".parse().unwrap(),
            format!("Bearer {user_token}").parse().unwrap(),
        )
        .await;
    assert!(revoked_catalogue.json::<serde_json::Value>()["apps"]
        .as_array()
        .unwrap()
        .is_empty());
}
