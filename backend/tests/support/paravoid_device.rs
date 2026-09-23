//! Opt-in installed-device acceptance against the production Store router.
use axum_test::{
    multipart::{MultipartForm, Part},
    TestServer,
};
use lellostore_backend::{
    db,
    paravoid::signing::OnlineSigning,
    services::{upload_jobs, ApkParser, StorageService, UploadService},
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{os::unix::fs::PermissionsExt, path::PathBuf, sync::Arc};

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires a fresh disposable emulator, PARAVOID_DEVICE_SERIAL, PARAVOID_FIXTURE, ANDROID_HOME and APKSIGNER_PATH"]
async fn store_backed_https_bootstrap_revocation_and_repair() {
    let serial = std::env::var("PARAVOID_DEVICE_SERIAL").unwrap();
    let fixture = PathBuf::from(std::env::var("PARAVOID_FIXTURE").unwrap());
    let sdk = PathBuf::from(std::env::var("ANDROID_HOME").unwrap());
    let scratch = tempfile::tempdir().unwrap();
    for role in ["head", "grant"] {
        let target = scratch.path().join(format!("{role}.der"));
        std::fs::copy(fixture.join(format!("build/keys/{role}.der")), &target).unwrap();
        std::fs::set_permissions(target, std::fs::Permissions::from_mode(0o600)).unwrap();
    }
    let config = scratch.path().join("signing.json");
    std::fs::write(&config, json!({"version":1,"baseUrl":"https://127.0.0.1:18765/","headKeys":{"head":"head.der"},"grantKeys":{"grant":"grant.der"},"activeHeadKey":"head","activeGrantKey":"grant"}).to_string()).unwrap();
    let signing = Arc::new(OnlineSigning::load(&config).unwrap());
    let (ctx, oidc) =
        super::create_auth_test_context_options(Default::default(), Some(signing), Some(&sdk))
            .await;
    let worker = UploadService::new(
        StorageService::new(ctx.storage_path.clone()),
        ApkParser::new(sdk.join("build-tools/36.0.0/aapt2")),
        None,
        ctx.pool.clone(),
        100 * 1024 * 1024,
    );
    let server = TestServer::builder()
        .http_transport_with_ip_port(Some(std::net::Ipv4Addr::LOCALHOST.into()), None)
        .build(ctx.router)
        .unwrap();
    let admin = format!("Bearer {}", oidc.get_admin_token());
    let user = format!("Bearer {}", oidc.get_user_token());
    let output = fixture.join("build/outputs/paravoid/paravoidAndroidRelease");
    let source = output.join("shell.apk");
    let uploaded = server
        .post("/api/admin/apps")
        .add_header("Authorization", &admin)
        .multipart(
            MultipartForm::new()
                .add_text("publication", "draft")
                .add_text("distribution_mode", "paravoid")
                .add_part(
                    "file",
                    Part::bytes(std::fs::read(&source).unwrap()).file_name("shell.apk"),
                ),
        )
        .await;
    uploaded.assert_status(simple_server::axum::http::StatusCode::CREATED);
    let uploaded: Value = uploaded.json();
    let package = uploaded["package_name"].as_str().unwrap();
    assert_eq!(package, "com.lelloman.paravoidcompat.complete.paravoid");
    let code = uploaded["version"]["version_code"].as_i64().unwrap();
    let overview_url = format!("/api/admin/apps/{package}/distribution");
    let overview: Value = server
        .get(&overview_url)
        .add_header("Authorization", &admin)
        .await
        .json();
    let contract = overview["contracts"][0]["contract_id"].as_str().unwrap();
    assert_eq!(overview["contracts"][0]["bootstrap"], "empty");
    assert_eq!(overview["contracts"][0]["authentication"], "apkKey");
    let vpk = server
        .post(&format!(
            "/api/admin/apps/{package}/contracts/{contract}/vpks"
        ))
        .add_header("Authorization", &admin)
        .multipart(
            MultipartForm::new().add_part(
                "file",
                Part::bytes(std::fs::read(output.join("payload.vpk")).unwrap())
                    .file_name("payload.vpk"),
            ),
        )
        .await;
    vpk.assert_status(simple_server::axum::http::StatusCode::ACCEPTED);
    upload_jobs::process_next(&ctx.pool, &worker).await.unwrap();
    let overview: Value = server
        .get(&overview_url)
        .add_header("Authorization", &admin)
        .await
        .json();
    let release = overview["releases"][0]["id"].as_str().unwrap();
    let release_id = overview["releases"][0]["release_id"].as_str().unwrap();
    server.post(&format!("/api/admin/apps/{package}/publications")).add_header("Authorization", &admin)
        .json(&json!({"version_code":code,"expected_revision":overview["publication_revision"],"bootstrap_vpk":release})).await.assert_status_ok();
    db::access::set_direct_grant(
        &ctx.pool,
        "test-user",
        package,
        db::access::AppAccessLevel::Stable,
    )
    .await
    .unwrap();
    let acquired = server
        .post(&format!("/api/apps/{package}/acquisitions"))
        .add_header("Authorization", &user)
        .json(&json!({"version_code":code,"purpose":"install","idempotency_key":"device-install"}))
        .await;
    acquired.assert_status_ok();
    let acquired: Value = acquired.json();
    let delivered = server
        .get(acquired["apk_url"].as_str().unwrap())
        .add_header("Authorization", &user)
        .await;
    delivered.assert_status_ok();
    assert_eq!(acquired["size"], delivered.as_bytes().len());
    assert_eq!(
        acquired["sha256"],
        hex::encode(Sha256::digest(delivered.as_bytes()))
    );
    let carrier = scratch.path().join("acquired.apk");
    std::fs::write(&carrier, delivered.as_bytes()).unwrap();
    let control = scratch.path().join("device.json");
    std::fs::write(&control, json!({"server":server.server_address().unwrap().as_str(),"admin":admin,"user":user,
        "package":package,"version":code,"release":release_id,"acquisition":acquired["id"],"apk":carrier,"source":source,
        "fixture":fixture,"serial":serial}).to_string()).unwrap();
    std::fs::set_permissions(&control, std::fs::Permissions::from_mode(0o600)).unwrap();
    // Keep the real durable worker running while the device publishes its update.
    let worker_pool = ctx.pool.clone();
    let background = tokio::spawn(async move {
        loop {
            upload_jobs::process_next(&worker_pool, &worker)
                .await
                .unwrap();
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
    });
    let child = tokio::process::Command::new("python3")
        .arg(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../scripts/tests/paravoid-device.py"))
        .arg(control)
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let status = tokio::time::timeout(
        std::time::Duration::from_secs(600),
        child.wait_with_output(),
    )
    .await
    .unwrap()
    .unwrap();
    background.abort();
    assert!(
        status.status.success(),
        "Store-backed device acceptance failed"
    );
    let grants = db::paravoid::grants(&ctx.pool, package).await.unwrap();
    assert_eq!(grants.len(), 2);
    assert_eq!(grants.iter().filter(|g| g.revoked_at.is_some()).count(), 1);
    assert!(grants.iter().all(|g| g.request_count > 0));
}
