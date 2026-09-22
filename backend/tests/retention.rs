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
