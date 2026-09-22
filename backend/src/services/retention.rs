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
    removed += cleanup_orphans(pool, storage, now).await?;
    Ok(removed)
}

/// Seven-day grace separates crash leftovers from work being committed now.
/// Only generated names in transfer namespaces are eligible; published artifacts
/// and failed-upload inputs with a durable job are never treated as orphans.
async fn cleanup_orphans(pool: &SqlitePool, storage: &Path, now: i64) -> Result<u64, AppError> {
    let mut removed = 0;
    for namespace in ["uploads", "acquisitions"] {
        let mut entries = match tokio::fs::read_dir(storage.join(namespace)).await {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error.into()),
        };
        while let Some(entry) = entries.next_entry().await? {
            if removed >= 500 {
                return Ok(removed);
            }
            let Some(id) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            if uuid::Uuid::parse_str(&id).is_err() {
                continue;
            }
            let info = tokio::fs::symlink_metadata(entry.path()).await?;
            if info.file_type().is_symlink() {
                continue;
            }
            let modified = info
                .modified()?
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|_| AppError::Internal("Invalid transfer file timestamp".into()))?
                .as_secs();
            if modified > now.saturating_sub(7 * 86400).max(0) as u64 {
                continue;
            }
            let query = if namespace == "uploads" {
                "SELECT EXISTS(SELECT 1 FROM upload_jobs WHERE id = ?)"
            } else {
                "SELECT EXISTS(SELECT 1 FROM personalization_jobs WHERE id = ?) OR EXISTS(SELECT 1 FROM acquisitions WHERE id = ?)"
            };
            let mut query = sqlx::query_scalar::<_, bool>(query).bind(&id);
            if namespace == "acquisitions" {
                query = query.bind(&id);
            }
            if query.fetch_one(pool).await? {
                continue;
            }
            if namespace == "acquisitions" && info.is_dir() {
                tokio::fs::remove_dir_all(entry.path()).await?;
            } else if namespace == "uploads" && info.is_file() {
                tokio::fs::remove_file(entry.path()).await?;
            } else {
                continue;
            }
            removed += 1;
        }
    }
    Ok(removed)
}
