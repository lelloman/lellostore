//! Mark superseded files inside the publication transaction. Cleanup is retryable.
use crate::error::AppError;
use sqlx::SqliteConnection;

pub async fn replace_apks(
    conn: &mut SqliteConnection,
    package: &str,
    beta: bool,
    code: i64,
) -> Result<(), AppError> {
    sqlx::query("UPDATE app_versions SET publication_state = 'withdrawn', artifact_removed = 1 WHERE package_name = ? AND is_beta = ? AND version_code < ? AND archived = 0 AND artifact_removed = 0")
        .bind(package).bind(beta).bind(code).execute(conn).await?;
    Ok(())
}

pub async fn replace_vpks(
    conn: &mut SqliteConnection,
    package: &str,
    contract: &str,
    version: i64,
) -> Result<(), AppError> {
    sqlx::query("UPDATE vpk_releases SET publication_state = 'withdrawn', artifact_removed = 1 WHERE package_name = ? AND contract_id = ? AND payload_version < ? AND archived = 0 AND artifact_removed = 0")
        .bind(package).bind(contract).bind(version).execute(conn).await?;
    Ok(())
}
