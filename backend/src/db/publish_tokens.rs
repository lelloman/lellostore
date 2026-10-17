//! Per-app publish tokens for unattended release pipelines.
//!
//! A token authorizes uploading and publishing exactly one package. Only the
//! SHA-256 of the secret is stored; the secret itself is returned once.
use ring::rand::{SecureRandom, SystemRandom};
use serde::Serialize;
use serde_json::json;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;

use crate::error::AppError;

/// Every publish token secret starts with this prefix, which is how the
/// authentication middleware tells it apart from an OIDC access token.
pub const SECRET_PREFIX: &str = "lellostore_pub_";
const SECRET_BYTES: usize = 32;
const RECENT_UPLOADS: i64 = 10;

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct PublishToken {
    pub id: String,
    pub name: String,
    pub package_name: String,
    pub created_by: String,
    pub created_at: String,
    pub expires_at: Option<String>,
    pub last_used_at: Option<String>,
    pub revoked_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct TokenUpload {
    pub id: String,
    pub kind: String,
    pub package_name: Option<String>,
    pub status: String,
    pub error: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Serialize)]
pub struct PublishTokenSummary {
    #[serde(flatten)]
    pub token: PublishToken,
    pub recent_uploads: Vec<TokenUpload>,
}

#[derive(Debug, Serialize)]
pub struct CreatedPublishToken {
    #[serde(flatten)]
    pub token: PublishToken,
    /// The only time the secret is ever returned.
    pub secret: String,
}

/// Resolved authority of a valid, unrevoked and unexpired token.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct ResolvedToken {
    pub id: String,
    pub package_name: String,
}

pub fn hash_secret(secret: &str) -> String {
    hex::encode(Sha256::digest(secret.as_bytes()))
}

fn generate_secret() -> Result<String, AppError> {
    let mut bytes = [0_u8; SECRET_BYTES];
    SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| AppError::Internal("Secure random generator unavailable".into()))?;
    Ok(format!("{SECRET_PREFIX}{}", hex::encode(bytes)))
}

fn validate_package(package: &str) -> Result<(), AppError> {
    let valid = !package.is_empty()
        && package.len() <= 255
        && package.contains('.')
        && package.split('.').all(|part| {
            part.chars().next().is_some_and(|c| c.is_ascii_alphabetic())
                && part.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        });
    if valid {
        Ok(())
    } else {
        Err(AppError::BadRequest(
            "package_name must be an Android application ID".into(),
        ))
    }
}

/// Normalize an RFC 3339 expiry into SQLite's UTC `datetime()` format so it
/// compares correctly with `datetime('now')`.
fn normalize_expiry(expires_at: Option<&str>) -> Result<Option<String>, AppError> {
    let Some(raw) = expires_at.map(str::trim).filter(|v| !v.is_empty()) else {
        return Ok(None);
    };
    let parsed = chrono::DateTime::parse_from_rfc3339(raw)
        .map_err(|_| AppError::BadRequest("expires_at must be an RFC 3339 timestamp".into()))?
        .with_timezone(&chrono::Utc);
    if parsed <= chrono::Utc::now() {
        return Err(AppError::BadRequest(
            "expires_at must be in the future".into(),
        ));
    }
    Ok(Some(parsed.format("%Y-%m-%d %H:%M:%S").to_string()))
}

pub async fn create(
    pool: &SqlitePool,
    actor: &str,
    name: &str,
    package: &str,
    expires_at: Option<&str>,
) -> Result<CreatedPublishToken, AppError> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest(
            "name must contain 1 to 100 characters".into(),
        ));
    }
    let package = package.trim();
    validate_package(package)?;
    let expires_at = normalize_expiry(expires_at)?;
    let id = uuid::Uuid::new_v4().to_string();
    let secret = generate_secret()?;
    let mut tx = pool.begin().await?;
    sqlx::query("INSERT INTO publish_tokens(id, name, package_name, token_hash, created_by, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
        .bind(&id)
        .bind(name)
        .bind(package)
        .bind(hash_secret(&secret))
        .bind(actor)
        .bind(&expires_at)
        .execute(&mut *tx)
        .await?;
    super::admin::audit(
        &mut tx,
        actor,
        "publish_token.create",
        "publish_token",
        &id,
        json!({"name": name, "package_name": package, "expires_at": expires_at}),
    )
    .await?;
    tx.commit().await?;
    Ok(CreatedPublishToken {
        token: get(pool, &id).await?,
        secret,
    })
}

pub async fn get(pool: &SqlitePool, id: &str) -> Result<PublishToken, AppError> {
    sqlx::query_as("SELECT id, name, package_name, created_by, created_at, expires_at, last_used_at, revoked_at FROM publish_tokens WHERE id = ?")
        .bind(id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| AppError::NotFound("Publish token not found".into()))
}

pub async fn list(pool: &SqlitePool) -> Result<Vec<PublishTokenSummary>, AppError> {
    let tokens: Vec<PublishToken> = sqlx::query_as("SELECT id, name, package_name, created_by, created_at, expires_at, last_used_at, revoked_at FROM publish_tokens ORDER BY created_at DESC, rowid DESC")
        .fetch_all(pool)
        .await?;
    let mut summaries = Vec::with_capacity(tokens.len());
    for token in tokens {
        let recent_uploads = sqlx::query_as(
            "SELECT id, kind, COALESCE(package_name, json_extract(result_json, '$.package_name')) AS package_name, status, error, created_at FROM upload_jobs WHERE publish_token_id = ? ORDER BY created_at DESC, rowid DESC LIMIT ?",
        )
        .bind(&token.id)
        .bind(RECENT_UPLOADS)
        .fetch_all(pool)
        .await?;
        summaries.push(PublishTokenSummary {
            token,
            recent_uploads,
        });
    }
    Ok(summaries)
}

pub async fn revoke(pool: &SqlitePool, actor: &str, id: &str) -> Result<PublishToken, AppError> {
    let mut tx = pool.begin().await?;
    let changed = sqlx::query(
        "UPDATE publish_tokens SET revoked_at = datetime('now') WHERE id = ? AND revoked_at IS NULL",
    )
    .bind(id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if changed > 0 {
        super::admin::audit(
            &mut tx,
            actor,
            "publish_token.revoke",
            "publish_token",
            id,
            json!({}),
        )
        .await?;
    }
    tx.commit().await?;
    // Revoking an already revoked token is idempotent; an unknown id is 404.
    get(pool, id).await
}

/// Resolve a presented secret. Returns `None` for unknown, revoked or expired
/// tokens, and records the use of a valid one.
pub async fn authenticate(
    pool: &SqlitePool,
    secret: &str,
) -> Result<Option<ResolvedToken>, AppError> {
    Ok(sqlx::query_as(
        "UPDATE publish_tokens SET last_used_at = datetime('now') WHERE token_hash = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > datetime('now')) RETURNING id, package_name",
    )
    .bind(hash_secret(secret))
    .fetch_optional(pool)
    .await?)
}

/// The package a token may upload, if the token is still usable. The upload
/// worker re-checks this so a token revoked while its job is queued cannot
/// complete the upload.
pub async fn active_package(pool: &SqlitePool, id: &str) -> Result<Option<String>, AppError> {
    Ok(sqlx::query_scalar(
        "SELECT package_name FROM publish_tokens WHERE id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > datetime('now'))",
    )
    .bind(id)
    .fetch_optional(pool)
    .await?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secrets_are_prefixed_random_and_hashed() {
        let first = generate_secret().unwrap();
        let second = generate_secret().unwrap();
        assert!(first.starts_with(SECRET_PREFIX));
        assert_eq!(first.len(), SECRET_PREFIX.len() + 64);
        assert_ne!(first, second);
        assert_eq!(hash_secret(&first).len(), 64);
        assert_ne!(hash_secret(&first), first);
    }

    #[test]
    fn package_and_expiry_validation() {
        assert!(validate_package("com.lelloman.pezzottify").is_ok());
        for invalid in ["", "nodot", "com..x", "com.1x", "com.x-y", "../x.y"] {
            assert!(validate_package(invalid).is_err(), "{invalid}");
        }
        assert_eq!(normalize_expiry(None).unwrap(), None);
        assert_eq!(normalize_expiry(Some(" ")).unwrap(), None);
        assert!(normalize_expiry(Some("2000-01-01T00:00:00Z")).is_err());
        assert!(normalize_expiry(Some("tomorrow")).is_err());
        assert_eq!(
            normalize_expiry(Some("2999-01-01T02:00:00+02:00")).unwrap(),
            Some("2999-01-01 00:00:00".into())
        );
    }
}
