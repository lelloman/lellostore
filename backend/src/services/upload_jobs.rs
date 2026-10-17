//! A durable, single-worker validation queue. Publication is never a worker action.
use super::UploadService;
use crate::error::AppError;
use serde::Serialize;
use simple_server::lifecycle::Shutdown;
use sqlx::SqlitePool;
use std::sync::Arc;

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct UploadJob {
    pub kind: String,
    pub distribution_mode: String,
    pub package_name: Option<String>,
    pub contract_id: Option<String>,
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
    /// The publish token that created this job, if any. The worker enforces
    /// that token's package after parsing the artifact.
    pub publish_token_id: Option<String>,
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
    // A publish token's job must still be authorized when it runs: the token
    // may have been revoked or expired while the job was queued.
    let token_package = match &job.publish_token_id {
        Some(token) => match crate::db::publish_tokens::active_package(pool, token).await? {
            Some(package) => Some(package),
            None => {
                return fail_unauthorized(
                    pool,
                    &job,
                    "The publish token that uploaded this artifact was revoked or expired",
                )
                .await;
            }
        },
        None => None,
    };
    if let (Some(expected), Some(target)) = (&token_package, &job.package_name) {
        if expected != target {
            return fail_unauthorized(
                pool,
                &job,
                &format!("This publish token is not allowed to upload {target}"),
            )
            .await;
        }
    }
    let result = if job.kind == "vpk" {
        super::vpks::process_job(pool, service.storage_root(), &job)
            .await
            .map_err(|e| match e {
                AppError::BadRequest(message)
                | AppError::Conflict(message)
                | AppError::NotFound(message) => super::UploadError::InvalidPayload(message),
                other => super::UploadError::DatabaseError(other),
            })
    } else {
        service
            .process_queued_upload(&job, token_package.as_deref())
            .await
            .map(|_| ())
    };
    if let Err(error) = result {
        if let super::UploadError::PackageNotAllowed { .. } = &error {
            return fail_unauthorized(pool, &job, &error.to_string()).await;
        }
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

/// Fail a job that its uploader was not authorized to complete, and discard
/// the uploaded artifact instead of keeping it for a retry.
async fn fail_unauthorized(
    pool: &SqlitePool,
    job: &UploadJob,
    message: &str,
) -> Result<bool, AppError> {
    if let Some(token) = &job.publish_token_id {
        tracing::warn!(upload_id = %job.id, token_id = %token, message, "Publish token upload refused");
    }
    if let Err(error) = tokio::fs::remove_file(&job.input_path).await {
        if error.kind() != std::io::ErrorKind::NotFound {
            tracing::warn!(upload_id = %job.id, %error, "Refused upload input could not be removed");
        }
    }
    sqlx::query("UPDATE upload_jobs SET status = 'failed', error = ?, input_cleaned_at = unixepoch(), updated_at = datetime('now') WHERE id = ? AND status = 'validating'")
        .bind(format!("Forbidden: {message}"))
        .bind(&job.id)
        .execute(pool)
        .await?;
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
    let mut next_cleanup = tokio::time::Instant::now();
    loop {
        if tokio::time::Instant::now() >= next_cleanup {
            match super::retention::cleanup(
                &pool,
                service.storage_root(),
                chrono::Utc::now().timestamp(),
            )
            .await
            {
                Ok(removed) if removed > 0 => {
                    tracing::info!(removed, "Expired transfer copies cleaned up")
                }
                Ok(_) => {}
                Err(error) => tracing::warn!(%error, "Transfer cleanup will retry next hour"),
            }
            next_cleanup = tokio::time::Instant::now() + std::time::Duration::from_secs(3600);
        }
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
async fn enqueue_inner(
    pool: &SqlitePool,
    storage: &std::path::Path,
    actor: &str,
    file_name: &str,
    source: &std::path::Path,
    name: Option<String>,
    description: Option<String>,
    is_beta: bool,
    target: Option<(&str, &str)>,
    mode: &str,
    publish_token: Option<&str>,
) -> Result<UploadJob, AppError> {
    if !["normal", "paravoid"].contains(&mode) {
        return Err(AppError::BadRequest("Invalid distribution mode".into()));
    }
    let id = uuid::Uuid::new_v4().to_string();
    let directory = storage.join("uploads");
    tokio::fs::create_dir_all(&directory).await?;
    let input = directory.join(&id);
    tokio::fs::copy(source, &input).await?;
    tokio::fs::File::open(&input).await?.sync_all().await?;
    tokio::fs::File::open(&directory).await?.sync_all().await?;
    let result = sqlx::query("INSERT INTO upload_jobs(id, actor_subject, file_name, input_path, override_name, override_description, is_beta, kind, package_name, contract_id, distribution_mode, publish_token_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .bind(&id).bind(actor).bind(file_name).bind(input.to_string_lossy().as_ref()).bind(name).bind(description).bind(is_beta).bind(if target.is_some() {"vpk"} else {"apk"}).bind(target.map(|v| v.0)).bind(target.map(|v| v.1)).bind(mode).bind(publish_token).execute(pool).await;
    if let Err(error) = result {
        let _ = tokio::fs::remove_file(input).await;
        return Err(error.into());
    }
    get(pool, &id).await
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
    enqueue_inner(
        pool,
        storage,
        actor,
        file_name,
        source,
        name,
        description,
        is_beta,
        None,
        "normal",
        None,
    )
    .await
}
#[allow(clippy::too_many_arguments)]
pub async fn enqueue_vpk(
    pool: &SqlitePool,
    storage: &std::path::Path,
    actor: &str,
    file_name: &str,
    source: &std::path::Path,
    package: &str,
    contract: &str,
) -> Result<UploadJob, AppError> {
    enqueue_vpk_for_token(
        pool, storage, actor, file_name, source, package, contract, None,
    )
    .await
}

/// Queue a VPK, recording the publish token that uploaded it, if any.
#[allow(clippy::too_many_arguments)]
pub async fn enqueue_vpk_for_token(
    pool: &SqlitePool,
    storage: &std::path::Path,
    actor: &str,
    file_name: &str,
    source: &std::path::Path,
    package: &str,
    contract: &str,
    publish_token: Option<&str>,
) -> Result<UploadJob, AppError> {
    enqueue_inner(
        pool,
        storage,
        actor,
        file_name,
        source,
        None,
        None,
        false,
        Some((package, contract)),
        "paravoid",
        publish_token,
    )
    .await
}

#[allow(clippy::too_many_arguments)]
pub async fn enqueue_with_mode(
    pool: &SqlitePool,
    storage: &std::path::Path,
    actor: &str,
    file_name: &str,
    source: &std::path::Path,
    name: Option<String>,
    description: Option<String>,
    is_beta: bool,
    mode: &str,
) -> Result<UploadJob, AppError> {
    enqueue_with_mode_for_token(
        pool,
        storage,
        actor,
        file_name,
        source,
        name,
        description,
        is_beta,
        mode,
        None,
    )
    .await
}

/// Queue an APK/AAB upload, recording the publish token that uploaded it, if
/// any. The worker rejects the artifact unless its parsed package matches the
/// token's package.
#[allow(clippy::too_many_arguments)]
pub async fn enqueue_with_mode_for_token(
    pool: &SqlitePool,
    storage: &std::path::Path,
    actor: &str,
    file_name: &str,
    source: &std::path::Path,
    name: Option<String>,
    description: Option<String>,
    is_beta: bool,
    mode: &str,
    publish_token: Option<&str>,
) -> Result<UploadJob, AppError> {
    enqueue_inner(
        pool,
        storage,
        actor,
        file_name,
        source,
        name,
        description,
        is_beta,
        None,
        mode,
        publish_token,
    )
    .await
}
