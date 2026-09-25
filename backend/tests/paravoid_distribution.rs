#[allow(dead_code)]
mod common;
use axum_test::TestServer;
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use lellostore_backend::{
    db::{self, paravoid},
    paravoid::signing::OnlineSigning,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{os::unix::fs::PermissionsExt, process::Command, sync::Arc};

fn keys(directory: &std::path::Path) -> Arc<OnlineSigning> {
    for role in ["head", "grant"] {
        let pem = directory.join(format!("{role}.pem"));
        let der = directory.join(format!("{role}.pk8"));
        assert!(Command::new("openssl")
            .args([
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:3072",
                "-out"
            ])
            .arg(&pem)
            .output()
            .unwrap()
            .status
            .success());
        assert!(Command::new("openssl")
            .args(["pkcs8", "-topk8", "-nocrypt", "-outform", "DER", "-in"])
            .arg(&pem)
            .arg("-out")
            .arg(&der)
            .output()
            .unwrap()
            .status
            .success());
        std::fs::set_permissions(der, std::fs::Permissions::from_mode(0o600)).unwrap();
    }
    let config = directory.join("config.json");
    std::fs::write(&config,json!({"version":1,"baseUrl":"https://store.test/api/paravoid/","headKeys":{"head":"head.pk8"},"grantKeys":{"grant":"grant.pk8"},"activeHeadKey":"head","activeGrantKey":"grant"}).to_string()).unwrap();
    Arc::new(OnlineSigning::load(&config).unwrap())
}
async fn seed(pool: &sqlx::SqlitePool, signing: &OnlineSigning, auth: &str) {
    let public = signing.public_configuration();
    let mut trust: Value =
        serde_json::from_str(include_str!("fixtures/paravoid-metadata/trust.json")).unwrap();
    trust["applicationId"] = json!("test.app");
    trust["headKeys"] = json!(public.head_keys);
    trust["grantKeys"] = json!(public.grant_keys);
    sqlx::query("INSERT INTO apps(package_name,name,distribution_mode) VALUES ('test.app','Test','paravoid')").execute(pool).await.unwrap();
    sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk,distribution_mode) VALUES ('test.app',1,'1','apk',3,'hash',30,'paravoid')").execute(pool).await.unwrap();
    sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state) VALUES ('test.app',?,1,'stable',?,'embedded','https://store.test/api/paravoid/',?,'{}','verified')")
        .bind("a".repeat(64)).bind(auth).bind(trust.to_string()).execute(pool).await.unwrap();
    for code in [1_i64, 2] {
        sqlx::query("INSERT INTO vpk_releases(id,package_name,contract_id,release_id,payload_version,archive_path,archive_size,archive_sha256,manifest_sha256,manifest_json,min_sdk,max_sdk,abis_json,signing_key_id,validation_state,validation_report) VALUES (?,'test.app',?,?,?,'payload.vpk',3,?,?,'{}',30,0,'[]','release','verified','{}')")
            .bind(format!("vpk-{code}")).bind("a".repeat(64)).bind(format!("release-{code}")).bind(code).bind(hex::encode(Sha256::digest(b"vpk"))).bind("b".repeat(64)).execute(pool).await.unwrap();
    }
}
fn head_url() -> String {
    format!("/api/paravoid/v1/apps/test.app/head?contract={}&channel=stable&sdk=30&abis=x86_64&runtime=1&format=1&protocol=1","a".repeat(64))
}
fn body(envelope: &Value) -> Value {
    serde_json::from_slice(&STANDARD.decode(envelope["body"].as_str().unwrap()).unwrap()).unwrap()
}

#[tokio::test]
async fn payload_publication_is_atomic_monotonic_and_blocks_incomplete_validation() {
    let dir = tempfile::tempdir().unwrap();
    let signing = keys(dir.path());
    let ctx = common::create_test_context().await;
    seed(&ctx.pool, &signing, "public").await;
    sqlx::query("UPDATE vpk_releases SET validation_state = 'inspected' WHERE id = 'vpk-1'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    assert!(
        paravoid::publish(&ctx.pool, "test.app", "vpk-1", "admin", 0)
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
    paravoid::publish(&ctx.pool, "test.app", "vpk-2", "admin", 0)
        .await
        .unwrap();
    assert!(
        paravoid::withdraw(&ctx.pool, "test.app", "vpk-2", "admin", 0)
            .await
            .is_err()
    );
    paravoid::withdraw(&ctx.pool, "test.app", "vpk-2", "admin", 1)
        .await
        .unwrap();
    sqlx::query("UPDATE vpk_releases SET validation_state = 'verified' WHERE id = 'vpk-1'")
        .execute(&ctx.pool)
        .await
        .unwrap();
    assert!(
        paravoid::publish(&ctx.pool, "test.app", "vpk-1", "admin", 2)
            .await
            .is_err()
    );
    assert_eq!(
        paravoid::release(&ctx.pool, "test.app", "vpk-1")
            .await
            .unwrap()
            .publication_state,
        "draft"
    );
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM published_vpk_identities")
        .fetch_one(&ctx.pool)
        .await
        .unwrap();
    assert_eq!(count, 1);
}

#[tokio::test]
async fn signed_heads_cache_exact_bytes_refresh_revision_and_retire_explicitly() {
    let dir = tempfile::tempdir().unwrap();
    let signing = keys(dir.path());
    let ctx = common::create_paravoid_test_context(signing.clone()).await;
    seed(&ctx.pool, &signing, "public").await;
    paravoid::publish(&ctx.pool, "test.app", "vpk-1", "admin", 0)
        .await
        .unwrap();
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let url = head_url();
    let first = server.get(&url).await;
    first.assert_status_ok();
    let envelope: Value = first.json();
    let first_body = body(&envelope);
    assert_eq!(first_body["status"], "available");
    let contract = paravoid::contract(&ctx.pool, "test.app", &"a".repeat(64))
        .await
        .unwrap();
    contract
        .policy()
        .unwrap()
        .verify_head(
            &serde_json::to_vec(&envelope).unwrap(),
            30,
            &["x86_64".into()],
        )
        .unwrap();
    let etag = first.header("etag").to_str().unwrap().to_owned();
    server
        .get(&url)
        .add_header("If-None-Match", &etag)
        .await
        .assert_status(simple_server::web::http::StatusCode::NOT_MODIFIED);
    server
        .get(&format!("{url}&sdk=31"))
        .await
        .assert_status_bad_request();
    sqlx::query("UPDATE paravoid_streams SET expires_at = 0")
        .execute(&ctx.pool)
        .await
        .unwrap();
    let refreshed = server.get(&url).add_header("If-None-Match", &etag).await;
    refreshed.assert_status_ok();
    assert!(
        body(&refreshed.json::<Value>())["headRevision"].as_i64()
            > first_body["headRevision"].as_i64()
    );
    paravoid::set_stream(&ctx.pool, "test.app", &"a".repeat(64), true, "admin", 1)
        .await
        .unwrap();
    let retired = body(&server.get(&url).await.json::<Value>());
    assert_eq!(retired["status"], "shell-update-required");
    assert!(retired["release"].is_null());
}

#[tokio::test]
async fn revoked_or_unentitled_keys_cannot_fetch_cached_heads_or_ranges() {
    let dir = tempfile::tempdir().unwrap();
    let signing = keys(dir.path());
    let ctx = common::create_paravoid_test_context(signing.clone()).await;
    seed(&ctx.pool, &signing, "apkKey").await;
    let canonical = TestServer::new(simple_server::web::compat::into_axum_router(
        ctx.router.clone(),
    ))
    .unwrap();
    canonical
        .get("/api/apps/test.app/versions/1/apk")
        .await
        .assert_status_conflict();

    let key = URL_SAFE_NO_PAD.encode([7_u8; 32]);
    sqlx::query("INSERT INTO paravoid_grants(id,key_id,credential_sha256,package_name,contract_id,installer_version,user_subject,acquisition_id,issued_at) VALUES ('grant','key',?,'test.app',?,1,'alice','copy',1)")
        .bind(hex::encode(Sha256::digest(key.as_bytes()))).bind("a".repeat(64)).execute(&ctx.pool).await.unwrap();
    db::access::set_direct_grant(
        &ctx.pool,
        "alice",
        "test.app",
        db::access::AppAccessLevel::Stable,
    )
    .await
    .unwrap();
    paravoid::publish(&ctx.pool, "test.app", "vpk-1", "admin", 0)
        .await
        .unwrap();
    std::fs::write(ctx.storage_path.join("payload.vpk"), b"vpk").unwrap();
    let server = TestServer::new(simple_server::web::compat::into_axum_router(ctx.router)).unwrap();
    let token = format!("Bearer {key}");
    let url = head_url();
    server.get(&url).await.assert_status_unauthorized();
    let first = server.get(&url).add_header("Authorization", &token).await;
    first.assert_status_ok();
    let etag = first.header("etag").to_str().unwrap().to_owned();
    let download = "/api/paravoid/v1/apps/test.app/releases/release-1/payload.vpk";
    server
        .get(download)
        .add_header("Authorization", &token)
        .add_header("Range", "bytes=1-")
        .await
        .assert_status(simple_server::web::http::StatusCode::PARTIAL_CONTENT);
    db::access::remove_direct_grant(&ctx.pool, "alice", "test.app")
        .await
        .unwrap();
    server
        .get(&url)
        .add_header("Authorization", &token)
        .add_header("If-None-Match", &etag)
        .await
        .assert_status_forbidden();
    db::access::set_direct_grant(
        &ctx.pool,
        "alice",
        "test.app",
        db::access::AppAccessLevel::Stable,
    )
    .await
    .unwrap();
    paravoid::revoke(&ctx.pool, "test.app", "grant", "admin", 1)
        .await
        .unwrap();
    server
        .get(download)
        .add_header("Authorization", &token)
        .add_header("Range", "bytes=1-")
        .await
        .assert_status_forbidden();
    let grants =
        serde_json::to_string(&paravoid::grants(&ctx.pool, "test.app").await.unwrap()).unwrap();
    assert!(!grants.contains(&key));
    assert!(!grants.contains("credential_sha256"));
}

/// Uses real Android developer signatures; deliberately opt-in on SDK-equipped hosts.
#[tokio::test]
#[ignore = "requires ANDROID_HOME with build-tools 36.0.0, platform 36, and keytool"]
async fn personalized_acquisition_preserves_signatures_and_repair_issues_new_grant() {
    use lellostore_backend::{
        db::acquisitions::{AcquisitionPurpose, AcquisitionRequest},
        paravoid::apk_grant,
        services::personalization::Personalizer,
    };
    use std::path::PathBuf;
    let sdk = PathBuf::from(std::env::var("ANDROID_HOME").expect("ANDROID_HOME required"));
    let tools = sdk.join("build-tools/36.0.0");
    let dir = tempfile::tempdir().unwrap();
    let signing = keys(dir.path());
    let ctx = common::create_paravoid_test_context(signing.clone()).await;
    seed(&ctx.pool, &signing, "apkKey").await;
    let manifest = dir.path().join("AndroidManifest.xml");
    std::fs::write(&manifest, r#"<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.app" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/><application android:label="Carrier test"/></manifest>"#).unwrap();
    let unsigned = dir.path().join("unsigned.apk");
    let keystore = dir.path().join("test.p12");
    let source = ctx.storage_path.join("apk");
    let commands = [
        Command::new(tools.join("aapt2"))
            .arg("link")
            .arg("--manifest")
            .arg(&manifest)
            .arg("-I")
            .arg(sdk.join("platforms/android-36/android.jar"))
            .arg("-o")
            .arg(&unsigned)
            .output()
            .unwrap(),
        Command::new("keytool")
            .args(["-genkeypair", "-keystore"])
            .arg(&keystore)
            .args([
                "-storepass",
                "testpassword",
                "-keypass",
                "testpassword",
                "-alias",
                "test",
                "-dname",
                "CN=Ephemeral Store Test",
                "-keyalg",
                "RSA",
                "-keysize",
                "3072",
                "-validity",
                "2",
                "-noprompt",
            ])
            .output()
            .unwrap(),
        Command::new(tools.join("apksigner"))
            .args(["sign", "--ks"])
            .arg(&keystore)
            .args([
                "--ks-key-alias",
                "test",
                "--ks-pass",
                "pass:testpassword",
                "--key-pass",
                "pass:testpassword",
                "--v1-signing-enabled",
                "false",
                "--v2-signing-enabled",
                "true",
                "--v3-signing-enabled",
                "true",
                "--v4-signing-enabled",
                "false",
                "--out",
            ])
            .arg(&source)
            .arg(&unsigned)
            .output()
            .unwrap(),
    ];
    for result in commands {
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
    }
    let certificate = lellostore_backend::services::apk_signatures::verified_signer_with_tool(
        &tools.join("apksigner"),
        &source,
    )
    .await
    .unwrap();
    assert_eq!(certificate.len(), 64);
    let registered = paravoid::contract(&ctx.pool, "test.app", &"a".repeat(64))
        .await
        .unwrap();
    let trust: Value = serde_json::from_str(&registered.trust_json).unwrap();
    let descriptor = json!({"profile":"complete-apk-v1","runtimeAbi":1,"trustPolicy":trust,
        "installed":{"applicationId":"test.app","minSdk":30,"manifestSha256":"b".repeat(64),"declarations":{},"pinnedResources":{},"runtimeClasses":{},"nativeAbis":{},"ledgerReservations":{},"apkSigners":[certificate],"toolchain":{}},
        "distribution":{"bootstrap":"embedded","enabled":true,"baseUrl":"https://store.test/api/paravoid/","channel":"stable","authentication":"apkKey","debugHttpAllowed":false}});
    let canonical = lellostore_backend::paravoid::canonical_json(&descriptor).unwrap();
    let contract_id = hex::encode(Sha256::digest(&canonical));
    let document = lellostore_backend::paravoid::canonical_json(
        &json!({"version":1,"contractId":contract_id,"descriptor":descriptor}),
    )
    .unwrap();
    let archive = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(&unsigned)
        .unwrap();
    let mut zip = zip::ZipWriter::new_append(archive).unwrap();
    zip.start_file(
        lellostore_backend::paravoid::shell_policy::APK_PATH,
        zip::write::SimpleFileOptions::default(),
    )
    .unwrap();
    std::io::Write::write_all(&mut zip, &document).unwrap();
    zip.finish().unwrap();
    let signed = Command::new(tools.join("apksigner"))
        .args(["sign", "--ks"])
        .arg(&keystore)
        .args([
            "--ks-key-alias",
            "test",
            "--ks-pass",
            "pass:testpassword",
            "--v1-signing-enabled",
            "false",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "true",
            "--v4-signing-enabled",
            "false",
            "--out",
        ])
        .arg(&source)
        .arg(&unsigned)
        .output()
        .unwrap();
    assert!(
        signed.status.success(),
        "{}",
        String::from_utf8_lossy(&signed.stderr)
    );
    let mut tx = ctx.pool.begin().await.unwrap();
    sqlx::query("PRAGMA defer_foreign_keys=ON")
        .execute(&mut *tx)
        .await
        .unwrap();
    sqlx::query("UPDATE paravoid_contracts SET contract_id=?,descriptor_json=?")
        .bind(&contract_id)
        .bind(String::from_utf8(canonical).unwrap())
        .execute(&mut *tx)
        .await
        .unwrap();
    sqlx::query("UPDATE paravoid_installers SET contract_id=?")
        .bind(&contract_id)
        .execute(&mut *tx)
        .await
        .unwrap();
    sqlx::query("UPDATE vpk_releases SET contract_id=?")
        .bind(&contract_id)
        .execute(&mut *tx)
        .await
        .unwrap();
    tx.commit().await.unwrap();
    let original = std::fs::read(&source).unwrap();
    sqlx::query("UPDATE app_versions SET sha256 = ?, size = ? WHERE package_name = 'test.app'")
        .bind(hex::encode(Sha256::digest(&original)))
        .bind(original.len() as i64)
        .execute(&ctx.pool)
        .await
        .unwrap();
    db::access::set_direct_grant(
        &ctx.pool,
        "alice",
        "test.app",
        db::access::AppAccessLevel::Stable,
    )
    .await
    .unwrap();
    let personalizer = Personalizer::new(
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../scripts/paravoid-personalize.py"),
        tools.join("apksigner"),
        PathBuf::from(env!("CARGO_BIN_EXE_paravoid_grant_check")),
    );
    let mut request = AcquisitionRequest {
        version_code: 1,
        purpose: AcquisitionPurpose::Install,
        idempotency_key: "initial".into(),
    };
    let first = personalizer
        .acquire(
            &ctx.pool,
            &ctx.storage_path,
            &signing,
            "alice",
            "test.app",
            &request,
        )
        .await
        .unwrap();
    let repeated = personalizer
        .acquire(
            &ctx.pool,
            &ctx.storage_path,
            &signing,
            "alice",
            "test.app",
            &request,
        )
        .await
        .unwrap();
    assert_eq!(first.id, repeated.id);
    let output = ctx.storage_path.join(&first.apk_path);
    let delivered = std::fs::read(&output).unwrap();
    assert_eq!(first.sha256, hex::encode(Sha256::digest(&delivered)));
    assert_eq!(first.size, delivered.len() as i64);
    assert_eq!(std::fs::read(&source).unwrap(), original);
    assert_eq!(
        apk_grant::inspect(&mut std::fs::File::open(&source).unwrap())
            .unwrap()
            .signatures,
        apk_grant::inspect(&mut std::fs::File::open(&output).unwrap())
            .unwrap()
            .signatures
    );
    let mut wrong_trust = trust.clone();
    wrong_trust["minimumHeadRevision"] = json!(99);
    let wrong_trust_path = dir.path().join("wrong-trust.json");
    std::fs::write(&wrong_trust_path, wrong_trust.to_string()).unwrap();
    let mismatched = Command::new(env!("CARGO_BIN_EXE_paravoid_grant_check"))
        .arg(&wrong_trust_path)
        .arg(&contract_id)
        .args(["https://store.test/api/paravoid/", "stable", "apk"])
        .arg(&output)
        .arg("--source-apk")
        .arg(&source)
        .output()
        .unwrap();
    assert!(
        !mismatched.status.success(),
        "Detached trust must not override the signed APK policy"
    );
    if let Ok(classes) = std::env::var("PARAVOID_JAVA_CLASSES") {
        let trust = ctx
            .storage_path
            .join("acquisitions")
            .join(&first.id)
            .join("trust.json");
        let checked = Command::new("java")
            .args([
                "-cp",
                &classes,
                "com.lelloman.paravoidandroid.delivery.tools.GrantCheck",
            ])
            .arg(&trust)
            .arg(&contract_id)
            .args(["https://store.test/api/paravoid/", "stable", "apk"])
            .arg(&output)
            .output()
            .unwrap();
        assert!(
            checked.status.success(),
            "Upstream Java rejected Store-personalized grant"
        );
        let pinned = Command::new("java")
            .args([
                "-cp",
                &classes,
                "com.lelloman.paravoidandroid.delivery.tools.GrantCheck",
                "pinned",
            ])
            .arg(&source)
            .arg("apk")
            .arg(&output)
            .output()
            .unwrap();
        assert!(
            pinned.status.success(),
            "Upstream APK-pinned grant verification failed"
        );
        paravoid::publish(&ctx.pool, "test.app", "vpk-1", "admin", 0)
            .await
            .unwrap();
        let server = TestServer::new(simple_server::web::compat::into_axum_router(
            ctx.router.clone(),
        ))
        .unwrap();
        let envelope = apk_grant::read(&mut std::fs::File::open(&output).unwrap()).unwrap();
        let policy = paravoid::contract(&ctx.pool, "test.app", &contract_id)
            .await
            .unwrap()
            .policy()
            .unwrap();
        let grant = policy.verify_grant(&envelope).unwrap();
        let response = server
            .get(&head_url().replace(&"a".repeat(64), &contract_id))
            .add_header("Authorization", format!("Bearer {}", grant.credential()))
            .await;
        response.assert_status_ok();
        let head = dir.path().join("store-head.json");
        std::fs::write(&head, response.as_bytes()).unwrap();
        let checked = Command::new("java")
            .args(["-cp", &classes, "StoreHeadCheck"])
            .arg(&trust)
            .arg(&contract_id)
            .arg("https://store.test/api/paravoid/")
            .arg(&head)
            .output()
            .unwrap();
        assert!(
            checked.status.success(),
            "Upstream Java rejected Store signed head"
        );
    }
    request.idempotency_key = "repair".into();
    request.purpose = AcquisitionPurpose::Repair;
    let repair = personalizer
        .acquire(
            &ctx.pool,
            &ctx.storage_path,
            &signing,
            "alice",
            "test.app",
            &request,
        )
        .await
        .unwrap();
    assert_ne!(repair.id, first.id);
    assert_ne!(repair.sha256, first.sha256);
    let grants = paravoid::grants(&ctx.pool, "test.app").await.unwrap();
    assert_eq!(grants.len(), 2);
    assert_ne!(grants[0].key_id, grants[1].key_id);
    // Expired transfer bytes can be reclaimed without revoking installed grants
    // or destroying canonical APKs, stable identities or future repair copies.
    sqlx::query("UPDATE personalization_jobs SET expires_at = 1 WHERE id = ?")
        .bind(&first.id)
        .execute(&ctx.pool)
        .await
        .unwrap();
    let removed = lellostore_backend::services::retention::cleanup(
        &ctx.pool,
        &ctx.storage_path,
        chrono::Utc::now().timestamp(),
    )
    .await
    .unwrap();
    assert_eq!(removed, 1);
    assert!(!output.exists());
    assert!(ctx.storage_path.join(&repair.apk_path).exists());
    assert_eq!(std::fs::read(&source).unwrap(), original);
    assert_eq!(
        paravoid::grants(&ctx.pool, "test.app").await.unwrap().len(),
        2
    );
    db::access::remove_direct_grant(&ctx.pool, "alice", "test.app")
        .await
        .unwrap();
    assert!(personalizer
        .acquire(
            &ctx.pool,
            &ctx.storage_path,
            &signing,
            "alice",
            "test.app",
            &request
        )
        .await
        .is_err());
}

#[tokio::test]
async fn push_subscription_resynchronizes_and_rejects_wrong_scope() {
    let dir = tempfile::tempdir().unwrap();
    let signing = keys(dir.path());
    let ctx = common::create_paravoid_test_context(signing.clone()).await;
    seed(&ctx.pool, &signing, "public").await;
    let server = TestServer::builder()
        .http_transport()
        .build(simple_server::web::compat::into_axum_router(ctx.router))
        .unwrap();
    let subscription = json!({"version":1,"type":"subscribe","applicationId":"test.app","shellContractId":"a".repeat(64),"channel":"stable"});
    let mut ids = Vec::new();
    for _ in 0..2 {
        let mut socket = server
            .get_websocket("/api/paravoid/v1/events")
            .add_header(
                simple_server::web::http::header::SEC_WEBSOCKET_PROTOCOL,
                "paravoid.updates.v1",
            )
            .await
            .into_websocket()
            .await;
        socket.send_json(&subscription).await;
        let hint: Value =
            tokio::time::timeout(std::time::Duration::from_secs(5), socket.receive_json())
                .await
                .unwrap();
        assert_eq!(hint["type"], "updates_changed");
        assert_eq!(hint["version"], 1);
        assert_eq!(hint["applicationId"], "test.app");
        assert_eq!(hint["shellContractId"], "a".repeat(64));
        assert_eq!(hint["channel"], "stable");
        ids.push(hint["eventId"].clone());
    }
    assert_ne!(ids[0], ids[1]);
    let mut socket = server
        .get_websocket("/api/paravoid/v1/events")
        .await
        .into_websocket()
        .await;
    let mut wrong = subscription;
    wrong["channel"] = json!("beta");
    socket.send_json(&wrong).await;
    let message = tokio::time::timeout(std::time::Duration::from_secs(5), socket.receive_text())
        .await
        .unwrap();
    assert!(
        message.is_empty(),
        "wrong scope must close without an event"
    );
}
