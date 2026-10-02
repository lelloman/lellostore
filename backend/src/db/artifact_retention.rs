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

/// Older payloads are withdrawn. Their files are removed, except archived
/// payloads and the most recent previously published stored archives kept as
/// DVPK bases (none while delta generation is off). Each publication
/// re-evaluates that window, so earlier retained bases are released in turn.
pub async fn replace_vpks(
    conn: &mut SqliteConnection,
    package: &str,
    contract: &str,
    version: i64,
) -> Result<(), AppError> {
    sqlx::query("UPDATE vpk_releases SET publication_state = 'withdrawn', artifact_removed = CASE WHEN id IN (SELECT r.id FROM vpk_releases r JOIN published_vpk_identities p ON p.package_name = r.package_name AND p.release_id = r.release_id WHERE r.package_name = ? AND r.contract_id = ? AND r.payload_version < ? AND r.validation_state = 'verified' AND r.artifact_removed = 0 ORDER BY r.payload_version DESC LIMIT ?) THEN 0 ELSE 1 END WHERE package_name = ? AND contract_id = ? AND payload_version < ? AND archived = 0 AND artifact_removed = 0")
        .bind(package).bind(contract).bind(version).bind(super::dvpk::retained_bases())
        .bind(package).bind(contract).bind(version).execute(conn).await?;
    Ok(())
}
