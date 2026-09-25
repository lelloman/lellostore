#[allow(dead_code)]
mod common;

use lellostore_backend::db::{
    self,
    acquisitions::{self, AcquisitionPurpose, AcquisitionRequest},
};

fn request(key: &str) -> AcquisitionRequest {
    AcquisitionRequest {
        version_code: 1,
        purpose: AcquisitionPurpose::Install,
        idempotency_key: key.into(),
    }
}

async fn fixture() -> common::TestContext {
    let ctx = common::create_test_context().await;
    sqlx::query("INSERT INTO apps(package_name, name) VALUES ('test.app', 'Test')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO app_versions(package_name, version_code, version_name, apk_path, size, sha256, min_sdk) VALUES ('test.app', 1, '1', 'apks/test.apk', 3, ?, 24)")
        .bind("a".repeat(64)).execute(&ctx.pool).await.unwrap();
    db::access::set_direct_grant(
        &ctx.pool,
        "alice",
        "test.app",
        db::access::AppAccessLevel::Stable,
    )
    .await
    .unwrap();
    ctx
}

#[tokio::test]
async fn retries_return_one_immutable_acquisition_and_reject_conflicting_keys() {
    let ctx = fixture().await;
    let a = acquisitions::create(&ctx.pool, "alice", "test.app", &request("retry"))
        .await
        .unwrap();
    let b = acquisitions::create(&ctx.pool, "alice", "test.app", &request("retry"))
        .await
        .unwrap();
    assert_eq!(a.id, b.id);
    assert_eq!(a.sha256, b.sha256);
    let mut conflict = request("retry");
    conflict.purpose = AcquisitionPurpose::Update;
    assert!(
        acquisitions::create(&ctx.pool, "alice", "test.app", &conflict)
            .await
            .is_err()
    );
}

#[tokio::test]
async fn ownership_access_and_expiry_are_rechecked() {
    let ctx = fixture().await;
    let a = acquisitions::create(&ctx.pool, "alice", "test.app", &request("one"))
        .await
        .unwrap();
    assert!(acquisitions::get(&ctx.pool, "bob", &a.id).await.is_err());
    db::access::remove_direct_grant(&ctx.pool, "alice", "test.app")
        .await
        .unwrap();
    assert!(acquisitions::get(&ctx.pool, "alice", &a.id).await.is_err());
    db::access::set_direct_grant(
        &ctx.pool,
        "alice",
        "test.app",
        db::access::AppAccessLevel::Stable,
    )
    .await
    .unwrap();
    sqlx::query("UPDATE acquisitions SET expires_at = 0")
        .execute(&ctx.pool)
        .await
        .unwrap();
    assert!(acquisitions::get(&ctx.pool, "alice", &a.id).await.is_err());
    assert!(
        acquisitions::create(&ctx.pool, "alice", "test.app", &request("one"))
            .await
            .is_err()
    );
    assert!(
        acquisitions::create(&ctx.pool, "alice", "test.app", &request("two"))
            .await
            .is_ok()
    );
}

#[tokio::test]
async fn withdrawn_releases_reject_new_acquisitions_but_existing_transfers_remain_available() {
    let ctx = fixture().await;
    let a = acquisitions::create(&ctx.pool, "alice", "test.app", &request("one"))
        .await
        .unwrap();
    sqlx::query("UPDATE app_versions SET publication_state = 'withdrawn'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    assert!(
        acquisitions::create(&ctx.pool, "alice", "test.app", &request("two"))
            .await
            .is_err()
    );
    assert!(acquisitions::get(&ctx.pool, "alice", &a.id).await.is_ok());
}

#[tokio::test]
async fn draft_and_unauthorized_beta_cannot_be_acquired() {
    let ctx = fixture().await;
    sqlx::query("UPDATE app_versions SET publication_state = 'draft'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    assert!(
        acquisitions::create(&ctx.pool, "alice", "test.app", &request("one"))
            .await
            .is_err()
    );
    sqlx::query("UPDATE app_versions SET publication_state = 'published', is_beta = 1")
        .execute(&ctx.pool)
        .await
        .unwrap();
    assert!(
        acquisitions::create(&ctx.pool, "alice", "test.app", &request("one"))
            .await
            .is_err()
    );
    db::access::set_direct_grant(
        &ctx.pool,
        "alice",
        "test.app",
        db::access::AppAccessLevel::Beta,
    )
    .await
    .unwrap();
    assert!(
        acquisitions::create(&ctx.pool, "alice", "test.app", &request("one"))
            .await
            .is_ok()
    );
}

#[tokio::test]
async fn conditional_transfer_uses_strong_validators_and_does_not_append_on_mismatch() {
    use simple_server::web::http::{HeaderMap, HeaderValue, StatusCode};
    let ctx = fixture().await;
    let file = ctx.storage_path.join("test.apk");
    std::fs::write(&file, b"apk").unwrap();
    let sha = "a".repeat(64);
    let mut headers = HeaderMap::new();
    headers.insert("range", HeaderValue::from_static("bytes=1-"));
    headers.insert("if-range", HeaderValue::from_static("\"old\""));
    let response = lellostore_backend::api::file_response::serve_immutable_file(
        &file,
        "application/octet-stream",
        "app.apk".into(),
        &sha,
        3,
        &headers,
    )
    .await
    .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    headers.insert(
        "if-range",
        HeaderValue::from_str(&format!("\"{sha}\"")).unwrap(),
    );
    let response = lellostore_backend::api::file_response::serve_immutable_file(
        &file,
        "application/octet-stream",
        "app.apk".into(),
        &sha,
        3,
        &headers,
    )
    .await
    .unwrap();
    assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(response.headers()["content-range"], "bytes 1-2/3");
    headers.insert(
        "if-none-match",
        HeaderValue::from_str(&format!("\"{sha}\"")).unwrap(),
    );
    let response = lellostore_backend::api::file_response::serve_immutable_file(
        &file,
        "application/octet-stream",
        "app.apk".into(),
        &sha,
        3,
        &headers,
    )
    .await
    .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_MODIFIED);
    assert_eq!(response.headers()["cache-control"], "private, no-cache");
}
