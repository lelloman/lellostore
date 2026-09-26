//! End-to-end tests with authentication
//!
//! These tests use an embedded mock OIDC server to test the full authentication flow.
//! Each test is designed to be LONG - testing many operations in sequence rather than
//! one operation per test.

use axum_test::TestServer;
use simple_server::web::http::StatusCode;
use std::sync::Arc;

#[allow(dead_code)]
mod common;
mod mock_oidc;

use common::TestContext;
use mock_oidc::MockOidc;

/// Create a test context with authentication enabled
async fn create_auth_test_context() -> (TestContext, MockOidc) {
    create_auth_test_context_with_events(Default::default()).await
}

async fn create_auth_test_context_with_events(
    catalog_events: lellostore_backend::api::events::CatalogEventHub,
) -> (TestContext, MockOidc) {
    create_auth_test_context_options(catalog_events, None, None).await
}

async fn create_auth_test_context_options(
    catalog_events: lellostore_backend::api::events::CatalogEventHub,
    signing: Option<Arc<lellostore_backend::paravoid::signing::OnlineSigning>>,
    sdk: Option<&std::path::Path>,
) -> (TestContext, MockOidc) {
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
        shutdown_grace_secs: 30,
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
    let apk_parser = sdk
        .map(|p| ApkParser::new(p.join("build-tools/36.0.0/aapt2")))
        .unwrap_or(apk_parser);
    let upload_service = Arc::new(UploadService::new(
        (*storage).clone(),
        apk_parser,
        None,
        pool.clone(),
        config.max_upload_size,
    ));

    let state = AppState {
        personalizer: sdk.map(|sdk| {
            Arc::new(
                lellostore_backend::services::personalization::Personalizer::new(
                    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                        .join("../scripts/paravoid-personalize.py"),
                    sdk.join("build-tools/36.0.0/apksigner"),
                    env!("CARGO_BIN_EXE_paravoid_grant_check").into(),
                ),
            )
        }),
        paravoid_signing: signing,
        db: pool.clone(),
        config: Arc::new(config),
        auth: Some(auth_state),
        upload_service,
        storage,
        catalog_events,
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
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();

    // Get tokens
    let admin_token = mock_oidc.get_admin_token();
    let user_token = mock_oidc.get_user_token();

    let response = server
        .get("/api/me")
        .add_header("Authorization", format!("Bearer {}", admin_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
    let identity: serde_json::Value = response.json();
    assert_eq!(identity["subject"], "test-admin");
    assert_eq!(identity["is_admin"], true);

    let response = server
        .get("/api/me")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
    let identity: serde_json::Value = response.json();
    assert_eq!(identity["subject"], "test-user");
    assert_eq!(identity["is_admin"], false);

    // =========================================================================
    // PHASE 1: Verify empty state
    // =========================================================================

    // User can list apps (empty)
    let response = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {}", user_token))
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
        .add_header("Authorization", format!("Bearer {}", user_token))
        .multipart(
            axum_test::multipart::MultipartForm::new()
                .add_text("publication", "draft")
                .add_text("distribution_mode", "normal")
                .add_part(
                    "file",
                    axum_test::multipart::Part::bytes(apk_data.clone()).file_name("test.apk"),
                ),
        )
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
        .add_header("Authorization", format!("Bearer {}", admin_token))
        .multipart(
            axum_test::multipart::MultipartForm::new()
                .add_text("publication", "draft")
                .add_text("distribution_mode", "normal")
                .add_part(
                    "file",
                    axum_test::multipart::Part::bytes(apk_data.clone()).file_name("test.apk"),
                ),
        )
        .await;
    assert_eq!(response.status_code(), StatusCode::CREATED);
    let uploaded: serde_json::Value = response.json();
    assert_eq!(uploaded["package_name"], "com.test.app");
    assert_eq!(uploaded["version"]["version_code"], 1);
    assert_eq!(uploaded["version"]["publication_state"], "draft");
    server
        .post("/api/admin/apps/com.test.app/publications")
        .add_header("Authorization", format!("Bearer {}", admin_token))
        .json(&serde_json::json!({"version_code": 1, "expected_revision": 0}))
        .await
        .assert_status_ok();

    let grant = server
        .put("/api/admin/users/test-user/apps/com.test.app")
        .add_header("Authorization", format!("Bearer {}", admin_token))
        .json(&serde_json::json!({"access_level": "stable"}))
        .await;
    assert_eq!(grant.status_code(), StatusCode::NO_CONTENT);

    // =========================================================================
    // PHASE 4: Verify app appears in list (if upload succeeded)
    // =========================================================================

    let response = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {}", user_token))
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
        .add_header("Authorization", "Bearer invalid.token.here")
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
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
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
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
    let body: serde_json::Value = response.json();
    let apps = body["apps"].as_array().unwrap();
    assert_eq!(
        apps.len(),
        2,
        "Only apps with published releases are visible"
    );

    // Verify app1 has latest version info
    let app1 = apps
        .iter()
        .find(|a| a["package_name"] == "com.example.app1")
        .unwrap();
    assert_eq!(app1["name"], "App One");
    assert!(app1["latest_version"].is_object());
    assert_eq!(app1["latest_version"]["version_code"], 3);

    assert!(!apps
        .iter()
        .any(|app| app["package_name"] == "com.example.app3"));

    // =========================================================================
    // PHASE 2: Get app details with versions
    // =========================================================================

    let response = server
        .get("/api/apps/com.example.app1")
        .add_header("Authorization", format!("Bearer {}", user_token))
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
        .add_header("Authorization", format!("Bearer {}", admin_token))
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
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    let body: serde_json::Value = response.json();
    assert_eq!(body["name"], "Updated App One");

    // =========================================================================
    // PHASE 4: User cannot update app
    // =========================================================================

    let response = server
        .put("/api/admin/apps/com.example.app1")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .json(&serde_json::json!({
            "name": "Hacker Was Here"
        }))
        .await;
    assert_eq!(response.status_code(), StatusCode::FORBIDDEN);

    // Verify name unchanged
    let response = server
        .get("/api/apps/com.example.app1")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    let body: serde_json::Value = response.json();
    assert_eq!(
        body["name"], "Updated App One",
        "Name should not have changed"
    );

    // =========================================================================
    // PHASE 5: Admin withdraws a published version
    // =========================================================================

    let response = server
        .post("/api/admin/apps/com.example.app1/versions/1/withdraw")
        .add_header("Authorization", format!("Bearer {}", admin_token))
        .json(&serde_json::json!({"expected_revision": 0}))
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);

    // Verify version deleted
    let response = server
        .get("/api/apps/com.example.app1")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    let body: serde_json::Value = response.json();
    let versions = body["versions"].as_array().unwrap();
    assert_eq!(versions.len(), 2, "Should have 2 versions after deletion");

    // =========================================================================
    // PHASE 6: Admin deletes entire app
    // =========================================================================

    let response = server
        .delete("/api/admin/apps/com.example.app2")
        .add_header("Authorization", format!("Bearer {}", admin_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::NO_CONTENT);

    // Verify app deleted
    let response = server
        .get("/api/apps/com.example.app2")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::NOT_FOUND);

    // Verify list updated
    let response = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    let body: serde_json::Value = response.json();
    let apps = body["apps"].as_array().unwrap();
    assert_eq!(
        apps.len(),
        1,
        "Only app1 has published releases after app2 deletion"
    );

    // =========================================================================
    // PHASE 7: User cannot delete
    // =========================================================================

    let response = server
        .delete("/api/admin/apps/com.example.app1")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::FORBIDDEN);

    let response = server
        .delete("/api/admin/apps/com.example.app1/versions/2")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::FORBIDDEN);

    // Verify nothing deleted
    let response = server
        .get("/api/apps/com.example.app1")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);

    // =========================================================================
    // PHASE 8: Published identities cannot be deleted through the draft endpoint.
    // =========================================================================
    for code in [2, 3] {
        server
            .delete(&format!("/api/admin/apps/com.example.app1/versions/{code}"))
            .add_header("Authorization", format!("Bearer {}", admin_token))
            .await
            .assert_status(StatusCode::CONFLICT);
    }
    let body: serde_json::Value = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {}", user_token))
        .await
        .json();
    assert_eq!(body["apps"].as_array().unwrap().len(), 1);
    assert_eq!(body["apps"][0]["package_name"], "com.example.app1");
}

/// Test token expiration and refresh scenarios
#[tokio::test]
async fn test_token_expiration_handling() {
    let (ctx, mock_oidc) = create_auth_test_context().await;
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();

    // Get a valid token
    let valid_token = mock_oidc.get_user_token();

    // Test with valid token works
    let response = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {}", valid_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);

    // Test with expired token
    let expired_token = mock_oidc.get_expired_token();
    let response = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {}", expired_token))
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
        .add_header("Authorization", format!("Bearer {}", wrong_aud_token))
        .await;
    assert_eq!(
        response.status_code(),
        StatusCode::UNAUTHORIZED,
        "Wrong audience should be rejected"
    );

    // Valid token should still work
    let response = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {}", valid_token))
        .await;
    assert_eq!(response.status_code(), StatusCode::OK);
}

#[tokio::test]
async fn shared_auth_preserves_http_policy_and_registry_outage() {
    let (ctx, oidc) = create_auth_test_context().await;
    let server = TestServer::builder()
        .http_transport()
        .build(simple_server::web::compat::into_axum_router(ctx.router))
        .unwrap();
    let user = oidc.get_user_token();
    let admin = oidc.get_admin_token();

    assert_eq!(server.get("/health").await.status_code(), StatusCode::OK);
    assert_eq!(
        server.get("/api/apps").await.status_code(),
        StatusCode::UNAUTHORIZED
    );
    for value in [format!("Bearer {user}"), format!("bearer {user}")] {
        assert_eq!(
            server
                .get("/api/apps")
                .add_header("Authorization", value)
                .await
                .status_code(),
            StatusCode::OK
        );
    }
    for value in [
        format!("BEARER {user}"),
        format!("Basic {user}"),
        "Bearer ".to_string(),
    ] {
        let response = server
            .get("/api/apps")
            .add_header("Authorization", value)
            .await;
        assert_eq!(response.status_code(), StatusCode::UNAUTHORIZED);
        assert_eq!(
            response.json::<serde_json::Value>()["error"],
            "Unauthorized"
        );
    }
    assert_eq!(
        server
            .get("/api/admin/apps")
            .add_header("Authorization", format!("Bearer {user}"))
            .await
            .status_code(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        server
            .get("/api/admin/apps")
            .add_header("Authorization", format!("Bearer {admin}"))
            .await
            .status_code(),
        StatusCode::OK
    );

    ctx.pool.close().await;
    let response = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {user}"))
        .await;
    assert_eq!(response.status_code(), StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(
        response.json::<serde_json::Value>()["error"],
        "Authentication error"
    );
    assert_eq!(server.get("/health").await.status_code(), StatusCode::OK);
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

    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let response = server
        .delete("/api/admin/apps/com.example.atomic")
        .add_header(
            "Authorization",
            format!("Bearer {}", mock_oidc.get_admin_token())
                .parse::<simple_server::web::http::HeaderValue>()
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

    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let admin_token = mock_oidc.get_admin_token();
    let user_token = mock_oidc.get_user_token();

    // Successful authenticated requests populate the small OIDC-backed user directory.
    assert_eq!(
        server
            .get("/api/apps")
            .add_header("Authorization", format!("Bearer {user_token}"),)
            .await
            .status_code(),
        StatusCode::OK
    );

    let denied = server
        .post("/api/admin/app-groups")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .json(&serde_json::json!({"name": "Media apps"}))
        .await;
    assert_eq!(denied.status_code(), StatusCode::FORBIDDEN);

    let created = server
        .post("/api/admin/app-groups")
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .json(&serde_json::json!({"name": "Media apps"}))
        .await;
    assert_eq!(created.status_code(), StatusCode::CREATED);
    let group: serde_json::Value = created.json();
    let group_id = group["id"].as_i64().unwrap();

    let duplicate = server
        .post("/api/admin/app-groups")
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .json(&serde_json::json!({"name": "media APPS"}))
        .await;
    assert_eq!(duplicate.status_code(), StatusCode::CONFLICT);

    let set_group_grant = server
        .put(&format!(
            "/api/admin/app-groups/{group_id}/apps/com.example.media"
        ))
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .json(&serde_json::json!({"access_level": "stable"}))
        .await;
    assert_eq!(set_group_grant.status_code(), StatusCode::NO_CONTENT);

    let add_member = server
        .put(&format!("/api/admin/app-groups/{group_id}/users/test-user"))
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .await;
    assert_eq!(add_member.status_code(), StatusCode::NO_CONTENT);

    let stable_access = server
        .get("/api/admin/users/test-user/access")
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .await;
    assert_eq!(stable_access.status_code(), StatusCode::OK);
    let access: serde_json::Value = stable_access.json();
    assert_eq!(access["effective_access"][0]["access_level"], "stable");
    assert_eq!(access["groups"][0]["name"], "Media apps");

    let set_direct_beta = server
        .put("/api/admin/users/test-user/apps/com.example.media")
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .json(&serde_json::json!({"access_level": "beta"}))
        .await;
    assert_eq!(set_direct_beta.status_code(), StatusCode::NO_CONTENT);

    let beta_access = server
        .get("/api/admin/users/test-user/access")
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .await;
    let access: serde_json::Value = beta_access.json();
    assert_eq!(access["effective_access"][0]["access_level"], "beta");

    let mark_beta = server
        .put("/api/admin/apps/com.example.media/versions/7")
        .add_header("Authorization", format!("Bearer {admin_token}"))
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
        .add_header("Authorization", format!("Bearer {admin_token}"))
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
async fn all_system_group_grants_every_app_and_rejects_rule_changes() {
    let (ctx, mock_oidc) = create_auth_test_context().await;
    for package_name in ["com.example.one", "com.example.two"] {
        sqlx::query("INSERT INTO apps (package_name, name) VALUES (?, ?)")
            .bind(package_name)
            .bind(package_name)
            .execute(&ctx.pool)
            .await
            .unwrap();
        for (version_code, is_beta) in [(1_i64, false), (2_i64, true)] {
            sqlx::query(
                r#"INSERT INTO app_versions
                   (package_name, version_code, version_name, apk_path, size, sha256, min_sdk, is_beta)
                   VALUES (?, ?, ?, ?, 1, 'hash', 24, ?)"#,
            )
            .bind(package_name)
            .bind(version_code)
            .bind(version_code.to_string())
            .bind(format!("{package_name}-{version_code}.apk"))
            .bind(is_beta)
            .execute(&ctx.pool)
            .await
            .unwrap();
        }
    }

    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let admin_token = mock_oidc.get_admin_token();
    let user_token = mock_oidc.get_user_token();
    let authorization: simple_server::web::http::HeaderName = "Authorization".parse().unwrap();

    // Register the OIDC user in the server's administration directory.
    assert_eq!(
        server
            .get("/api/apps")
            .add_header(authorization.clone(), format!("Bearer {user_token}"),)
            .await
            .status_code(),
        StatusCode::OK
    );

    let groups = server
        .get("/api/admin/app-groups")
        .add_header(authorization.clone(), format!("Bearer {admin_token}"))
        .await;
    assert_eq!(groups.status_code(), StatusCode::OK);
    let groups: serde_json::Value = groups.json();
    let all = groups["groups"]
        .as_array()
        .unwrap()
        .iter()
        .find(|group| group["system_kind"] == "all")
        .expect("the all system group must be seeded");
    let group_id = all["id"].as_i64().unwrap();
    assert_eq!(all["name"], "all");
    assert!(all["grants"].as_array().unwrap().is_empty());

    let add_member = server
        .put(&format!("/api/admin/app-groups/{group_id}/users/test-user"))
        .add_header(authorization.clone(), format!("Bearer {admin_token}"))
        .await;
    assert_eq!(add_member.status_code(), StatusCode::NO_CONTENT);

    let catalogue = server
        .get("/api/apps")
        .add_header(authorization.clone(), format!("Bearer {user_token}"))
        .await;
    let catalogue: serde_json::Value = catalogue.json();
    let apps = catalogue["apps"].as_array().unwrap();
    assert_eq!(apps.len(), 2);
    assert!(apps.iter().all(|app| app["access_level"] == "beta"));
    assert!(apps
        .iter()
        .all(|app| app["latest_version"]["version_code"] == 2));

    let rename = server
        .put(&format!("/api/admin/app-groups/{group_id}"))
        .add_header(authorization.clone(), format!("Bearer {admin_token}"))
        .json(&serde_json::json!({"name": "renamed"}))
        .await;
    assert_eq!(rename.status_code(), StatusCode::BAD_REQUEST);

    let set_rule = server
        .put(&format!(
            "/api/admin/app-groups/{group_id}/apps/com.example.one"
        ))
        .add_header(authorization.clone(), format!("Bearer {admin_token}"))
        .json(&serde_json::json!({"access_level": "stable"}))
        .await;
    assert_eq!(set_rule.status_code(), StatusCode::BAD_REQUEST);

    let delete = server
        .delete(&format!("/api/admin/app-groups/{group_id}"))
        .add_header(authorization, format!("Bearer {admin_token}"))
        .await;
    assert_eq!(delete.status_code(), StatusCode::BAD_REQUEST);
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

    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let admin_token = mock_oidc.get_admin_token();
    let user_token = mock_oidc.get_user_token();

    let empty = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .await;
    assert!(empty.json::<serde_json::Value>()["apps"]
        .as_array()
        .unwrap()
        .is_empty());

    let hidden_detail = server
        .get("/api/apps/com.example.private")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .await;
    assert_eq!(hidden_detail.status_code(), StatusCode::NOT_FOUND);

    let admin_catalogue = server
        .get("/api/admin/apps")
        .add_header("Authorization", format!("Bearer {admin_token}"))
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
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .json(&serde_json::json!({"access_level": "stable"}))
        .await;
    assert_eq!(stable_grant.status_code(), StatusCode::NO_CONTENT);

    let stable_catalogue = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .await;
    let body: serde_json::Value = stable_catalogue.json();
    assert_eq!(body["apps"][0]["access_level"], "stable");
    assert_eq!(body["apps"][0]["latest_version"]["version_code"], 1);

    let stable_detail = server
        .get("/api/apps/com.example.private")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .await;
    let body: serde_json::Value = stable_detail.json();
    assert_eq!(body["versions"].as_array().unwrap().len(), 1);
    assert_eq!(body["versions"][0]["version_code"], 1);

    let hidden_beta_apk = server
        .get("/api/apps/com.example.private/versions/2/apk")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .await;
    assert_eq!(hidden_beta_apk.status_code(), StatusCode::NOT_FOUND);

    let beta_grant = server
        .put("/api/admin/users/test-user/apps/com.example.private")
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .json(&serde_json::json!({"access_level": "beta"}))
        .await;
    assert_eq!(beta_grant.status_code(), StatusCode::NO_CONTENT);
    let beta_apk = server
        .get("/api/apps/com.example.private/versions/2/apk")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .await;
    assert_eq!(beta_apk.status_code(), StatusCode::OK);

    let revoke = server
        .delete("/api/admin/users/test-user/apps/com.example.private")
        .add_header("Authorization", format!("Bearer {admin_token}"))
        .await;
    assert_eq!(revoke.status_code(), StatusCode::NO_CONTENT);

    // A stale APK URL cannot bypass the newly revoked grant.
    let revoked_download = server
        .get("/api/apps/com.example.private/versions/1/apk")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .await;
    assert_eq!(revoked_download.status_code(), StatusCode::NOT_FOUND);
    let revoked_catalogue = server
        .get("/api/apps")
        .add_header("Authorization", format!("Bearer {user_token}"))
        .await;
    assert!(revoked_catalogue.json::<serde_json::Value>()["apps"]
        .as_array()
        .unwrap()
        .is_empty());
}

/// Exercise authentication, upgrade, multipart upload and event serialization
/// through real sockets after the Axum migration.
#[tokio::test]
async fn authenticated_websocket_receives_catalog_change_after_publication() {
    let (ctx, oidc) = create_auth_test_context().await;
    let server = TestServer::builder()
        .http_transport()
        .build(simple_server::web::compat::into_axum_router(
            ctx.router.clone(),
        ))
        .unwrap();
    server
        .get_websocket("/api/events")
        .await
        .assert_status_unauthorized();
    let mut socket = server
        .get_websocket("/api/events")
        .add_header("Authorization", format!("Bearer {}", oidc.get_user_token()))
        .await
        .into_websocket()
        .await;
    server
        .post("/api/admin/apps")
        .add_header(
            "Authorization",
            format!("Bearer {}", oidc.get_admin_token()),
        )
        .multipart(
            axum_test::multipart::MultipartForm::new()
                .add_text("publication", "draft")
                .add_text("distribution_mode", "normal")
                .add_part(
                    "file",
                    axum_test::multipart::Part::bytes(create_test_apk("com.test.app", 1))
                        .file_name("test.apk"),
                ),
        )
        .await
        .assert_status(StatusCode::CREATED);
    server
        .post("/api/admin/apps/com.test.app/publications")
        .add_header(
            "Authorization",
            format!("Bearer {}", oidc.get_admin_token()),
        )
        .json(&serde_json::json!({"version_code": 1, "expected_revision": 0}))
        .await
        .assert_status_ok();
    let event = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        socket.receive_json::<serde_json::Value>(),
    )
    .await
    .expect("catalog event was not delivered");
    assert_eq!(event, serde_json::json!({"type": "catalog_changed"}));
    socket.close().await;
}

#[tokio::test]
async fn catalog_websockets_close_and_reject_new_upgrades_during_shutdown() {
    use lellostore_backend::api::events::CatalogEventHub;
    use simple_server::lifecycle::Shutdown;
    use std::time::Duration;

    tokio::time::timeout(Duration::from_secs(10), async {
        let shutdown = Shutdown::new();
        let hub = CatalogEventHub::new(shutdown.clone());
        let (ctx, oidc) = create_auth_test_context_with_events(hub.clone()).await;
        let server = TestServer::builder()
            .http_transport()
            .build(simple_server::web::compat::into_axum_router(
                ctx.router.clone(),
            ))
            .unwrap();
        let mut sockets = Vec::new();
        for _ in 0..3 {
            sockets.push(
                server
                    .get_websocket("/api/events")
                    .add_header("Authorization", format!("Bearer {}", oidc.get_user_token()))
                    .await
                    .into_websocket()
                    .await,
            );
        }
        // A disconnected peer must release its reservation; every remaining
        // connection must receive shutdown before drain completes.
        drop(sockets.pop());
        shutdown.request();
        let receive_closes = async {
            for socket in &mut sockets {
                assert!(matches!(
                    socket.receive_message().await,
                    axum_test::WsMessage::Close(_)
                ));
            }
        };
        let (result, ()) = tokio::join!(hub.drain(), receive_closes);
        result.unwrap();
        server
            .get_websocket("/api/events")
            .add_header("Authorization", format!("Bearer {}", oidc.get_user_token()))
            .await
            .assert_status(StatusCode::SERVICE_UNAVAILABLE);
        ctx.pool.close().await;
    })
    .await
    .expect("catalog WebSocket shutdown timed out");
}

#[cfg(unix)]
#[tokio::test]
async fn publication_replacement_deletes_files_and_legacy_uploads_fail_explicitly() {
    let (ctx, oidc) = create_auth_test_context().await;
    let server = TestServer::new(simple_server::web::compat::into_axum_router(
        ctx.router.clone(),
    ))
    .unwrap();
    let authorization = format!("Bearer {}", oidc.get_admin_token());
    server
        .post("/api/admin/apps")
        .add_header("Authorization", authorization.clone())
        .multipart(
            axum_test::multipart::MultipartForm::new().add_part(
                "file",
                axum_test::multipart::Part::bytes(create_test_apk("com.test.app", 1))
                    .file_name("test.apk"),
            ),
        )
        .await
        .assert_status_bad_request();
    for code in [1, 2] {
        let parser = ctx.temp_dir.path().join("fake-aapt2");
        std::fs::write(&parser, format!("#!/bin/sh\necho \"package: name='com.test.app' versionCode='{code}' versionName='{code}.0'\"\necho \"sdkVersion:'24'\"\necho \"application-label:'Test App'\"\n")).unwrap();
        server
            .post("/api/admin/apps")
            .add_header("Authorization", authorization.clone())
            .multipart(
                axum_test::multipart::MultipartForm::new()
                    .add_text("publication", "draft")
                    .add_text("distribution_mode", "normal")
                    .add_part(
                        "file",
                        axum_test::multipart::Part::bytes(create_test_apk("com.test.app", code))
                            .file_name("test.apk"),
                    ),
            )
            .await
            .assert_status(StatusCode::CREATED);
        server.post("/api/admin/apps/com.test.app/publications").add_header("Authorization", authorization.clone())
            .json(&serde_json::json!({"version_code": code, "expected_revision": code - 1, "replace_latest": true}))
            .await.assert_status_ok();
    }
    let versions = lellostore_backend::db::get_app_versions(&ctx.pool, "com.test.app")
        .await
        .unwrap();
    assert_eq!(versions.len(), 2);
    assert_eq!(versions[0].publication_state, "published");
    assert_eq!(versions[1].publication_state, "withdrawn");
    assert!(ctx.storage_path.join("apks/com.test.app/2.apk").exists());
    assert!(!ctx.storage_path.join("apks/com.test.app/1.apk").exists());
}

#[tokio::test]
async fn asynchronous_upload_is_persisted_and_history_requires_admin() {
    let (ctx, oidc) = create_auth_test_context().await;
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let admin = format!("Bearer {}", oidc.get_admin_token());
    let response = server
        .post("/api/admin/apps?asynchronous=true")
        .add_header("Authorization", &admin)
        .multipart(
            axum_test::multipart::MultipartForm::new()
                .add_text("publication", "draft")
                .add_text("distribution_mode", "normal")
                .add_part(
                    "file",
                    axum_test::multipart::Part::bytes(b"invalid bytes".to_vec())
                        .file_name("invalid.bin"),
                ),
        )
        .await;
    response.assert_status(StatusCode::ACCEPTED);
    let job: serde_json::Value = response.json();
    assert_eq!(job["status"], "queued");
    assert!(job.get("input_path").is_none());
    let id = job["id"].as_str().unwrap();
    let stored: String = sqlx::query_scalar("SELECT input_path FROM upload_jobs WHERE id = ?")
        .bind(id)
        .fetch_one(&ctx.pool)
        .await
        .unwrap();
    assert_eq!(std::fs::read(stored).unwrap(), b"invalid bytes");
    server
        .get("/api/admin/uploads")
        .add_header("Authorization", format!("Bearer {}", oidc.get_user_token()))
        .await
        .assert_status(StatusCode::FORBIDDEN);
    let history: Vec<serde_json::Value> = server
        .get("/api/admin/uploads")
        .add_header("Authorization", &admin)
        .await
        .json();
    assert_eq!(history.len(), 1);
    server
        .post(&format!("/api/admin/uploads/{id}/retry"))
        .add_header("Authorization", &admin)
        .await
        .assert_status(StatusCode::CONFLICT);
    sqlx::query("UPDATE upload_jobs SET status = 'failed', error = 'test failure' WHERE id = ?")
        .bind(id)
        .execute(&ctx.pool)
        .await
        .unwrap();
    let retried: serde_json::Value = server
        .post(&format!("/api/admin/uploads/{id}/retry"))
        .add_header("Authorization", &admin)
        .await
        .json();
    assert_eq!(retried["status"], "queued");
    assert!(retried["error"].is_null());
}

#[tokio::test]
async fn paravoid_public_key_configuration_requires_administrator() {
    let (ctx, oidc) = create_auth_test_context().await;
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let path = "/api/admin/paravoid/configuration";
    server
        .get(path)
        .await
        .assert_status(StatusCode::UNAUTHORIZED);
    server
        .get(path)
        .add_header("Authorization", format!("Bearer {}", oidc.get_user_token()))
        .await
        .assert_status(StatusCode::FORBIDDEN);
    let response = server
        .get(path)
        .add_header(
            "Authorization",
            format!("Bearer {}", oidc.get_admin_token()),
        )
        .await;
    response.assert_status_ok();
    assert_eq!(
        response.json::<serde_json::Value>(),
        serde_json::json!({"configured": false, "distribution_enabled": false, "signing": null})
    );
}

#[tokio::test]
async fn canonical_download_requires_acquisition_for_keyed_and_unverified_shells() {
    use lellostore_backend::db::access::{set_direct_grant, AppAccessLevel};
    let (ctx, oidc) = create_auth_test_context().await;
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    sqlx::query("INSERT INTO apps(package_name,name,distribution_mode) VALUES ('test.shell','Shell','paravoid')").execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk,distribution_mode) VALUES ('test.shell',1,'1','shell.apk',3,'hash',30,'paravoid')").execute(&ctx.pool).await.unwrap();
    std::fs::write(ctx.storage_path.join("shell.apk"), b"apk").unwrap();
    let url = "/api/apps/test.shell/versions/1/apk";
    let token = format!("Bearer {}", oidc.get_user_token());
    server.get(url).await.assert_status_unauthorized();
    server
        .get(url)
        .add_header("Authorization", &token)
        .await
        .assert_status_not_found();
    set_direct_grant(&ctx.pool, "test-user", "test.shell", AppAccessLevel::Stable)
        .await
        .unwrap();
    server
        .get(url)
        .add_header("Authorization", &token)
        .await
        .assert_status_conflict();
    sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state) VALUES ('test.shell',?,1,'stable','apkKey','empty','https://store.test/api/paravoid/','{}','{}','verified')").bind("a".repeat(64)).execute(&ctx.pool).await.unwrap();
    server
        .get(url)
        .add_header("Authorization", &token)
        .await
        .assert_status_conflict();
    server
        .get(url)
        .add_header("Authorization", &token)
        .add_header("Range", "bytes=0-1")
        .await
        .assert_status_conflict();
    sqlx::query("UPDATE paravoid_contracts SET authentication='public'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    let public = server.get(url).add_header("Authorization", &token).await;
    public.assert_status_ok();
    assert_eq!(public.as_bytes().as_ref(), b"apk");
    sqlx::query("UPDATE paravoid_contracts SET verification_state='pending'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    server
        .get(url)
        .add_header("Authorization", &token)
        .await
        .assert_status_conflict();
}

#[path = "common/signed_shell.rs"]
mod signed_shell;

#[path = "support/paravoid_http.rs"]
mod paravoid_http;

#[path = "support/paravoid_device.rs"]
mod paravoid_device;

#[tokio::test]
async fn shared_multipart_preserves_upload_rejections_and_temp_cleanup() {
    use axum_test::multipart::{MultipartForm, Part};
    let (ctx, oidc) = create_auth_test_context().await;
    let server = TestServer::builder()
        .http_transport()
        .build(simple_server::web::compat::into_axum_router(ctx.router))
        .unwrap();
    let admin = format!("Bearer {}", oidc.get_admin_token());
    for (form, status, message) in [
        (
            MultipartForm::new().add_text("name", "x".repeat(64 * 1024 + 1)),
            StatusCode::PAYLOAD_TOO_LARGE,
            "File too large",
        ),
        (
            MultipartForm::new()
                .add_part("file", Part::bytes(vec![1]).file_name("first.apk"))
                .add_part("file", Part::bytes(vec![2]).file_name("second.apk")),
            StatusCode::BAD_REQUEST,
            "Only one upload file is allowed",
        ),
        (
            MultipartForm::new().add_text("name", "No file"),
            StatusCode::BAD_REQUEST,
            "No file provided",
        ),
        (
            MultipartForm::new().add_part("name", Part::bytes(vec![0xff])),
            StatusCode::BAD_REQUEST,
            "Metadata must be UTF-8",
        ),
    ] {
        let response = server
            .post("/api/admin/apps")
            .add_header("Authorization", &admin)
            .multipart(form)
            .await;
        response.assert_status(status);
        let json: serde_json::Value = response.json();
        assert!(
            json["message"].as_str().unwrap().contains(message),
            "{json}"
        );
        assert_eq!(
            std::fs::read_dir(ctx.storage_path.join("temp"))
                .unwrap()
                .count(),
            0
        );
    }
}

#[tokio::test]
async fn archive_controls_require_admin_and_current_revision_and_reject_replaced_files() {
    let (ctx, oidc) = create_auth_test_context().await;
    sqlx::query("INSERT INTO apps(package_name,name) VALUES ('test.app','Test')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk) VALUES ('test.app',1,'1','apks/test.app/1.apk',3,'hash',24)").execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state) VALUES ('test.app','contract',1,'stable','public','embedded','https://example.test/','{}','{}','pending')").execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO vpk_releases(id,package_name,contract_id,release_id,payload_version,archive_path,archive_size,archive_sha256,manifest_sha256,manifest_json,min_sdk,max_sdk,abis_json,signing_key_id,validation_state,validation_report) VALUES ('payload','test.app','contract','release',1,'vpks/payload.vpk',3,'hash','hash','{}',24,0,'[]','key','inspected','{}')").execute(&ctx.pool).await.unwrap();
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let admin = oidc.get_admin_token();
    let user = oidc.get_user_token();
    for (index, suffix) in ["versions/1", "vpks/payload"].iter().enumerate() {
        let url = format!("/api/admin/apps/test.app/{suffix}/archive");
        let revision = index as i64 * 2;
        let body = serde_json::json!({"expected_revision":revision,"archived":true});
        server
            .put(&url)
            .json(&body)
            .await
            .assert_status_unauthorized();
        server
            .put(&url)
            .add_header("Authorization", format!("Bearer {user}"))
            .json(&body)
            .await
            .assert_status_forbidden();
        server
            .put(&url)
            .add_header("Authorization", format!("Bearer {admin}"))
            .json(&body)
            .await
            .assert_status(StatusCode::NO_CONTENT);
        server
            .put(&url)
            .add_header("Authorization", format!("Bearer {admin}"))
            .json(&body)
            .await
            .assert_status(StatusCode::CONFLICT);
        let query = if index == 0 {
            "SELECT archived FROM app_versions"
        } else {
            "SELECT archived FROM vpk_releases"
        };
        assert!(sqlx::query_scalar::<_, bool>(query)
            .fetch_one(&ctx.pool)
            .await
            .unwrap());
        server
            .put(&url)
            .add_header("Authorization", format!("Bearer {admin}"))
            .json(&serde_json::json!({"expected_revision":revision+1,"archived":false}))
            .await
            .assert_status(StatusCode::NO_CONTENT);
        assert!(!sqlx::query_scalar::<_, bool>(query)
            .fetch_one(&ctx.pool)
            .await
            .unwrap());
    }
    sqlx::query("UPDATE app_versions SET artifact_removed=1, publication_state='withdrawn'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    sqlx::query("UPDATE vpk_releases SET artifact_removed=1, publication_state='withdrawn'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    for suffix in ["versions/1", "vpks/payload"] {
        server
            .put(&format!("/api/admin/apps/test.app/{suffix}/archive"))
            .add_header("Authorization", format!("Bearer {admin}"))
            .json(&serde_json::json!({"expected_revision":4,"archived":true}))
            .await
            .assert_status(StatusCode::CONFLICT);
    }
    let detail: serde_json::Value = server
        .get("/api/admin/apps/test.app")
        .add_header("Authorization", format!("Bearer {admin}"))
        .await
        .json();
    assert_eq!(detail["versions"][0]["artifact_removed"], true);
    assert_eq!(detail["versions"][0]["archived"], false);
    assert_eq!(detail["publication_revision"], 4);
}
