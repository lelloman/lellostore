//! Device tokens: admin-issued, read-only credentials for provisioned devices.
//!
//! A device token authenticates as the ordinary, non-admin principal
//! `device-token:<id>`. It carries no authority of its own: what it can see
//! comes from the grants and group memberships an administrator gives that
//! principal. Only the SHA-256 of the secret is stored; the secret itself is
//! returned once.
use serde::Serialize;
use serde_json::json;
use sqlx::{SqliteConnection, SqlitePool};

use super::token_secrets::{generate_secret, hash_secret, normalize_expiry, validate_name};
use crate::error::AppError;

/// Every device token secret starts with this prefix, which is how the
/// authentication middleware tells it apart from an OIDC access token.
pub const SECRET_PREFIX: &str = "lellostore_dev_";
/// Prefix of the principal subject of a device token.
pub const SUBJECT_PREFIX: &str = "device-token:";

const TOKEN_COLUMNS: &str =
    "id, name, created_by, created_at, expires_at, last_used_at, revoked_at";

/// The principal subject a device token authenticates as.
pub fn subject(id: &str) -> String {
    format!("{SUBJECT_PREFIX}{id}")
}

#[derive(Debug, Clone, sqlx::FromRow)]
struct DeviceTokenRow {
    id: String,
    name: String,
    created_by: String,
    created_at: String,
    expires_at: Option<String>,
    last_used_at: Option<String>,
    revoked_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow, PartialEq, Eq)]
pub struct GroupRef {
    pub id: i64,
    pub name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct DeviceToken {
    pub id: String,
    pub name: String,
    pub created_by: String,
    pub created_at: String,
    pub expires_at: Option<String>,
    pub last_used_at: Option<String>,
    pub revoked_at: Option<String>,
    /// The principal this token authenticates as (`device-token:<id>`).
    pub subject: String,
    /// The app groups that principal is a member of.
    pub groups: Vec<GroupRef>,
}

#[derive(Debug, Serialize)]
pub struct CreatedDeviceToken {
    #[serde(flatten)]
    pub token: DeviceToken,
    /// The only time the secret is ever returned.
    pub secret: String,
}

/// Identity of a valid, unrevoked and unexpired token.
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct ResolvedToken {
    pub id: String,
}

async fn with_groups(pool: &SqlitePool, row: DeviceTokenRow) -> Result<DeviceToken, AppError> {
    let subject = subject(&row.id);
    let groups = sqlx::query_as(
        "SELECT g.id, g.name FROM app_groups g JOIN user_app_group_memberships m ON m.group_id = g.id WHERE m.user_subject = ? ORDER BY g.name, g.id",
    )
    .bind(&subject)
    .fetch_all(pool)
    .await?;
    Ok(DeviceToken {
        id: row.id,
        name: row.name,
        created_by: row.created_by,
        created_at: row.created_at,
        expires_at: row.expires_at,
        last_used_at: row.last_used_at,
        revoked_at: row.revoked_at,
        subject,
        groups,
    })
}

/// Create a token, register its principal as a known user and add it to
/// `group_ids`, all in one transaction: an unknown group fails the whole
/// request and no token is created.
pub async fn create(
    pool: &SqlitePool,
    actor: &str,
    name: &str,
    expires_at: Option<&str>,
    group_ids: &[i64],
) -> Result<CreatedDeviceToken, AppError> {
    let name = validate_name(name)?;
    let expires_at = normalize_expiry(expires_at)?;
    let id = uuid::Uuid::new_v4().to_string();
    let subject = subject(&id);
    let secret = generate_secret(SECRET_PREFIX)?;
    let mut tx = pool.begin().await?;
    insert(
        &mut tx,
        actor,
        &id,
        name,
        &hash_secret(&secret),
        &expires_at,
    )
    .await?;
    let mut seen = Vec::with_capacity(group_ids.len());
    for &group_id in group_ids {
        if seen.contains(&group_id) {
            continue;
        }
        seen.push(group_id);
        super::admin::apply_membership(&mut tx, actor, group_id, &subject, true).await?;
    }
    tx.commit().await?;
    Ok(CreatedDeviceToken {
        token: get(pool, &id).await?,
        secret,
    })
}

async fn insert(
    conn: &mut SqliteConnection,
    actor: &str,
    id: &str,
    name: &str,
    token_hash: &str,
    expires_at: &Option<String>,
) -> Result<(), AppError> {
    sqlx::query("INSERT INTO device_tokens(id, name, token_hash, created_by, expires_at) VALUES (?, ?, ?, ?, ?)")
        .bind(id)
        .bind(name)
        .bind(token_hash)
        .bind(actor)
        .bind(expires_at)
        .execute(&mut *conn)
        .await?;
    // Grants and group memberships require a known user.
    sqlx::query("INSERT OR IGNORE INTO known_users (subject, email) VALUES (?, NULL)")
        .bind(subject(id))
        .execute(&mut *conn)
        .await?;
    super::admin::audit(
        conn,
        actor,
        "device_token.create",
        "device_token",
        id,
        json!({"name": name, "expires_at": expires_at}),
    )
    .await
}

pub async fn get(pool: &SqlitePool, id: &str) -> Result<DeviceToken, AppError> {
    let row = sqlx::query_as(&format!(
        "SELECT {TOKEN_COLUMNS} FROM device_tokens WHERE id = ?"
    ))
    .bind(id)
    .fetch_optional(pool)
    .await?
    .ok_or_else(|| AppError::NotFound("Device token not found".into()))?;
    with_groups(pool, row).await
}

pub async fn list(pool: &SqlitePool) -> Result<Vec<DeviceToken>, AppError> {
    let rows: Vec<DeviceTokenRow> = sqlx::query_as(&format!(
        "SELECT {TOKEN_COLUMNS} FROM device_tokens ORDER BY created_at DESC, rowid DESC"
    ))
    .fetch_all(pool)
    .await?;
    let mut tokens = Vec::with_capacity(rows.len());
    for row in rows {
        tokens.push(with_groups(pool, row).await?);
    }
    Ok(tokens)
}

/// Revoke a token. Idempotent; the principal's known-user row, grants and
/// memberships are kept so a re-provisioned token can be granted the same way.
pub async fn revoke(pool: &SqlitePool, actor: &str, id: &str) -> Result<DeviceToken, AppError> {
    let mut tx = pool.begin().await?;
    let changed = sqlx::query(
        "UPDATE device_tokens SET revoked_at = datetime('now') WHERE id = ? AND revoked_at IS NULL",
    )
    .bind(id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if changed > 0 {
        super::admin::audit(
            &mut tx,
            actor,
            "device_token.revoke",
            "device_token",
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
    let resolved: Option<ResolvedToken> = sqlx::query_as(
        "UPDATE device_tokens SET last_used_at = datetime('now') WHERE token_hash = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > datetime('now')) RETURNING id",
    )
    .bind(hash_secret(secret))
    .fetch_optional(pool)
    .await?;
    if let Some(token) = &resolved {
        sqlx::query("UPDATE known_users SET last_seen_at = datetime('now') WHERE subject = ?")
            .bind(subject(&token.id))
            .execute(pool)
            .await?;
    }
    Ok(resolved)
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn pool() -> SqlitePool {
        let pool = sqlx::sqlite::SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        crate::db::run_migrations(&pool).await.unwrap();
        pool
    }

    async fn group(pool: &SqlitePool, name: &str) -> i64 {
        crate::db::admin::create_group(pool, "admin", name)
            .await
            .unwrap()
            .id
    }

    #[tokio::test]
    async fn create_authenticate_and_revoke() {
        let pool = pool().await;
        let tv = group(&pool, "TV").await;
        let created = create(&pool, "admin", " Living room TV ", None, &[tv, tv])
            .await
            .unwrap();
        assert!(created.secret.starts_with(SECRET_PREFIX));
        assert_eq!(created.secret.len(), SECRET_PREFIX.len() + 64);
        let token = &created.token;
        assert_eq!(token.name, "Living room TV");
        assert_eq!(token.subject, format!("device-token:{}", token.id));
        assert_eq!(
            token.groups,
            vec![GroupRef {
                id: tv,
                name: "TV".into()
            }]
        );
        let stored: String = sqlx::query_scalar("SELECT token_hash FROM device_tokens")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(stored, hash_secret(&created.secret));
        let users = crate::db::admin::list_users(&pool).await.unwrap();
        let user = users.iter().find(|u| u.subject == token.subject).unwrap();
        assert_eq!(user.device_token_name.as_deref(), Some("Living room TV"));

        let resolved = authenticate(&pool, &created.secret).await.unwrap().unwrap();
        assert_eq!(resolved.id, token.id);
        assert!(get(&pool, &token.id).await.unwrap().last_used_at.is_some());
        assert!(authenticate(&pool, "lellostore_dev_nope")
            .await
            .unwrap()
            .is_none());

        let revoked = revoke(&pool, "admin", &token.id).await.unwrap();
        let first_revocation = revoked.revoked_at.clone().unwrap();
        assert_eq!(
            revoke(&pool, "admin", &token.id).await.unwrap().revoked_at,
            Some(first_revocation)
        );
        assert!(authenticate(&pool, &created.secret)
            .await
            .unwrap()
            .is_none());
        // The principal and its memberships outlive the revocation.
        assert_eq!(revoked.groups.len(), 1);
        assert!(matches!(
            revoke(&pool, "admin", "missing").await,
            Err(AppError::NotFound(_))
        ));
        let audits: Vec<String> = sqlx::query_scalar(
            "SELECT action FROM access_audit_log WHERE action LIKE 'device_token.%' OR action = 'membership.set' ORDER BY id",
        )
        .fetch_all(&pool)
        .await
        .unwrap();
        assert_eq!(
            audits,
            [
                "device_token.create",
                "membership.set",
                "device_token.revoke"
            ]
        );
    }

    #[tokio::test]
    async fn unknown_group_or_bad_input_creates_nothing() {
        let pool = pool().await;
        let tv = group(&pool, "TV").await;
        assert!(matches!(
            create(&pool, "admin", "TV", None, &[tv, 9999]).await,
            Err(AppError::NotFound(_))
        ));
        assert!(matches!(
            create(&pool, "admin", " ", None, &[]).await,
            Err(AppError::BadRequest(_))
        ));
        assert!(matches!(
            create(&pool, "admin", "TV", Some("2000-01-01T00:00:00Z"), &[]).await,
            Err(AppError::BadRequest(_))
        ));
        let tokens: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_tokens")
            .fetch_one(&pool)
            .await
            .unwrap();
        let principals: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM known_users WHERE subject LIKE 'device-token:%'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        let memberships: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM user_app_group_memberships")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!((tokens, principals, memberships), (0, 0, 0));
        assert!(list(&pool).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn expired_tokens_do_not_authenticate() {
        let pool = pool().await;
        let secret = format!("{SECRET_PREFIX}expired");
        sqlx::query("INSERT INTO device_tokens(id,name,token_hash,created_by,expires_at) VALUES ('old','Old',?,'admin',datetime('now','-1 minute'))")
            .bind(hash_secret(&secret))
            .execute(&pool)
            .await
            .unwrap();
        assert!(authenticate(&pool, &secret).await.unwrap().is_none());
    }
}
