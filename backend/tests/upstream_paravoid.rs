//! Opt-in consumer check for an unchanged, developer-signed upstream build.
#[allow(dead_code)]
mod common;
use lellostore_backend::services::{upload_jobs, ApkParser, StorageService, UploadService};

#[tokio::test]
#[ignore = "requires PARAVOID_UPSTREAM_APK, optional PARAVOID_UPSTREAM_VPK, ANDROID_HOME and APKSIGNER_PATH"]
async fn admits_current_upstream_producer_artifacts() {
    let ctx = common::create_test_context().await;
    let sdk = std::path::PathBuf::from(std::env::var("ANDROID_HOME").unwrap());
    let apk = std::path::PathBuf::from(std::env::var("PARAVOID_UPSTREAM_APK").unwrap());
    let service = UploadService::new(
        StorageService::new(ctx.storage_path.clone()),
        ApkParser::new(sdk.join("build-tools/36.0.0/aapt2")),
        None,
        ctx.pool.clone(),
        1024 * 1024 * 1024,
    );
    let result = service
        .process_distribution_draft_upload("shell.apk", &apk, None, None, false, "paravoid")
        .await
        .unwrap();
    let contracts = lellostore_backend::db::paravoid::contracts(&ctx.pool, &result.package_name)
        .await
        .unwrap();
    assert_eq!(contracts.len(), 1);
    assert_eq!(contracts[0].verification_state, "verified");
    if contracts[0].bootstrap == "empty" {
        let vpk = std::path::PathBuf::from(std::env::var("PARAVOID_UPSTREAM_VPK").unwrap());
        let job = upload_jobs::enqueue_vpk(
            &ctx.pool,
            &ctx.storage_path,
            "upstream-test",
            "payload.vpk",
            &vpk,
            &result.package_name,
            &contracts[0].contract_id,
        )
        .await
        .unwrap();
        upload_jobs::process_next(&ctx.pool, &service)
            .await
            .unwrap();
        let result = upload_jobs::get(&ctx.pool, &job.id).await.unwrap();
        assert_eq!(result.status, "ready", "{:?}", result.error);
    }
    let releases = lellostore_backend::db::paravoid::releases(&ctx.pool, &result.package_name)
        .await
        .unwrap();
    assert_eq!(releases.len(), 1);
    assert_eq!(releases[0].validation_state, "verified");
    assert_eq!(releases[0].publication_state, "draft");
}
