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
    let secret = generate_secret(SECRET_PREFIX)?;
    let mut tx = pool.begin().await?;
    let id = create_in(
        &mut tx,
        actor,
        name,
        &expires_at,
        group_ids,
        &hash_secret(&secret),
    )
    .await?;
    tx.commit().await?;
    Ok(CreatedDeviceToken {
        token: get(pool, &id).await?,
        secret,
    })
}

/// Insert a token with an already validated name and expiry and the given
/// secret hash inside the caller's transaction, register its principal and
/// join `group_ids` (duplicates ignored). Returns the new token id.
pub(crate) async fn create_in(
    conn: &mut SqliteConnection,
    actor: &str,
    name: &str,
    expires_at: &Option<String>,
    group_ids: &[i64],
    token_hash: &str,
) -> Result<String, AppError> {
    let id = uuid::Uuid::new_v4().to_string();
    let subject = subject(&id);
    insert(conn, actor, &id, name, token_hash, expires_at).await?;
    let mut seen = Vec::with_capacity(group_ids.len());
    for &group_id in group_ids {
        if seen.contains(&group_id) {
            continue;
        }
        seen.push(group_id);
        super::admin::apply_membership(conn, actor, group_id, &subject, true).await?;
    }
    Ok(id)
}

/// A random hash whose preimage nobody knows: a token holding it cannot
/// authenticate until a rekey or an enrollment's redemption gives it a real one.
pub(crate) fn placeholder_hash() -> Result<String, AppError> {
    Ok(hash_secret(&generate_secret("placeholder_")?))
}

/// Rekey a token inside the caller's transaction: give it `token_hash`,
/// clear `revoked_at` and optionally rename it. The id, principal, grants and
/// group memberships stay the same, so the previous secret stops working and
/// the device holding the new one keeps everything the old one had. An
/// expired token cannot be rekeyed.
pub(crate) async fn rekey_in(
    conn: &mut SqliteConnection,
    actor: &str,
    id: &str,
    name: Option<&str>,
    token_hash: &str,
    details: serde_json::Value,
) -> Result<(), AppError> {
    let expired: Option<bool> = sqlx::query_scalar(
        "SELECT expires_at IS NOT NULL AND expires_at <= datetime('now') FROM device_tokens WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&mut *conn)
    .await?;
    match expired {
        None => return Err(AppError::NotFound("Device token not found".into())),
        Some(true) => {
            return Err(AppError::BadRequest(
                "An expired device token cannot be rekeyed".into(),
            ))
        }
        Some(false) => {}
    }
    sqlx::query(
        "UPDATE device_tokens SET token_hash = ?, revoked_at = NULL, name = COALESCE(?, name) WHERE id = ?",
    )
    .bind(token_hash)
    .bind(name)
    .bind(id)
    .execute(&mut *conn)
    .await?;
    let mut details = details;
    if let (Some(name), Some(map)) = (name, details.as_object_mut()) {
        map.insert("name".into(), json!(name));
    }
    super::admin::audit(
        conn,
        actor,
        "device_token.rekey",
        "device_token",
        id,
        details,
    )
    .await
}

/// Give a token a new secret (see [`rekey_in`]) and return it once.
pub async fn rekey(
    pool: &SqlitePool,
    actor: &str,
    id: &str,
) -> Result<CreatedDeviceToken, AppError> {
    let secret = generate_secret(SECRET_PREFIX)?;
    let mut tx = pool.begin().await?;
    rekey_in(&mut tx, actor, id, None, &hash_secret(&secret), json!({})).await?;
    tx.commit().await?;
    Ok(CreatedDeviceToken {
        token: get(pool, id).await?,
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

/// Permanently delete a revoked token. An active token is refused with
/// `409`: revoke it first. Its principal goes with it: the known-user row,
/// direct grants and group memberships are deleted, and enrollments that
/// referenced it forget it. Acquisition and audit history are kept.
pub async fn purge(pool: &SqlitePool, actor: &str, id: &str) -> Result<(), AppError> {
    let mut tx = pool.begin().await?;
    let revoked: Option<bool> =
        sqlx::query_scalar("SELECT revoked_at IS NOT NULL FROM device_tokens WHERE id = ?")
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;
    match revoked {
        None => return Err(AppError::NotFound("Device token not found".into())),
        Some(false) => {
            return Err(AppError::Conflict(
                "Only a revoked device token can be deleted; revoke it first".into(),
            ))
        }
        Some(true) => purge_in(&mut tx, actor, id).await?,
    }
    tx.commit().await?;
    Ok(())
}

/// Delete every revoked token as [`purge`] does and return how many.
pub async fn purge_revoked(pool: &SqlitePool, actor: &str) -> Result<u64, AppError> {
    let mut tx = pool.begin().await?;
    let ids: Vec<String> =
        sqlx::query_scalar("SELECT id FROM device_tokens WHERE revoked_at IS NOT NULL")
            .fetch_all(&mut *tx)
            .await?;
    for id in &ids {
        purge_in(&mut tx, actor, id).await?;
    }
    tx.commit().await?;
    Ok(ids.len() as u64)
}

async fn purge_in(conn: &mut SqliteConnection, actor: &str, id: &str) -> Result<(), AppError> {
    let subject = subject(id);
    let name: String = sqlx::query_scalar("DELETE FROM device_tokens WHERE id = ? RETURNING name")
        .bind(id)
        .fetch_one(&mut *conn)
        .await?;
    for statement in [
        "DELETE FROM user_app_grants WHERE user_subject = ?",
        "DELETE FROM user_app_group_memberships WHERE user_subject = ?",
        "DELETE FROM known_users WHERE subject = ?",
    ] {
        sqlx::query(statement)
            .bind(&subject)
            .execute(&mut *conn)
            .await?;
    }
    sqlx::query(
        "UPDATE device_enrollments SET device_token_id = NULL, state = CASE WHEN state = 'approved' THEN 'expired' ELSE state END WHERE device_token_id = ?",
    )
    .bind(id)
    .execute(&mut *conn)
    .await?;
    super::admin::audit(
        conn,
        actor,
        "device_token.delete",
        "device_token",
        id,
        json!({"name": name}),
    )
    .await
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

    #[tokio::test]
    async fn rekey_issues_a_new_secret_for_the_same_principal() {
        let pool = pool().await;
        let tv = group(&pool, "TV").await;
        let created = create(&pool, "admin", "TV", None, &[tv]).await.unwrap();
        revoke(&pool, "admin", &created.token.id).await.unwrap();
        let rekeyed = rekey(&pool, "admin", &created.token.id).await.unwrap();
        assert_ne!(rekeyed.secret, created.secret);
        assert_eq!(rekeyed.token.id, created.token.id);
        assert!(rekeyed.token.revoked_at.is_none());
        assert_eq!(rekeyed.token.groups, created.token.groups);
        assert!(authenticate(&pool, &created.secret)
            .await
            .unwrap()
            .is_none());
        assert!(authenticate(&pool, &rekeyed.secret)
            .await
            .unwrap()
            .is_some());
        assert!(matches!(
            rekey(&pool, "admin", "missing").await,
            Err(AppError::NotFound(_))
        ));
        sqlx::query("UPDATE device_tokens SET expires_at = datetime('now', '-1 minute')")
            .execute(&pool)
            .await
            .unwrap();
        assert!(matches!(
            rekey(&pool, "admin", &created.token.id).await,
            Err(AppError::BadRequest(_))
        ));
    }

    async fn principal_rows(pool: &SqlitePool, subject: &str) -> (i64, i64, i64) {
        let count = |sql: &'static str| async move {
            sqlx::query_scalar::<_, i64>(sql)
                .bind(subject)
                .fetch_one(pool)
                .await
                .unwrap()
        };
        (
            count("SELECT COUNT(*) FROM known_users WHERE subject = ?").await,
            count("SELECT COUNT(*) FROM user_app_grants WHERE user_subject = ?").await,
            count("SELECT COUNT(*) FROM user_app_group_memberships WHERE user_subject = ?").await,
        )
    }

    #[tokio::test]
    async fn only_revoked_tokens_are_deleted_with_their_principal() {
        let pool = pool().await;
        let tv = group(&pool, "TV").await;
        sqlx::query("INSERT INTO apps (package_name, name) VALUES ('com.example.tv', 'TV')")
            .execute(&pool)
            .await
            .unwrap();
        let created = create(&pool, "admin", "TV", None, &[tv]).await.unwrap();
        let subject = created.token.subject.clone();
        crate::db::admin::set_direct_grant(
            &pool,
            "admin",
            &subject,
            "com.example.tv",
            crate::db::access::AppAccessLevel::Stable,
        )
        .await
        .unwrap();
        assert_eq!(principal_rows(&pool, &subject).await, (1, 1, 1));
        assert!(matches!(
            purge(&pool, "admin", &created.token.id).await,
            Err(AppError::Conflict(_))
        ));
        assert!(get(&pool, &created.token.id).await.is_ok());
        revoke(&pool, "admin", &created.token.id).await.unwrap();
        purge(&pool, "admin", &created.token.id).await.unwrap();
        assert!(matches!(
            get(&pool, &created.token.id).await,
            Err(AppError::NotFound(_))
        ));
        assert_eq!(principal_rows(&pool, &subject).await, (0, 0, 0));
        assert!(matches!(
            purge(&pool, "admin", &created.token.id).await,
            Err(AppError::NotFound(_))
        ));
        let details: String = sqlx::query_scalar(
            "SELECT details_json FROM access_audit_log WHERE action = 'device_token.delete'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(details, r#"{"name":"TV"}"#);
    }

    #[tokio::test]
    async fn purge_revoked_keeps_active_tokens() {
        let pool = pool().await;
        let active = create(&pool, "admin", "Active", None, &[]).await.unwrap();
        for name in ["Xiaomi TV Box", "Xiaomi TV Box", "Xiaomi TV Box"] {
            let token = create(&pool, "admin", name, None, &[]).await.unwrap();
            revoke(&pool, "admin", &token.token.id).await.unwrap();
        }
        assert_eq!(purge_revoked(&pool, "admin").await.unwrap(), 3);
        assert_eq!(purge_revoked(&pool, "admin").await.unwrap(), 0);
        let remaining = list(&pool).await.unwrap();
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].id, active.token.id);
        assert!(authenticate(&pool, &active.secret).await.unwrap().is_some());
        let deletes: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM access_audit_log WHERE action = 'device_token.delete'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(deletes, 3);
    }
}
