use serde::Serialize;
use serde_json::{json, Value};
use sqlx::{SqliteConnection, SqlitePool};

use crate::auth::User;
use crate::error::AppError;

use super::access::{get_effective_app_access, AppAccessLevel, AppGroup, EffectiveAppAccess};

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
pub struct KnownUser {
    pub subject: String,
    pub email: Option<String>,
    pub first_seen_at: String,
    pub last_seen_at: String,
}

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
pub struct GrantRecord {
    pub package_name: String,
    pub access_level: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct GroupDetail {
    #[serde(flatten)]
    pub group: AppGroup,
    pub grants: Vec<GrantRecord>,
    pub user_subjects: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct UserAccessDetail {
    pub user: KnownUser,
    pub direct_grants: Vec<GrantRecord>,
    pub groups: Vec<AppGroup>,
    pub effective_access: Vec<EffectiveAppAccess>,
}

pub async fn observe_user(pool: &SqlitePool, user: &User) -> Result<(), AppError> {
    sqlx::query(
        r#"
        INSERT INTO known_users (subject, email) VALUES (?, ?)
        ON CONFLICT(subject) DO UPDATE SET
            email = COALESCE(excluded.email, known_users.email),
            last_seen_at = datetime('now')
        "#,
    )
    .bind(&user.subject)
    .bind(&user.email)
    .execute(pool)
    .await
    .map_err(AppError::Database)?;
    Ok(())
}

pub async fn list_users(pool: &SqlitePool) -> Result<Vec<KnownUser>, AppError> {
    sqlx::query_as("SELECT * FROM known_users ORDER BY COALESCE(email, subject), subject")
        .fetch_all(pool)
        .await
        .map_err(AppError::Database)
}

pub async fn get_user_access(
    pool: &SqlitePool,
    subject: &str,
) -> Result<UserAccessDetail, AppError> {
    let user = sqlx::query_as::<_, KnownUser>("SELECT * FROM known_users WHERE subject = ?")
        .bind(subject)
        .fetch_optional(pool)
        .await
        .map_err(AppError::Database)?
        .ok_or_else(|| AppError::NotFound(format!("User '{subject}' not found")))?;
    let direct_grants = sqlx::query_as::<_, GrantRecord>(
        "SELECT package_name, access_level FROM user_app_grants WHERE user_subject = ? ORDER BY package_name",
    )
    .bind(subject)
    .fetch_all(pool)
    .await
    .map_err(AppError::Database)?;
    let groups = sqlx::query_as::<_, AppGroup>(
        r#"
        SELECT groups.* FROM app_groups groups
        JOIN user_app_group_memberships memberships ON memberships.group_id = groups.id
        WHERE memberships.user_subject = ? ORDER BY groups.name
        "#,
    )
    .bind(subject)
    .fetch_all(pool)
    .await
    .map_err(AppError::Database)?;
    let effective_access = get_effective_app_access(pool, subject).await?;
    Ok(UserAccessDetail {
        user,
        direct_grants,
        groups,
        effective_access,
    })
}

pub async fn list_groups(pool: &SqlitePool) -> Result<Vec<GroupDetail>, AppError> {
    let groups = sqlx::query_as::<_, AppGroup>("SELECT * FROM app_groups ORDER BY name")
        .fetch_all(pool)
        .await
        .map_err(AppError::Database)?;
    let mut details = Vec::with_capacity(groups.len());
    for group in groups {
        let grants = sqlx::query_as::<_, GrantRecord>(
            "SELECT package_name, access_level FROM app_group_grants WHERE group_id = ? ORDER BY package_name",
        )
        .bind(group.id)
        .fetch_all(pool)
        .await
        .map_err(AppError::Database)?;
        let user_subjects = sqlx::query_scalar(
            "SELECT user_subject FROM user_app_group_memberships WHERE group_id = ? ORDER BY user_subject",
        )
        .bind(group.id)
        .fetch_all(pool)
        .await
        .map_err(AppError::Database)?;
        details.push(GroupDetail {
            group,
            grants,
            user_subjects,
        });
    }
    Ok(details)
}

async fn audit(
    conn: &mut SqliteConnection,
    actor: &str,
    action: &str,
    target_type: &str,
    target_id: &str,
    details: Value,
) -> Result<(), AppError> {
    sqlx::query(
        "INSERT INTO access_audit_log (actor_subject, action, target_type, target_id, details_json) VALUES (?, ?, ?, ?, ?)",
    )
    .bind(actor)
    .bind(action)
    .bind(target_type)
    .bind(target_id)
    .bind(details.to_string())
    .execute(conn)
    .await
    .map_err(AppError::Database)?;
    Ok(())
}

pub async fn create_group(
    pool: &SqlitePool,
    actor: &str,
    name: &str,
) -> Result<AppGroup, AppError> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest(
            "Group name must contain between 1 and 100 characters".to_string(),
        ));
    }
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    let result = sqlx::query("INSERT INTO app_groups (name) VALUES (?)")
        .bind(name)
        .execute(&mut *tx)
        .await
        .map_err(|error| match &error {
            sqlx::Error::Database(database) if database.is_unique_violation() => {
                AppError::Conflict(format!("An app group named '{name}' already exists"))
            }
            _ => AppError::Database(error),
        })?;
    let id = result.last_insert_rowid();
    audit(
        &mut tx,
        actor,
        "group.created",
        "app_group",
        &id.to_string(),
        json!({"name": name}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    sqlx::query_as("SELECT * FROM app_groups WHERE id = ?")
        .bind(id)
        .fetch_one(pool)
        .await
        .map_err(AppError::Database)
}

pub async fn rename_group(
    pool: &SqlitePool,
    actor: &str,
    group_id: i64,
    name: &str,
) -> Result<(), AppError> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest(
            "Group name must contain between 1 and 100 characters".to_string(),
        ));
    }
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    let result =
        sqlx::query("UPDATE app_groups SET name = ?, updated_at = datetime('now') WHERE id = ?")
            .bind(name)
            .bind(group_id)
            .execute(&mut *tx)
            .await
            .map_err(|error| match &error {
                sqlx::Error::Database(database) if database.is_unique_violation() => {
                    AppError::Conflict(format!("An app group named '{name}' already exists"))
                }
                _ => AppError::Database(error),
            })?;
    if result.rows_affected() == 0 {
        return Err(AppError::NotFound(format!(
            "App group {group_id} not found"
        )));
    }
    audit(
        &mut tx,
        actor,
        "group.renamed",
        "app_group",
        &group_id.to_string(),
        json!({"name": name}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    Ok(())
}

pub async fn delete_group(pool: &SqlitePool, actor: &str, group_id: i64) -> Result<(), AppError> {
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    let result = sqlx::query("DELETE FROM app_groups WHERE id = ?")
        .bind(group_id)
        .execute(&mut *tx)
        .await
        .map_err(AppError::Database)?;
    if result.rows_affected() == 0 {
        return Err(AppError::NotFound(format!(
            "App group {group_id} not found"
        )));
    }
    audit(
        &mut tx,
        actor,
        "group.deleted",
        "app_group",
        &group_id.to_string(),
        json!({}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    Ok(())
}

async fn require_known_user(conn: &mut SqliteConnection, subject: &str) -> Result<(), AppError> {
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM known_users WHERE subject = ?)")
            .bind(subject)
            .fetch_one(conn)
            .await
            .map_err(AppError::Database)?;
    if !exists {
        return Err(AppError::NotFound(format!("User '{subject}' not found")));
    }
    Ok(())
}

async fn require_app(conn: &mut SqliteConnection, package_name: &str) -> Result<(), AppError> {
    let exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM apps WHERE package_name = ?)")
            .bind(package_name)
            .fetch_one(conn)
            .await
            .map_err(AppError::Database)?;
    if !exists {
        return Err(AppError::NotFound(format!(
            "App '{package_name}' not found"
        )));
    }
    Ok(())
}

pub async fn set_direct_grant(
    pool: &SqlitePool,
    actor: &str,
    subject: &str,
    package_name: &str,
    level: AppAccessLevel,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    require_known_user(&mut tx, subject).await?;
    require_app(&mut tx, package_name).await?;
    sqlx::query(
        r#"INSERT INTO user_app_grants (user_subject, package_name, access_level) VALUES (?, ?, ?)
           ON CONFLICT(user_subject, package_name) DO UPDATE SET access_level = excluded.access_level, updated_at = datetime('now')"#,
    )
    .bind(subject).bind(package_name).bind(level.as_str()).execute(&mut *tx).await.map_err(AppError::Database)?;
    audit(
        &mut tx,
        actor,
        "direct_grant.set",
        "user_app",
        &format!("{subject}:{package_name}"),
        json!({"access_level": level}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    Ok(())
}

pub async fn remove_direct_grant(
    pool: &SqlitePool,
    actor: &str,
    subject: &str,
    package_name: &str,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    let result =
        sqlx::query("DELETE FROM user_app_grants WHERE user_subject = ? AND package_name = ?")
            .bind(subject)
            .bind(package_name)
            .execute(&mut *tx)
            .await
            .map_err(AppError::Database)?;
    if result.rows_affected() == 0 {
        return Err(AppError::NotFound("Direct app grant not found".to_string()));
    }
    audit(
        &mut tx,
        actor,
        "direct_grant.removed",
        "user_app",
        &format!("{subject}:{package_name}"),
        json!({}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    Ok(())
}

pub async fn set_group_grant(
    pool: &SqlitePool,
    actor: &str,
    group_id: i64,
    package_name: &str,
    level: AppAccessLevel,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM app_groups WHERE id = ?)")
        .bind(group_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(AppError::Database)?;
    if !exists {
        return Err(AppError::NotFound(format!(
            "App group {group_id} not found"
        )));
    }
    require_app(&mut tx, package_name).await?;
    sqlx::query(r#"INSERT INTO app_group_grants (group_id, package_name, access_level) VALUES (?, ?, ?)
        ON CONFLICT(group_id, package_name) DO UPDATE SET access_level = excluded.access_level, updated_at = datetime('now')"#)
        .bind(group_id).bind(package_name).bind(level.as_str()).execute(&mut *tx).await.map_err(AppError::Database)?;
    audit(
        &mut tx,
        actor,
        "group_grant.set",
        "group_app",
        &format!("{group_id}:{package_name}"),
        json!({"access_level": level}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    Ok(())
}

pub async fn remove_group_grant(
    pool: &SqlitePool,
    actor: &str,
    group_id: i64,
    package_name: &str,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    let result =
        sqlx::query("DELETE FROM app_group_grants WHERE group_id = ? AND package_name = ?")
            .bind(group_id)
            .bind(package_name)
            .execute(&mut *tx)
            .await
            .map_err(AppError::Database)?;
    if result.rows_affected() == 0 {
        return Err(AppError::NotFound("Group app grant not found".to_string()));
    }
    audit(
        &mut tx,
        actor,
        "group_grant.removed",
        "group_app",
        &format!("{group_id}:{package_name}"),
        json!({}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    Ok(())
}

pub async fn set_membership(
    pool: &SqlitePool,
    actor: &str,
    group_id: i64,
    subject: &str,
    present: bool,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    require_known_user(&mut tx, subject).await?;
    let group_exists: bool =
        sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM app_groups WHERE id = ?)")
            .bind(group_id)
            .fetch_one(&mut *tx)
            .await
            .map_err(AppError::Database)?;
    if !group_exists {
        return Err(AppError::NotFound(format!(
            "App group {group_id} not found"
        )));
    }
    if present {
        sqlx::query("INSERT INTO user_app_group_memberships (user_subject, group_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
            .bind(subject).bind(group_id).execute(&mut *tx).await.map_err(AppError::Database)?;
    } else {
        let result = sqlx::query(
            "DELETE FROM user_app_group_memberships WHERE user_subject = ? AND group_id = ?",
        )
        .bind(subject)
        .bind(group_id)
        .execute(&mut *tx)
        .await
        .map_err(AppError::Database)?;
        if result.rows_affected() == 0 {
            return Err(AppError::NotFound("Group membership not found".to_string()));
        }
    }
    audit(
        &mut tx,
        actor,
        if present {
            "membership.set"
        } else {
            "membership.removed"
        },
        "group_user",
        &format!("{group_id}:{subject}"),
        json!({}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    Ok(())
}

pub async fn set_release_channel(
    pool: &SqlitePool,
    actor: &str,
    package_name: &str,
    version_code: i64,
    is_beta: bool,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await.map_err(AppError::Database)?;
    let result = sqlx::query(
        "UPDATE app_versions SET is_beta = ? WHERE package_name = ? AND version_code = ?",
    )
    .bind(is_beta)
    .bind(package_name)
    .bind(version_code)
    .execute(&mut *tx)
    .await
    .map_err(AppError::Database)?;
    if result.rows_affected() == 0 {
        return Err(AppError::NotFound(format!(
            "Version {version_code} not found for '{package_name}'"
        )));
    }
    audit(
        &mut tx,
        actor,
        "release.channel_set",
        "app_version",
        &format!("{package_name}:{version_code}"),
        json!({"is_beta": is_beta}),
    )
    .await?;
    tx.commit().await.map_err(AppError::Database)?;
    Ok(())
}
