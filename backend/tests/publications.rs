#[allow(dead_code)]
mod common;

use axum_test::TestServer;
use common::create_test_context;
use lellostore_backend::db::{
    self,
    publications::{self, PublishRequest},
};

async fn draft(pool: &sqlx::SqlitePool, code: i64, beta: bool) {
    sqlx::query("INSERT OR IGNORE INTO apps(package_name, name) VALUES ('test.app', 'Test')")
        .execute(pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO app_versions(package_name, version_code, version_name, apk_path, size, sha256, min_sdk, is_beta, publication_state) VALUES ('test.app', ?, '1.0', 'apks/test.app/1.apk', 3, 'hash', 24, ?, 'draft')")
        .bind(code).bind(beta).execute(pool).await.unwrap();
}

fn request(code: i64, revision: i64) -> PublishRequest {
    PublishRequest {
        version_code: code,
        expected_revision: revision,
        replace_latest: false,
        transition_review: None,
        bootstrap_vpk: None,
    }
}

#[tokio::test]
async fn draft_is_hidden_from_catalog_and_direct_download_until_published() {
    let ctx = create_test_context().await;
    draft(&ctx.pool, 1, false).await;
    std::fs::create_dir_all(ctx.storage_path.join("apks/test.app")).unwrap();
    std::fs::write(ctx.storage_path.join("apks/test.app/1.apk"), b"apk").unwrap();
    let server = TestServer::new(ctx.router).unwrap();
    let before: serde_json::Value = server.get("/api/apps").await.json();
    assert_eq!(before["apps"].as_array().unwrap().len(), 0);
    server
        .get("/api/apps/test.app/versions/1/apk")
        .await
        .assert_status_not_found();
    publications::publish(&ctx.pool, "test.app", "admin", &request(1, 0))
        .await
        .unwrap();
    let after: serde_json::Value = server.get("/api/apps").await.json();
    assert_eq!(after["apps"][0]["latest_version"]["version_code"], 1);
    server
        .get("/api/apps/test.app/versions/1/apk")
        .await
        .assert_status_ok();
}

#[tokio::test]
async fn publication_is_atomic_and_stale_reviews_cannot_publish() {
    let ctx = create_test_context().await;
    draft(&ctx.pool, 1, false).await;
    draft(&ctx.pool, 2, true).await;
    publications::publish(&ctx.pool, "test.app", "admin", &request(1, 0))
        .await
        .unwrap();
    assert!(
        publications::publish(&ctx.pool, "test.app", "admin", &request(2, 0))
            .await
            .is_err()
    );
    assert_eq!(
        db::get_published_versions(&ctx.pool, "test.app")
            .await
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        db::get_app(&ctx.pool, "test.app")
            .await
            .unwrap()
            .unwrap()
            .publication_revision,
        1
    );
    let events = publications::history(&ctx.pool, "test.app").await.unwrap();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].actor_subject, "admin");
}

#[tokio::test]
async fn version_order_includes_beta_and_withdrawn_history() {
    let ctx = create_test_context().await;
    draft(&ctx.pool, 20, true).await;
    publications::publish(&ctx.pool, "test.app", "admin", &request(20, 0))
        .await
        .unwrap();
    publications::withdraw(&ctx.pool, "test.app", 20, "admin", 1)
        .await
        .unwrap();
    draft(&ctx.pool, 19, false).await;
    assert!(
        publications::publish(&ctx.pool, "test.app", "admin", &request(19, 2))
            .await
            .is_err()
    );
    assert_eq!(
        db::get_app(&ctx.pool, "test.app")
            .await
            .unwrap()
            .unwrap()
            .publication_revision,
        2
    );
    assert!(db::get_published_versions(&ctx.pool, "test.app")
        .await
        .unwrap()
        .is_empty());
}

#[tokio::test]
async fn replacement_withdraws_same_channel_without_deleting_artifacts_or_other_channel() {
    let ctx = create_test_context().await;
    for (code, beta) in [(1, false), (2, true), (3, false)] {
        draft(&ctx.pool, code, beta).await;
        let mut publish = request(code, code - 1);
        publish.replace_latest = code == 3;
        publications::publish(&ctx.pool, "test.app", "admin", &publish)
            .await
            .unwrap();
    }
    let all = db::get_app_versions(&ctx.pool, "test.app").await.unwrap();
    assert_eq!(all.len(), 3);
    assert_eq!(
        all.iter()
            .find(|v| v.version_code == 1)
            .unwrap()
            .publication_state,
        "withdrawn"
    );
    assert_eq!(
        all.iter()
            .find(|v| v.version_code == 2)
            .unwrap()
            .publication_state,
        "published"
    );
}

#[tokio::test]
async fn concurrent_reviews_cannot_both_commit() {
    let ctx = create_test_context().await;
    draft(&ctx.pool, 1, false).await;
    draft(&ctx.pool, 2, false).await;
    let first = request(1, 0);
    let second = request(2, 0);
    let (a, b) = tokio::join!(
        publications::publish(&ctx.pool, "test.app", "admin", &first),
        publications::publish(&ctx.pool, "test.app", "admin", &second),
    );
    assert_ne!(a.is_ok(), b.is_ok());
    assert_eq!(
        publications::history(&ctx.pool, "test.app")
            .await
            .unwrap()
            .len(),
        1
    );
}

#[tokio::test]
async fn deleted_app_keeps_identity_and_revision_history() {
    let ctx = create_test_context().await;
    draft(&ctx.pool, 1, false).await;
    publications::publish(&ctx.pool, "test.app", "admin", &request(1, 0))
        .await
        .unwrap();
    db::delete_app(&ctx.pool, "test.app").await.unwrap();
    draft(&ctx.pool, 1, false).await;
    assert_eq!(
        db::get_app(&ctx.pool, "test.app")
            .await
            .unwrap()
            .unwrap()
            .publication_revision,
        2
    );
    assert!(
        publications::publish(&ctx.pool, "test.app", "admin", &request(1, 2))
            .await
            .is_err()
    );
}

#[tokio::test]
async fn older_devices_keep_a_compatible_historical_installer_after_shell_adoption() {
    let ctx = create_test_context().await;
    draft(&ctx.pool, 1, false).await;
    draft(&ctx.pool, 2, false).await;
    sqlx::query("UPDATE app_versions SET publication_state = 'published'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    sqlx::query("UPDATE app_versions SET min_sdk = 30, distribution_mode = 'paravoid' WHERE version_code = 2").execute(&ctx.pool).await.unwrap();
    let server = TestServer::new(ctx.router).unwrap();
    let old: serde_json::Value = server.get("/api/apps?sdk=28").await.json();
    assert_eq!(old["apps"][0]["latest_version"]["version_code"], 1);
    assert_eq!(
        old["apps"][0]["latest_version"]["distribution_mode"],
        "normal"
    );
    let modern: serde_json::Value = server.get("/api/apps?sdk=30").await.json();
    assert_eq!(modern["apps"][0]["latest_version"]["version_code"], 2);
    let detail: serde_json::Value = server.get("/api/apps/test.app?sdk=28").await.json();
    assert_eq!(detail["versions"].as_array().unwrap().len(), 1);
    let unsupported: serde_json::Value = server.get("/api/apps?sdk=23").await.json();
    assert!(unsupported["apps"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn mode_switch_requires_review_bound_to_exact_draft_and_revision() {
    let ctx = create_test_context().await;
    draft(&ctx.pool, 2, false).await;
    sqlx::query("UPDATE apps SET distribution_mode = 'paravoid'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    let mut publish = request(2, 0);
    assert!(
        publications::publish(&ctx.pool, "test.app", "admin", &publish)
            .await
            .is_err()
    );
    assert_eq!(
        db::get_app(&ctx.pool, "test.app")
            .await
            .unwrap()
            .unwrap()
            .publication_revision,
        0
    );
    sqlx::query("INSERT INTO distribution_reviews(id,package_name,from_mode,to_mode,from_version,target_version,target_sha256,signer_sha256,review_revision,migration_evidence,actor_subject) VALUES ('review','test.app','paravoid','normal',1,2,'other-hash','cert',0,'{}','admin')").execute(&ctx.pool).await.unwrap();
    publish.transition_review = Some("review".into());
    assert!(
        publications::publish(&ctx.pool, "test.app", "admin", &publish)
            .await
            .is_err()
    );
    sqlx::query("UPDATE distribution_reviews SET target_sha256 = 'hash'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    publications::publish(&ctx.pool, "test.app", "admin", &publish)
        .await
        .unwrap();
    assert_eq!(
        db::get_app(&ctx.pool, "test.app")
            .await
            .unwrap()
            .unwrap()
            .distribution_mode,
        "normal"
    );
}
