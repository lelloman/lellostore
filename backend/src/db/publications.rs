//! Publication changes are serialized by the application's optimistic revision.
//! Artifact identities and publication events survive withdrawal and deletion.
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use super::models::AppVersion;
use crate::error::AppError;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PublishRequest {
    pub version_code: i64,
    pub expected_revision: i64,
    #[serde(default)]
    pub replace_latest: bool,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RevisionRequest {
    pub expected_revision: i64,
}

#[derive(Debug, Serialize)]
pub struct PublicationResult {
    pub package_name: String,
    pub version_code: i64,
    pub publication_revision: i64,
    pub publication_state: &'static str,
}

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct PublicationEvent {
    pub id: i64,
    pub version_code: i64,
    pub revision: i64,
    pub actor_subject: String,
    pub action: String,
    pub created_at: String,
}

pub async fn history(pool: &SqlitePool, package: &str) -> Result<Vec<PublicationEvent>, AppError> {
    Ok(sqlx::query_as("SELECT id, version_code, revision, actor_subject, action, created_at FROM publication_events WHERE package_name = ? ORDER BY revision DESC LIMIT 200")
        .bind(package).fetch_all(pool).await?)
}

pub async fn publish(
    pool: &SqlitePool,
    package: &str,
    actor: &str,
    request: &PublishRequest,
) -> Result<PublicationResult, AppError> {
    let mut tx = pool.begin().await?;
    // First statement acquires the write lock. No read-then-write race between publishers.
    let updated = sqlx::query("UPDATE apps SET publication_revision = publication_revision + 1 WHERE package_name = ? AND publication_revision = ?")
        .bind(package).bind(request.expected_revision).execute(&mut *tx).await?;
    if updated.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Publication changed. Refresh the review before publishing.".into(),
        ));
    }
    let version = sqlx::query_as::<_, AppVersion>(
        "SELECT * FROM app_versions WHERE package_name = ? AND version_code = ?",
    )
    .bind(package)
    .bind(request.version_code)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| AppError::NotFound("Draft release not found".into()))?;
    if version.publication_state != "draft" {
        return Err(AppError::Conflict(
            "Only a draft release can be published".into(),
        ));
    }
    // Paravoid publication is enabled only once the complete verifier and acquisition path exist.
    if version.distribution_mode != "normal" {
        return Err(AppError::BadRequest(
            "Paravoid publication requires the complete protocol and acquisition implementation"
                .into(),
        ));
    }
    let highest: Option<i64> = sqlx::query_scalar(
        "SELECT MAX(version_code) FROM published_apk_identities WHERE package_name = ?",
    )
    .bind(package)
    .fetch_one(&mut *tx)
    .await?;
    if highest.is_some_and(|code| request.version_code <= code) {
        return Err(AppError::Conflict("APK version code must exceed every previously published version, including beta and withdrawn releases".into()));
    }
    if request.replace_latest {
        let previous: Option<i64> = sqlx::query_scalar("SELECT MAX(version_code) FROM app_versions WHERE package_name = ? AND is_beta = ? AND publication_state = 'published'")
            .bind(package).bind(version.is_beta).fetch_one(&mut *tx).await?;
        if let Some(code) = previous {
            sqlx::query("UPDATE app_versions SET publication_state = 'withdrawn' WHERE package_name = ? AND version_code = ?")
                .bind(package).bind(code).execute(&mut *tx).await?;
        }
    }
    sqlx::query(
        "INSERT INTO published_apk_identities(package_name, version_code, sha256) VALUES (?, ?, ?)",
    )
    .bind(package)
    .bind(version.version_code)
    .bind(&version.sha256)
    .execute(&mut *tx)
    .await?;
    sqlx::query("UPDATE app_versions SET publication_state = 'published', published_at = datetime('now') WHERE package_name = ? AND version_code = ?")
        .bind(package).bind(request.version_code).execute(&mut *tx).await?;
    sqlx::query("UPDATE apps SET name = COALESCE(?, name), description = COALESCE(?, description), updated_at = datetime('now') WHERE package_name = ?")
        .bind(&version.proposed_name).bind(&version.proposed_description).bind(package).execute(&mut *tx).await?;
    let revision = request.expected_revision + 1;
    sqlx::query("INSERT INTO publication_events(package_name, version_code, revision, actor_subject, action) VALUES (?, ?, ?, ?, 'publish')")
        .bind(package).bind(request.version_code).bind(revision).bind(actor).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(PublicationResult {
        package_name: package.into(),
        version_code: request.version_code,
        publication_revision: revision,
        publication_state: "published",
    })
}

pub async fn withdraw(
    pool: &SqlitePool,
    package: &str,
    code: i64,
    actor: &str,
    expected_revision: i64,
) -> Result<PublicationResult, AppError> {
    let mut tx = pool.begin().await?;
    let updated = sqlx::query("UPDATE apps SET publication_revision = publication_revision + 1, updated_at = datetime('now') WHERE package_name = ? AND publication_revision = ?")
        .bind(package).bind(expected_revision).execute(&mut *tx).await?;
    if updated.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Publication changed. Refresh before withdrawing.".into(),
        ));
    }
    let changed = sqlx::query("UPDATE app_versions SET publication_state = 'withdrawn' WHERE package_name = ? AND version_code = ? AND publication_state = 'published'")
        .bind(package).bind(code).execute(&mut *tx).await?;
    if changed.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Only a published release can be withdrawn".into(),
        ));
    }
    let revision = expected_revision + 1;
    sqlx::query("INSERT INTO publication_events(package_name, version_code, revision, actor_subject, action) VALUES (?, ?, ?, ?, 'withdraw')")
        .bind(package).bind(code).bind(revision).bind(actor).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(PublicationResult {
        package_name: package.into(),
        version_code: code,
        publication_revision: revision,
        publication_state: "withdrawn",
    })
}
