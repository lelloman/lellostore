//! A durable, single-worker validation queue. Publication is never a worker action.
use super::UploadService;
use crate::error::AppError;
use serde::Serialize;
use simple_server::lifecycle::Shutdown;
use sqlx::SqlitePool;
use std::sync::Arc;

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct UploadJob {
    pub id: String,
    pub actor_subject: String,
    pub file_name: String,
    #[serde(skip_serializing)]
    pub input_path: String,
    pub override_name: Option<String>,
    pub override_description: Option<String>,
    pub is_beta: bool,
    pub status: String,
    pub result_json: Option<String>,
    pub error: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

pub async fn get(pool: &SqlitePool, id: &str) -> Result<UploadJob, AppError> {
    sqlx::query_as("SELECT * FROM upload_jobs WHERE id = ?")
        .bind(id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| AppError::NotFound("Upload not found".into()))
}

pub async fn process_next(pool: &SqlitePool, service: &UploadService) -> Result<bool, AppError> {
    let job = sqlx::query_as::<_, UploadJob>("UPDATE upload_jobs SET status = 'validating', error = NULL, updated_at = datetime('now') WHERE id = (SELECT id FROM upload_jobs WHERE status = 'queued' ORDER BY created_at, id LIMIT 1) AND status = 'queued' RETURNING *")
        .fetch_optional(pool).await?;
    let Some(job) = job else {
        return Ok(false);
    };
    let result = service.process_queued_upload(&job).await;
    if let Err(error) = result {
        let message = match error {
            super::UploadError::DatabaseError(_)
            | super::UploadError::StorageError(_)
            | super::UploadError::Io(_) => {
                tracing::error!(upload_id = %job.id, %error, "Upload validation failed");
                "Storage or database failure during validation. Retry after resolving the server error.".to_string()
            }
            _ => error.to_string(),
        };
        sqlx::query("UPDATE upload_jobs SET status = 'failed', error = ?, updated_at = datetime('now') WHERE id = ? AND status = 'validating'")
            .bind(message).bind(&job.id).execute(pool).await?;
    }
    // Success is recorded in the same transaction as the draft, so a restart
    // cannot turn a successfully inserted version into a duplicate-upload failure.
    Ok(true)
}

pub async fn run(
    pool: SqlitePool,
    service: Arc<UploadService>,
    shutdown: Shutdown,
) -> Result<(), AppError> {
    sqlx::query("UPDATE upload_jobs SET status = 'queued' WHERE status = 'validating'")
        .execute(&pool)
        .await?;
    loop {
        if shutdown.is_requested() {
            return Ok(());
        }
        if !process_next(&pool, &service).await? {
            tokio::select! {
                _ = shutdown.requested() => return Ok(()),
                _ = tokio::time::sleep(std::time::Duration::from_secs(1)) => {},
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub async fn enqueue(
    pool: &SqlitePool,
    storage: &std::path::Path,
    actor: &str,
    file_name: &str,
    source: &std::path::Path,
    name: Option<String>,
    description: Option<String>,
    is_beta: bool,
) -> Result<UploadJob, AppError> {
    let id = uuid::Uuid::new_v4().to_string();
    let directory = storage.join("uploads");
    tokio::fs::create_dir_all(&directory).await?;
    let input = directory.join(&id);
    tokio::fs::copy(source, &input).await?;
    tokio::fs::File::open(&input).await?.sync_all().await?;
    tokio::fs::File::open(&directory).await?.sync_all().await?;
    let result = sqlx::query("INSERT INTO upload_jobs(id, actor_subject, file_name, input_path, override_name, override_description, is_beta) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .bind(&id).bind(actor).bind(file_name).bind(input.to_string_lossy().as_ref()).bind(name).bind(description).bind(is_beta).execute(pool).await;
    if let Err(error) = result {
        let _ = tokio::fs::remove_file(input).await;
        return Err(error.into());
    }
    get(pool, &id).await
}
