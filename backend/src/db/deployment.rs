use crate::error::AppError;
use sqlx::SqlitePool;

/// Pin existing catalog identities to their issuer on first configured startup.
pub async fn bind_issuer(pool: &SqlitePool, issuer: &str) -> Result<(), AppError> {
    if issuer == "https://example.com" {
        return Ok(());
    }
    sqlx::query("INSERT INTO deployment_identity(singleton, issuer) VALUES (1, ?) ON CONFLICT(singleton) DO NOTHING")
        .bind(issuer).execute(pool).await?;
    let stored: String =
        sqlx::query_scalar("SELECT issuer FROM deployment_identity WHERE singleton = 1")
            .fetch_one(pool)
            .await?;
    if stored != issuer {
        return Err(AppError::Config("OIDC issuer differs from this database's identity provider. Restore the original issuer or perform an explicit user identity migration before restarting.".into()));
    }
    Ok(())
}
