//! Retryable cleanup of replaced artifacts and expendable transfer copies.
use crate::error::AppError;
use sqlx::SqlitePool;
use std::path::Path;

pub async fn cleanup(pool: &SqlitePool, storage: &Path, now: i64) -> Result<u64, AppError> {
    let mut removed = cleanup_replaced(pool, storage).await?;
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
    removed += cleanup_deltas(pool, storage, now).await?;
    Ok(removed)
}

/// Patches are optional derived artifacts. Superseded targets retire their
/// patches; files are removed only after a grace period for outstanding heads
/// and transfers. Full archives are never removed because a patch exists.
async fn cleanup_deltas(pool: &SqlitePool, storage: &Path, now: i64) -> Result<u64, AppError> {
    crate::db::dvpk::retire_superseded(pool, now).await?;
    crate::db::dvpk::skip_orphaned(pool, now).await?;
    let mut removed = 0;
    for delta in crate::db::dvpk::expired_retired(pool, now).await? {
        let Some(path) = delta.patch_path.as_deref() else {
            continue;
        };
        let relative = Path::new(path);
        if !relative.starts_with("dvpks")
            || !relative
                .components()
                .all(|part| matches!(part, std::path::Component::Normal(_)))
        {
            return Err(AppError::Internal("Invalid retired delta path".into()));
        }
        let mut tx = pool.begin().await?;
        // Mark first, then unlink only bytes no servable relationship shares.
        sqlx::query("UPDATE vpk_deltas SET file_removed = 1, updated_at = ? WHERE id = ? AND file_removed = 0")
            .bind(now).bind(&delta.id).execute(&mut *tx).await?;
        let shared: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM vpk_deltas WHERE patch_path = ? AND file_removed = 0 AND state IN ('ready','retired'))")
            .bind(path).fetch_one(&mut *tx).await?;
        tx.commit().await?;
        if !shared {
            match tokio::fs::remove_file(storage.join(relative)).await {
                Ok(()) => removed += 1,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
        }
    }
    // Reconcile patch files an interrupted publication left unreferenced, and
    // staging copies, after the same grace as other transfer leftovers.
    removed += cleanup_unreferenced_patches(pool, storage, now).await?;
    // Worker directories are removed after each job; leftovers of a crash are
    // removed once no job with that ID is running.
    let work = storage.join(crate::services::dvpk::WORK_NAMESPACE);
    let mut entries = match tokio::fs::read_dir(&work).await {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(removed),
        Err(error) => return Err(error.into()),
    };
    while let Some(entry) = entries.next_entry().await? {
        let Some(id) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let info = tokio::fs::symlink_metadata(entry.path()).await?;
        if uuid::Uuid::parse_str(&id).is_err() || !info.is_dir() {
            continue;
        }
        let running: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM vpk_deltas WHERE id = ? AND state = 'running')",
        )
        .bind(&id)
        .fetch_one(pool)
        .await?;
        if !running {
            tokio::fs::remove_dir_all(entry.path()).await?;
            removed += 1;
        }
    }
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

/// Tombstones commit before bytes are deleted. A failed unlink is retried after restart.
/// Keep metadata for shell contracts, issued grants and version identity reservations.
pub async fn cleanup_replaced(pool: &SqlitePool, storage: &Path) -> Result<u64, AppError> {
    let mut removed = 0;
    for (table, column, namespace) in [
        ("app_versions", "apk_path", "apks"),
        ("vpk_releases", "archive_path", "vpks"),
    ] {
        let mut tx = pool.begin().await?;
        // Serialize reference checks and deletion with publication/archive changes.
        sqlx::query(&format!(
            "UPDATE {table} SET artifact_cleaned = artifact_cleaned WHERE 0"
        ))
        .execute(&mut *tx)
        .await?;
        let paths: Vec<String> = sqlx::query_scalar(&format!("SELECT DISTINCT {column} FROM {table} WHERE artifact_removed = 1 AND artifact_cleaned = 0 LIMIT 500"))
            .fetch_all(&mut *tx).await?;
        for path in paths {
            let relative = Path::new(&path);
            if !relative.starts_with(namespace)
                || !relative
                    .components()
                    .all(|part| matches!(part, std::path::Component::Normal(_)))
            {
                return Err(AppError::Internal("Invalid replaced artifact path".into()));
            }
            let referenced: bool = sqlx::query_scalar(&format!(
                "SELECT EXISTS(SELECT 1 FROM {table} WHERE {column} = ? AND artifact_removed = 0)"
            ))
            .bind(&path)
            .fetch_one(&mut *tx)
            .await?;
            // Pending delta generation keeps its verified inputs; retry later.
            if table == "vpk_releases" {
                let pending: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM vpk_deltas d JOIN vpk_releases r ON r.id IN (d.base_vpk_id, d.target_vpk_id) WHERE r.archive_path = ? AND d.state IN ('queued','running'))")
                    .bind(&path).fetch_one(&mut *tx).await?;
                if pending {
                    continue;
                }
            }
            if !referenced {
                match tokio::fs::remove_file(storage.join(&path)).await {
                    Ok(()) => removed += 1,
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                    Err(error) => return Err(error.into()),
                }
            }
            sqlx::query(&format!("UPDATE {table} SET artifact_cleaned = 1 WHERE {column} = ? AND artifact_removed = 1"))
                .bind(&path).execute(&mut *tx).await?;
        }
        tx.commit().await?;
    }
    Ok(removed)
}

async fn cleanup_unreferenced_patches(
    pool: &SqlitePool,
    storage: &Path,
    now: i64,
) -> Result<u64, AppError> {
    let mut entries = match tokio::fs::read_dir(storage.join("dvpks")).await {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(0),
        Err(error) => return Err(error.into()),
    };
    let mut removed = 0;
    while let Some(entry) = entries.next_entry().await? {
        if removed >= 500 {
            break;
        }
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let canonical = name.strip_suffix(".dvpk").is_some_and(|hash| {
            hash.len() == 64
                && hash
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        });
        if !canonical && !name.starts_with(".tmp") {
            continue;
        }
        let info = tokio::fs::symlink_metadata(entry.path()).await?;
        let modified = info
            .modified()?
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|_| AppError::Internal("Invalid patch file timestamp".into()))?
            .as_secs();
        if !info.is_file() || modified > now.saturating_sub(7 * 86400).max(0) as u64 {
            continue;
        }
        if canonical {
            let referenced: bool = sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM vpk_deltas WHERE patch_path = ? AND file_removed = 0)",
            )
            .bind(format!("dvpks/{name}"))
            .fetch_one(pool)
            .await?;
            if referenced {
                continue;
            }
        }
        tokio::fs::remove_file(entry.path()).await?;
        removed += 1;
    }
    Ok(removed)
}
