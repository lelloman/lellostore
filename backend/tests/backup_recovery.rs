#[allow(dead_code)]
mod common;
use sha2::{Digest, Sha256};
use std::{
    path::Path,
    process::{Command, Output},
};

fn verify(db: &Path, storage: &Path, expected: Option<&Path>) -> Output {
    let mut command = Command::new("python3");
    command
        .arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("../scripts/verify-store-backup.py"))
        .arg("--database")
        .arg(db)
        .arg("--storage")
        .arg(storage);
    if let Some(expected) = expected {
        command.arg("--expected-state").arg(expected);
    }
    command.output().unwrap()
}

#[tokio::test]
async fn restored_snapshot_preserves_replay_and_revocation_and_detects_missing_bytes() {
    let ctx = common::create_test_context().await;
    let bytes = b"retained installer bytes";
    let sha = hex::encode(Sha256::digest(bytes));
    std::fs::write(ctx.storage_path.join("apks/fixture.apk"), bytes).unwrap();
    sqlx::query("INSERT INTO apps(package_name,name,publication_revision) VALUES ('example.app','Recovery fixture',19)").execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk) VALUES ('example.app',3,'3','apks/fixture.apk',?, ?,30)").bind(bytes.len() as i64).bind(&sha).execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO published_apk_identities(package_name,version_code,sha256) VALUES ('example.app',3,?)").bind(&sha).execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state) VALUES ('example.app','contract',3,'stable','apkKey','embedded','https://example.test/','{}','{}','pending')").execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO paravoid_streams(package_name,contract_id,revision,status) VALUES ('example.app','contract',41,'retired')").execute(&ctx.pool).await.unwrap();
    sqlx::query("INSERT INTO paravoid_grants(id,key_id,credential_sha256,package_name,contract_id,installer_version,user_subject,acquisition_id,issued_at,revoked_at) VALUES ('grant','key','never-a-credential','example.app','contract',3,'owner','acquisition',1,100)").execute(&ctx.pool).await.unwrap();
    let db = ctx.temp_dir.path().join("test.db");
    let snapshot = verify(&db, &ctx.storage_path, None);
    assert!(
        snapshot.status.success(),
        "{}",
        String::from_utf8_lossy(&snapshot.stderr)
    );
    let report = String::from_utf8(snapshot.stdout.clone()).unwrap();
    assert!(!report.contains("never-a-credential"));
    let checkpoint = ctx.temp_dir.path().join("checkpoint.json");
    std::fs::write(&checkpoint, &snapshot.stdout).unwrap();
    let restored = ctx.temp_dir.path().join("restored.db");
    sqlx::query("VACUUM INTO ?")
        .bind(restored.to_str().unwrap())
        .execute(&ctx.pool)
        .await
        .unwrap();
    assert!(verify(&restored, &ctx.storage_path, Some(&checkpoint))
        .status
        .success());
    // Detect a logically stale recovery even if SQLite integrity and all file hashes pass.
    let recovered = sqlx::SqlitePool::connect(&format!("sqlite:{}", restored.display()))
        .await
        .unwrap();
    sqlx::query("UPDATE paravoid_grants SET revoked_at = NULL")
        .execute(&recovered)
        .await
        .unwrap();
    assert!(!verify(&restored, &ctx.storage_path, Some(&checkpoint))
        .status
        .success());
    sqlx::query("UPDATE paravoid_grants SET revoked_at = 100")
        .execute(&recovered)
        .await
        .unwrap();
    sqlx::query("UPDATE paravoid_streams SET revision = 40")
        .execute(&recovered)
        .await
        .unwrap();
    assert!(!verify(&restored, &ctx.storage_path, Some(&checkpoint))
        .status
        .success());
    sqlx::query("UPDATE paravoid_streams SET revision = 41")
        .execute(&recovered)
        .await
        .unwrap();
    assert!(verify(&restored, &ctx.storage_path, Some(&checkpoint))
        .status
        .success());
    // A truncated/corrupted retained APK is rejected independently of the checkpoint.
    std::fs::write(ctx.storage_path.join("apks/fixture.apk"), b"corrupt").unwrap();
    assert!(!verify(&restored, &ctx.storage_path, None).status.success());
    recovered.close().await;
}

#[tokio::test]
async fn backup_accepts_intentionally_replaced_files_but_requires_archived_files() {
    let ctx = common::create_test_context().await;
    sqlx::query("INSERT INTO apps(package_name,name) VALUES ('example.app','Test')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk,publication_state,artifact_removed) VALUES ('example.app',1,'1','apks/gone.apk',3,'hash',24,'withdrawn',1)").execute(&ctx.pool).await.unwrap();
    let db = ctx.temp_dir.path().join("test.db");
    assert!(verify(&db, &ctx.storage_path, None).status.success());
    sqlx::query("UPDATE app_versions SET artifact_removed=0, archived=1")
        .execute(&ctx.pool)
        .await
        .unwrap();
    assert!(!verify(&db, &ctx.storage_path, None).status.success());
}
