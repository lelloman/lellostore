//! Bounded cleanup of expendable transfer copies, never published artifacts or identities.
use crate::error::AppError;
use sqlx::SqlitePool;
use std::path::Path;

pub async fn cleanup(pool: &SqlitePool, storage: &Path, now: i64) -> Result<u64, AppError> {
    let mut removed = 0;
    // Keep a one-hour grace beyond acquisition expiry. Personalization is bounded
    // to minutes; expired jobs cannot resume, while installed grants remain valid.
    let jobs: Vec<String> = sqlx::query_scalar("SELECT id FROM personalization_jobs WHERE files_cleaned_at IS NULL AND expires_at < ? ORDER BY expires_at LIMIT 500")
        .bind(now - 3600).fetch_all(pool).await?;
    for id in jobs {
        if uuid::Uuid::parse_str(&id).is_err() {
            continue;
        }
        let path = storage.join("acquisitions").join(&id);
        match tokio::fs::symlink_metadata(&path).await {
            Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => {
                tokio::fs::remove_dir_all(path).await?;
                removed += 1;
            }
            Ok(_) => continue,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        sqlx::query("UPDATE personalization_jobs SET files_cleaned_at = ? WHERE id = ?")
            .bind(now)
            .bind(&id)
            .execute(pool)
            .await?;
    }
    // Successful inputs are redundant after the draft/file transaction commits.
    // Failed/queued jobs keep their original bytes for inspection and retry.
    let uploads: Vec<String> = sqlx::query_scalar("SELECT id FROM upload_jobs WHERE input_cleaned_at IS NULL AND status = 'ready' AND unixepoch(updated_at) < ? ORDER BY updated_at LIMIT 500")
        .bind(now - 7 * 86400).fetch_all(pool).await?;
    for id in uploads {
        if uuid::Uuid::parse_str(&id).is_err() {
            continue;
        }
        // Construct from the restricted UUID namespace; never follow DB paths.
        let path = storage.join("uploads").join(&id);
        match tokio::fs::remove_file(path).await {
            Ok(()) => removed += 1,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        sqlx::query("UPDATE upload_jobs SET input_cleaned_at = ? WHERE id = ?")
            .bind(now)
            .bind(&id)
            .execute(pool)
            .await?;
    }
    Ok(removed)
}
