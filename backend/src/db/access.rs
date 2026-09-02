use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use crate::error::AppError;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AppAccessLevel {
    Stable,
    Beta,
}

impl AppAccessLevel {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Stable => "stable",
            Self::Beta => "beta",
        }
    }

    fn from_rank(rank: i64) -> Result<Self, AppError> {
        match rank {
            1 => Ok(Self::Stable),
            2 => Ok(Self::Beta),
            _ => Err(AppError::Internal(format!(
                "Invalid app access rank returned by database: {rank}"
            ))),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct EffectiveAppAccess {
    pub package_name: String,
    pub access_level: AppAccessLevel,
}

#[derive(Debug, Clone, sqlx::FromRow, Serialize)]
pub struct AppGroup {
    pub id: i64,
    pub name: String,
    pub created_at: String,
    pub updated_at: String,
}

pub async fn set_direct_grant(
    pool: &SqlitePool,
    user_subject: &str,
    package_name: &str,
    access_level: AppAccessLevel,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        INSERT INTO user_app_grants (user_subject, package_name, access_level)
        VALUES (?, ?, ?)
        ON CONFLICT(user_subject, package_name) DO UPDATE SET
            access_level = excluded.access_level,
            updated_at = datetime('now')
        "#,
    )
    .bind(user_subject)
    .bind(package_name)
    .bind(access_level.as_str())
    .execute(pool)
    .await
    .map_err(AppError::Database)?;
    Ok(())
}

pub async fn remove_direct_grant(
    pool: &SqlitePool,
    user_subject: &str,
    package_name: &str,
) -> Result<(), AppError> {
    sqlx::query("DELETE FROM user_app_grants WHERE user_subject = ? AND package_name = ?")
        .bind(user_subject)
        .bind(package_name)
        .execute(pool)
        .await
        .map_err(AppError::Database)?;
    Ok(())
}

pub async fn create_group(pool: &SqlitePool, name: &str) -> Result<AppGroup, AppError> {
    let normalized_name = name.trim();
    let result = sqlx::query("INSERT INTO app_groups (name) VALUES (?)")
        .bind(normalized_name)
        .execute(pool)
        .await
        .map_err(AppError::Database)?;

    sqlx::query_as::<_, AppGroup>("SELECT * FROM app_groups WHERE id = ?")
        .bind(result.last_insert_rowid())
        .fetch_one(pool)
        .await
        .map_err(AppError::Database)
}

pub async fn set_group_grant(
    pool: &SqlitePool,
    group_id: i64,
    package_name: &str,
    access_level: AppAccessLevel,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        INSERT INTO app_group_grants (group_id, package_name, access_level)
        VALUES (?, ?, ?)
        ON CONFLICT(group_id, package_name) DO UPDATE SET
            access_level = excluded.access_level,
            updated_at = datetime('now')
        "#,
    )
    .bind(group_id)
    .bind(package_name)
    .bind(access_level.as_str())
    .execute(pool)
    .await
    .map_err(AppError::Database)?;
    Ok(())
}

pub async fn remove_group_grant(
    pool: &SqlitePool,
    group_id: i64,
    package_name: &str,
) -> Result<(), AppError> {
    sqlx::query("DELETE FROM app_group_grants WHERE group_id = ? AND package_name = ?")
        .bind(group_id)
        .bind(package_name)
        .execute(pool)
        .await
        .map_err(AppError::Database)?;
    Ok(())
}

pub async fn add_user_to_group(
    pool: &SqlitePool,
    user_subject: &str,
    group_id: i64,
) -> Result<(), AppError> {
    sqlx::query(
        r#"
        INSERT INTO user_app_group_memberships (user_subject, group_id)
        VALUES (?, ?)
        ON CONFLICT(user_subject, group_id) DO NOTHING
        "#,
    )
    .bind(user_subject)
    .bind(group_id)
    .execute(pool)
    .await
    .map_err(AppError::Database)?;
    Ok(())
}

pub async fn remove_user_from_group(
    pool: &SqlitePool,
    user_subject: &str,
    group_id: i64,
) -> Result<(), AppError> {
    sqlx::query("DELETE FROM user_app_group_memberships WHERE user_subject = ? AND group_id = ?")
        .bind(user_subject)
        .bind(group_id)
        .execute(pool)
        .await
        .map_err(AppError::Database)?;
    Ok(())
}

pub async fn get_effective_app_access(
    pool: &SqlitePool,
    user_subject: &str,
) -> Result<Vec<EffectiveAppAccess>, AppError> {
    let rows = sqlx::query_as::<_, (String, i64)>(
        r#"
        WITH grant_levels(package_name, access_rank) AS (
            SELECT package_name,
                   CASE access_level WHEN 'beta' THEN 2 ELSE 1 END
            FROM user_app_grants
            WHERE user_subject = ?

            UNION ALL

            SELECT grants.package_name,
                   CASE grants.access_level WHEN 'beta' THEN 2 ELSE 1 END
            FROM user_app_group_memberships memberships
            JOIN app_group_grants grants ON grants.group_id = memberships.group_id
            WHERE memberships.user_subject = ?
        )
        SELECT package_name, MAX(access_rank)
        FROM grant_levels
        GROUP BY package_name
        ORDER BY package_name
        "#,
    )
    .bind(user_subject)
    .bind(user_subject)
    .fetch_all(pool)
    .await
    .map_err(AppError::Database)?;

    rows.into_iter()
        .map(|(package_name, rank)| {
            Ok(EffectiveAppAccess {
                package_name,
                access_level: AppAccessLevel::from_rank(rank)?,
            })
        })
        .collect()
}

pub async fn get_effective_access_for_app(
    pool: &SqlitePool,
    user_subject: &str,
    package_name: &str,
) -> Result<Option<AppAccessLevel>, AppError> {
    Ok(get_effective_app_access(pool, user_subject)
        .await?
        .into_iter()
        .find(|grant| grant.package_name == package_name)
        .map(|grant| grant.access_level))
}

#[cfg(test)]
mod tests {
    use super::*;
    use sqlx::sqlite::SqlitePoolOptions;

    async fn test_pool() -> SqlitePool {
        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        sqlx::migrate!("./migrations").run(&pool).await.unwrap();
        pool
    }

    async fn insert_app(pool: &SqlitePool, package_name: &str) {
        sqlx::query("INSERT INTO apps (package_name, name) VALUES (?, ?)")
            .bind(package_name)
            .bind(package_name)
            .execute(pool)
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn users_have_no_implicit_access() {
        let pool = test_pool().await;
        insert_app(&pool, "app.one").await;

        assert!(get_effective_app_access(&pool, "new-user")
            .await
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn beta_is_the_highest_additive_grant() {
        let pool = test_pool().await;
        insert_app(&pool, "app.one").await;
        set_direct_grant(&pool, "user", "app.one", AppAccessLevel::Stable)
            .await
            .unwrap();
        let group = create_group(&pool, "Beta testers").await.unwrap();
        set_group_grant(&pool, group.id, "app.one", AppAccessLevel::Beta)
            .await
            .unwrap();
        add_user_to_group(&pool, "user", group.id).await.unwrap();

        assert_eq!(
            get_effective_access_for_app(&pool, "user", "app.one")
                .await
                .unwrap(),
            Some(AppAccessLevel::Beta)
        );

        remove_user_from_group(&pool, "user", group.id)
            .await
            .unwrap();
        assert_eq!(
            get_effective_access_for_app(&pool, "user", "app.one")
                .await
                .unwrap(),
            Some(AppAccessLevel::Stable)
        );
    }

    #[tokio::test]
    async fn group_rules_are_dynamic_for_all_members() {
        let pool = test_pool().await;
        insert_app(&pool, "media.music").await;
        insert_app(&pool, "media.movies").await;
        let group = create_group(&pool, "Media apps").await.unwrap();
        add_user_to_group(&pool, "alice", group.id).await.unwrap();
        add_user_to_group(&pool, "bob", group.id).await.unwrap();
        set_group_grant(&pool, group.id, "media.music", AppAccessLevel::Stable)
            .await
            .unwrap();

        set_group_grant(&pool, group.id, "media.movies", AppAccessLevel::Beta)
            .await
            .unwrap();

        for subject in ["alice", "bob"] {
            assert_eq!(
                get_effective_access_for_app(&pool, subject, "media.movies")
                    .await
                    .unwrap(),
                Some(AppAccessLevel::Beta)
            );
        }

        remove_group_grant(&pool, group.id, "media.music")
            .await
            .unwrap();
        assert_eq!(
            get_effective_access_for_app(&pool, "alice", "media.music")
                .await
                .unwrap(),
            None
        );
    }

    #[tokio::test]
    async fn releases_default_to_stable() {
        let pool = test_pool().await;
        insert_app(&pool, "app.one").await;
        sqlx::query(
            r#"
            INSERT INTO app_versions
                (package_name, version_code, version_name, apk_path, size, sha256, min_sdk)
            VALUES ('app.one', 1, '1.0', 'one.apk', 1, 'hash', 21)
            "#,
        )
        .execute(&pool)
        .await
        .unwrap();

        let is_beta: bool =
            sqlx::query_scalar("SELECT is_beta FROM app_versions WHERE package_name = 'app.one'")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert!(!is_beta);
    }

    #[tokio::test]
    async fn duplicate_and_invalid_relationships_are_rejected() {
        let pool = test_pool().await;
        insert_app(&pool, "app.one").await;
        let group = create_group(&pool, "Media apps").await.unwrap();
        add_user_to_group(&pool, "user", group.id).await.unwrap();

        let duplicate = sqlx::query(
            "INSERT INTO user_app_group_memberships (user_subject, group_id) VALUES (?, ?)",
        )
        .bind("user")
        .bind(group.id)
        .execute(&pool)
        .await;
        assert!(duplicate.is_err());

        let invalid_level = sqlx::query(
            "INSERT INTO user_app_grants (user_subject, package_name, access_level) VALUES (?, ?, ?)",
        )
        .bind("user")
        .bind("app.one")
        .bind("preview")
        .execute(&pool)
        .await;
        assert!(invalid_level.is_err());
    }
}
