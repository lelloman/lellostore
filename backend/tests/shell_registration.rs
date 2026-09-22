#[allow(dead_code)]
mod common;
use lellostore_backend::{
    paravoid::shell_policy::ShellPolicyDocument,
    services::{upload_jobs, ApkParser, StorageService, UploadService},
};
use sha2::{Digest, Sha256};
use std::{io::Write, process::Command};

#[path = "common/signed_shell.rs"]
mod signed_shell;
use signed_shell::{apk, document, run};

#[tokio::test]
#[ignore = "requires ANDROID_HOME and APKSIGNER_PATH with build-tools/platform 36"]
async fn signed_shell_jobs_register_shared_contract_and_reject_wrong_mode_or_channel() {
    let sdk = std::path::PathBuf::from(std::env::var("ANDROID_HOME").unwrap());
    let ctx = common::create_test_context().await;
    let dir = tempfile::tempdir().unwrap();
    run(Command::new("keytool")
        .args(["-genkeypair", "-keystore"])
        .arg(dir.path().join("test.p12"))
        .args([
            "-storepass",
            "testpassword",
            "-keypass",
            "testpassword",
            "-alias",
            "test",
            "-dname",
            "CN=Ephemeral Registration Test",
            "-keyalg",
            "RSA",
            "-keysize",
            "3072",
            "-validity",
            "2",
            "-noprompt",
        ]));
    let cert = dir.path().join("cert.der");
    run(Command::new("keytool")
        .args(["-exportcert", "-keystore"])
        .arg(dir.path().join("test.p12"))
        .args(["-storepass", "testpassword", "-alias", "test", "-file"])
        .arg(&cert));
    let signer = hex::encode(Sha256::digest(std::fs::read(cert).unwrap()));
    let policy = document(&signer);
    let contract = ShellPolicyDocument::parse(&policy).unwrap().contract_id;
    let service = UploadService::new(
        StorageService::new(ctx.storage_path.clone()),
        ApkParser::new(sdk.join("build-tools/36.0.0/aapt2")),
        None,
        ctx.pool.clone(),
        10 * 1024 * 1024,
    );
    for code in [1, 2] {
        let source = apk(dir.path(), &sdk, code, &policy);
        let metadata = ApkParser::new(sdk.join("build-tools/36.0.0/aapt2"))
            .parse(&source)
            .await
            .unwrap();
        assert_eq!(metadata.package_name, "example.app");
        assert_eq!(metadata.min_sdk, 30);
        assert_eq!(
            lellostore_backend::services::apk_signatures::verified_signer(&source)
                .await
                .unwrap(),
            signer
        );
        assert!(service
            .process_draft_upload("shell.apk", &source, None, None, false)
            .await
            .is_err());
        let job = upload_jobs::enqueue_with_mode(
            &ctx.pool,
            &ctx.storage_path,
            "admin",
            "shell.apk",
            &source,
            None,
            None,
            false,
            "paravoid",
        )
        .await
        .unwrap();
        assert_eq!(job.distribution_mode, "paravoid");
        upload_jobs::process_next(&ctx.pool, &service)
            .await
            .unwrap();
        let result = upload_jobs::get(&ctx.pool, &job.id).await.unwrap();
        assert_eq!(result.status, "ready", "{:?}", result.error);
    }
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM paravoid_contracts")
        .fetch_one(&ctx.pool)
        .await
        .unwrap();
    assert_eq!(count, 1);
    let count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM paravoid_installers WHERE contract_id = ? AND signer_sha256 = ?",
    )
    .bind(&contract)
    .bind(&signer)
    .fetch_one(&ctx.pool)
    .await
    .unwrap();
    assert_eq!(count, 2);
    let modes: Vec<(String, String)> =
        sqlx::query_as("SELECT distribution_mode,publication_state FROM app_versions")
            .fetch_all(&ctx.pool)
            .await
            .unwrap();
    assert!(modes
        .iter()
        .all(|v| v == &("paravoid".into(), "draft".into())));
    let source = apk(dir.path(), &sdk, 3, &policy);
    assert!(service
        .process_distribution_draft_upload("shell.apk", &source, None, None, true, "paravoid")
        .await
        .is_err());
    let wrong = apk(dir.path(), &sdk, 4, &document(&"0".repeat(64)));
    assert!(service
        .process_distribution_draft_upload("shell.apk", &wrong, None, None, false, "paravoid")
        .await
        .is_err());
}

#[test]
fn apk_policy_extraction_rejects_missing_oversized_and_duplicate_names() {
    use lellostore_backend::paravoid::{apk_policy, shell_policy::APK_PATH};
    let policy = document(&"c".repeat(64));
    let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    zip.start_file(APK_PATH, zip::write::SimpleFileOptions::default())
        .unwrap();
    zip.write_all(&policy).unwrap();
    let bytes = zip.finish().unwrap().into_inner();
    assert!(apk_policy::read(&mut std::io::Cursor::new(&bytes)).is_ok());
    let mut missing = bytes.clone();
    for at in 0..missing.len() - APK_PATH.len() {
        if &missing[at..at + APK_PATH.len()] == APK_PATH.as_bytes() {
            missing[at] = b'b';
        }
    }
    assert!(apk_policy::read(&mut std::io::Cursor::new(missing)).is_err());
    // Duplicate the central record: the ZIP crate alone would collapse this name.
    let end = bytes.len() - 22;
    let start = u32::from_le_bytes(bytes[end + 16..end + 20].try_into().unwrap()) as usize;
    let central = &bytes[start..end];
    let mut duplicate = bytes[..end].to_vec();
    duplicate.extend_from_slice(central);
    let mut footer = bytes[end..].to_vec();
    footer[8..10].copy_from_slice(&2_u16.to_le_bytes());
    footer[10..12].copy_from_slice(&2_u16.to_le_bytes());
    footer[12..16].copy_from_slice(&((central.len() * 2) as u32).to_le_bytes());
    duplicate.extend_from_slice(&footer);
    assert!(apk_policy::read(&mut std::io::Cursor::new(duplicate)).is_err());
    let mut oversized = bytes;
    oversized[start + 24..start + 28].copy_from_slice(&(1024 * 1024 + 1_u32).to_le_bytes());
    assert!(apk_policy::read(&mut std::io::Cursor::new(oversized)).is_err());
}

async fn installer(pool: &sqlx::SqlitePool, code: i64, policy: &[u8]) {
    sqlx::query("INSERT OR IGNORE INTO apps(package_name,name) VALUES ('example.app','Shell')")
        .execute(pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk,publication_state) VALUES ('example.app',?,'1','installer.apk',1,'hash',30,'draft')").bind(code).execute(pool).await.unwrap();
    let shell = lellostore_backend::services::shells::VerifiedShell {
        policy: ShellPolicyDocument::parse(policy).unwrap(),
        signer: "c".repeat(64),
    };
    let mut tx = pool.begin().await.unwrap();
    lellostore_backend::services::shells::register(&mut tx, "example.app", code, &shell)
        .await
        .unwrap();
    tx.commit().await.unwrap();
}
async fn payload(pool: &sqlx::SqlitePool, id: &str, contract: &str, code: i64) {
    sqlx::query("INSERT INTO vpk_releases(id,package_name,contract_id,release_id,payload_version,archive_path,archive_size,archive_sha256,manifest_sha256,manifest_json,min_sdk,max_sdk,abis_json,signing_key_id,validation_state,validation_report) VALUES (?,'example.app',?,?,?,'payload.vpk',1,'hash','manifest','{}',30,0,'[]','release','verified','{}')")
        .bind(id).bind(contract).bind(id).bind(code).execute(pool).await.unwrap();
}
#[tokio::test]
async fn empty_bootstrap_publishes_atomically_and_rejects_coverage_and_identity_gaps() {
    use lellostore_backend::db::{
        self,
        publications::{self, PublishRequest},
    };
    let ctx = common::create_test_context().await;
    let bytes = document(&"c".repeat(64));
    let contract = ShellPolicyDocument::parse(&bytes).unwrap().contract_id;
    installer(&ctx.pool, 1, &bytes).await;
    assert!(
        db::admin::set_release_channel(&ctx.pool, "admin", "example.app", 1, true)
            .await
            .is_err()
    );
    payload(&ctx.pool, "bootstrap", &contract, 1).await;
    let mut request = PublishRequest {
        version_code: 1,
        expected_revision: 1,
        replace_latest: false,
        transition_review: None,
        bootstrap_vpk: None,
    };
    assert!(
        publications::publish(&ctx.pool, "example.app", "admin", &request)
            .await
            .is_err()
    );
    request.bootstrap_vpk = Some("bootstrap".into());
    for query in [
        "UPDATE vpk_releases SET validation_state='inspected'",
        "UPDATE vpk_releases SET max_sdk=35",
        "UPDATE vpk_releases SET min_sdk=31",
        "UPDATE vpk_releases SET abis_json='[\"arm64-v8a\"]'",
        "UPDATE vpk_releases SET publication_state='withdrawn'",
    ] {
        sqlx::query(query).execute(&ctx.pool).await.unwrap();
        assert!(
            publications::publish(&ctx.pool, "example.app", "admin", &request)
                .await
                .is_err(),
            "{query}"
        );
        let app = db::get_app(&ctx.pool, "example.app")
            .await
            .unwrap()
            .unwrap();
        assert_eq!(app.distribution_mode, "normal");
        assert_eq!(app.publication_revision, 1);
        let identities: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM published_vpk_identities")
            .fetch_one(&ctx.pool)
            .await
            .unwrap();
        assert_eq!(identities, 0);
        sqlx::query("UPDATE vpk_releases SET validation_state='verified',max_sdk=0,min_sdk=30,abis_json='[]',publication_state='draft'").execute(&ctx.pool).await.unwrap();
    }
    publications::publish(&ctx.pool, "example.app", "admin", &request)
        .await
        .unwrap();
    assert_eq!(
        db::get_app(&ctx.pool, "example.app")
            .await
            .unwrap()
            .unwrap()
            .distribution_mode,
        "paravoid"
    );
    assert_eq!(
        db::get_published_versions(&ctx.pool, "example.app")
            .await
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        db::paravoid::release(&ctx.pool, "example.app", "bootstrap")
            .await
            .unwrap()
            .publication_state,
        "published"
    );
    installer(&ctx.pool, 2, &bytes).await;
    request.version_code = 2;
    request.expected_revision = 3;
    // Reusing a published payload for an identical contract is not republishing it.
    publications::publish(&ctx.pool, "example.app", "admin", &request)
        .await
        .unwrap();
    let identities: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM published_vpk_identities")
        .fetch_one(&ctx.pool)
        .await
        .unwrap();
    assert_eq!(identities, 1);
    installer(&ctx.pool, 3, &bytes).await;
    payload(&ctx.pool, "next", &contract, 2).await;
    request.version_code = 3;
    request.expected_revision = 5;
    request.bootstrap_vpk = Some("next".into());
    sqlx::query("INSERT INTO published_apk_identities(package_name,version_code,sha256) VALUES ('example.app',10,'retained')").execute(&ctx.pool).await.unwrap();
    assert!(
        publications::publish(&ctx.pool, "example.app", "admin", &request)
            .await
            .is_err()
    );
    assert_eq!(
        db::paravoid::release(&ctx.pool, "example.app", "next")
            .await
            .unwrap()
            .publication_state,
        "draft"
    );
    let identities: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM published_vpk_identities")
        .fetch_one(&ctx.pool)
        .await
        .unwrap();
    assert_eq!(identities, 1);
}
