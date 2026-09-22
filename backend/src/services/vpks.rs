use super::upload_jobs::UploadJob;
use crate::{
    db::paravoid,
    error::AppError,
    paravoid::{compatibility, TrustPolicy},
};
use sqlx::SqlitePool;
use std::path::Path;

pub async fn process_job(
    pool: &SqlitePool,
    storage: &Path,
    job: &UploadJob,
) -> Result<(), AppError> {
    let package = job
        .package_name
        .as_deref()
        .ok_or_else(|| AppError::BadRequest("VPK job has no application".into()))?;
    let contract_id = job
        .contract_id
        .as_deref()
        .ok_or_else(|| AppError::BadRequest("VPK job has no shell contract".into()))?;
    let contract = paravoid::contract(pool, package, contract_id).await?;
    let verified = contract.verification_state == "verified";
    let reservations = if verified {
        contract
            .shell_policy()?
            .descriptor
            .installed
            .ledger_reservations
    } else {
        std::collections::BTreeMap::new()
    };
    let input = job.input_path.clone();
    let pinned = contract.trust_json.clone();
    let contract_hash = contract.contract_id.clone();
    let checked = tokio::task::spawn_blocking(move || {
        let trust = TrustPolicy::parse(pinned.as_bytes())
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
        compatibility::inspect(
            &mut std::fs::File::open(input)?,
            &trust,
            &contract_hash,
            &reservations,
        )
        .map_err(|e| AppError::BadRequest(e.to_string()))
    })
    .await
    .map_err(|_| AppError::Internal("VPK validation task failed".into()))??;
    // Only signature-verified APK registration supplies authoritative reservations.
    let inspection = checked.archive;
    if inspection.release.application_id != package {
        return Err(AppError::BadRequest(
            "VPK application differs from upload target".into(),
        ));
    }
    let relative = format!("vpks/{}.vpk", inspection.archive_sha256);
    let destination = storage.join(&relative);
    tokio::fs::create_dir_all(storage.join("vpks")).await?;
    // Link a fully synced temporary file into its immutable content address.
    // A crash during copying cannot leave a truncated canonical artifact.
    let staging = tempfile::NamedTempFile::new_in(storage.join("vpks"))?;
    tokio::fs::copy(&job.input_path, staging.path()).await?;
    tokio::fs::File::open(staging.path())
        .await?
        .sync_all()
        .await?;
    match tokio::fs::hard_link(staging.path(), &destination).await {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(error) => return Err(error.into()),
    }
    tokio::fs::File::open(storage.join("vpks"))
        .await?
        .sync_all()
        .await?;
    // Never admit replaced/corrupt bytes at an existing content address.
    if super::upload::calculate_sha256_file(&destination).await? != inspection.archive_sha256
        || tokio::fs::metadata(&destination).await?.len() != inspection.archive_size
    {
        return Err(AppError::Conflict(
            "Stored VPK failed checksum verification".into(),
        ));
    }
    let mut tx = pool.begin().await?;
    sqlx::query(
        "UPDATE apps SET publication_revision = publication_revision + 1 WHERE package_name = ?",
    )
    .bind(package)
    .execute(&mut *tx)
    .await?;
    let used: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM published_vpk_identities WHERE package_name = ? AND (payload_version = ? OR release_id = ?)) OR EXISTS(SELECT 1 FROM vpk_releases WHERE package_name = ? AND (payload_version = ? OR release_id = ?))")
        .bind(package).bind(inspection.release.payload_version as i64).bind(&inspection.release.release_id).bind(package).bind(inspection.release.payload_version as i64).bind(&inspection.release.release_id).fetch_one(&mut *tx).await?;
    if used {
        return Err(AppError::Conflict(
            "Payload version or release ID is already reserved; use a new identity".into(),
        ));
    }
    let id = uuid::Uuid::new_v4().to_string();
    let manifest = serde_json::to_string(&inspection.release)
        .map_err(|_| AppError::Internal("VPK manifest serialization failed".into()))?;
    let report = serde_json::json!({"container":"passed","signature":"passed","inventory":"passed","components":"passed","dex_files":checked.dex_files,"native_libraries":checked.native_libraries,"resource_reservations":if verified {"passed"} else {"pending"},"compatibility":if verified {"passed"} else {"pending"},"publication_ready":verified,"remaining":if verified {vec![]} else {vec!["installed shell policy verification"]}});
    sqlx::query("INSERT INTO vpk_releases(id,package_name,contract_id,release_id,payload_version,archive_path,archive_size,archive_sha256,manifest_sha256,manifest_json,min_sdk,max_sdk,abis_json,signing_key_id,validation_state,validation_report) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        .bind(&id).bind(package).bind(contract_id).bind(&inspection.release.release_id).bind(inspection.release.payload_version as i64).bind(relative).bind(inspection.archive_size as i64).bind(&inspection.archive_sha256).bind(&inspection.manifest_sha256).bind(manifest).bind(inspection.release.min_sdk as i64).bind(inspection.release.max_sdk as i64).bind(serde_json::to_string(&inspection.release.abis).unwrap()).bind(&inspection.signing_key_id).bind(if verified {"verified"} else {"inspected"}).bind(report.to_string()).execute(&mut *tx).await?;
    let result = serde_json::json!({"package_name":package,"vpk_id":id,"release_id":inspection.release.release_id,"payload_version":inspection.release.payload_version});
    sqlx::query("UPDATE upload_jobs SET status = 'ready', result_json = ?, updated_at = datetime('now') WHERE id = ? AND status = 'validating'")
        .bind(result.to_string()).bind(&job.id).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(())
}
