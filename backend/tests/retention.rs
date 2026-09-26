#[allow(dead_code)]
mod common;

#[tokio::test]
async fn cleanup_preserves_referenced_failed_uploads_and_removes_only_old_generated_orphans() {
    let ctx = common::create_test_context().await;
    let uploads = ctx.storage_path.join("uploads");
    let copies = ctx.storage_path.join("acquisitions");
    std::fs::create_dir_all(&uploads).unwrap();
    std::fs::create_dir_all(&copies).unwrap();
    let orphan = uuid::Uuid::new_v4().to_string();
    let referenced = uuid::Uuid::new_v4().to_string();
    let directory = uuid::Uuid::new_v4().to_string();
    std::fs::write(uploads.join(&orphan), b"orphan").unwrap();
    std::fs::write(uploads.join(&referenced), b"keep").unwrap();
    std::fs::write(uploads.join("operator-notes"), b"keep").unwrap();
    std::fs::create_dir(copies.join(&directory)).unwrap();
    sqlx::query("INSERT INTO upload_jobs(id,actor_subject,file_name,input_path,status,is_beta) VALUES (?,'admin','original.apk',?,'failed',0)").bind(&referenced).bind(uploads.join(&referenced).to_str().unwrap()).execute(&ctx.pool).await.unwrap();
    let now = chrono::Utc::now().timestamp();
    assert_eq!(
        lellostore_backend::services::retention::cleanup(&ctx.pool, &ctx.storage_path, now)
            .await
            .unwrap(),
        0
    );
    assert_eq!(
        lellostore_backend::services::retention::cleanup(
            &ctx.pool,
            &ctx.storage_path,
            now + 8 * 86400
        )
        .await
        .unwrap(),
        2
    );
    assert!(uploads.join(&referenced).is_file());
    assert!(uploads.join("operator-notes").is_file());
    assert!(!uploads.join(&orphan).exists());
    assert!(!copies.join(&directory).exists());
}

#[tokio::test]
async fn replaced_cleanup_retries_failures_and_preserves_shared_files() {
    let ctx = common::create_test_context().await;
    sqlx::query("INSERT INTO apps(package_name,name) VALUES ('test.app','Test')")
        .execute(&ctx.pool)
        .await
        .unwrap();
    for code in [1, 2, 3] {
        sqlx::query("INSERT INTO app_versions(package_name,version_code,version_name,apk_path,size,sha256,min_sdk,artifact_removed) VALUES ('test.app',?,'1',?,3,'hash',24,?)")
            .bind(code).bind(if code == 1 { "apks/retry.apk" } else { "apks/shared.apk" }).bind(code != 3).execute(&ctx.pool).await.unwrap();
    }
    let path = ctx.storage_path.join("apks/retry.apk");
    std::fs::create_dir(&path).unwrap(); // Force unlink to fail.
    std::fs::write(ctx.storage_path.join("apks/shared.apk"), b"apk").unwrap();
    assert!(lellostore_backend::services::retention::cleanup_replaced(
        &ctx.pool,
        &ctx.storage_path
    )
    .await
    .is_err());
    let cleaned: bool =
        sqlx::query_scalar("SELECT artifact_cleaned FROM app_versions WHERE version_code=1")
            .fetch_one(&ctx.pool)
            .await
            .unwrap();
    assert!(!cleaned);
    std::fs::remove_dir(&path).unwrap();
    std::fs::write(&path, b"apk").unwrap();
    assert_eq!(
        lellostore_backend::services::retention::cleanup_replaced(&ctx.pool, &ctx.storage_path)
            .await
            .unwrap(),
        1
    );
    assert!(!path.exists());
    assert!(ctx.storage_path.join("apks/shared.apk").exists());
    assert_eq!(
        lellostore_backend::services::retention::cleanup_replaced(&ctx.pool, &ctx.storage_path)
            .await
            .unwrap(),
        0
    );
}
