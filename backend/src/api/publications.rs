use serde::Deserialize;
use simple_server::web::{
    extract::{Path, State},
    Json,
};

use super::AppState;
use crate::{
    auth::AdminUser,
    db::{self, publications::*},
    error::AppError,
};

pub async fn publish(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(package): Path<String>,
    Json(request): Json<PublishRequest>,
) -> Result<Json<PublicationResult>, AppError> {
    let version = db::get_app_versions(&state.db, &package)
        .await?
        .into_iter()
        .find(|v| v.version_code == request.version_code)
        .ok_or_else(|| AppError::NotFound("Draft not found".into()))?;
    // Recheck stored bytes before committing publication; never offer missing or changed files.
    let path = state.config.storage_path.join(&version.apk_path);
    let actual = crate::services::upload::calculate_sha256_file(&path).await?;
    if actual != version.sha256 || tokio::fs::metadata(&path).await?.len() != version.size as u64 {
        return Err(AppError::Conflict(
            "Stored artifact failed integrity verification; upload a valid replacement draft"
                .into(),
        ));
    }
    if version.distribution_mode == "paravoid" {
        let contract: db::paravoid::Contract = sqlx::query_as("SELECT c.* FROM paravoid_contracts c JOIN paravoid_installers i USING(package_name,contract_id) WHERE i.package_name = ? AND i.installer_version = ? AND verification_state = 'verified'")
            .bind(&package).bind(version.version_code).fetch_optional(&state.db).await?
            .ok_or_else(|| AppError::Conflict("Verified shell registration is required".into()))?;
        let previous: Option<(i64,String)> = sqlx::query_as("SELECT version_code,sha256 FROM published_apk_identities WHERE package_name = ? ORDER BY version_code DESC LIMIT 1")
            .bind(&package).fetch_optional(&state.db).await?;
        if let Some((code, hash)) = previous {
            let previous_path: Option<String> = sqlx::query_scalar("SELECT apk_path FROM app_versions WHERE package_name = ? AND version_code = ? AND sha256 = ?")
                .bind(&package).bind(code).bind(&hash).fetch_optional(&state.db).await?;
            let previous_path = state.config.storage_path.join(previous_path.ok_or_else(|| {
                AppError::Conflict(
                    "Retain the latest published installer for signer continuity verification"
                        .into(),
                )
            })?);
            if crate::services::upload::calculate_sha256_file(&previous_path).await? != hash {
                return Err(AppError::Conflict(
                    "Previous installer failed integrity verification".into(),
                ));
            }
            let signer = crate::services::apk_signatures::verified_signer(&previous_path).await?;
            if contract.shell_policy()?.descriptor.installed.apk_signers != [signer] {
                return Err(AppError::Conflict(
                    "Shell updates must preserve the published APK signing identity".into(),
                ));
            }
        }
        let signing = state.paravoid_signing.as_ref().ok_or_else(|| {
            AppError::Conflict("Configure online Paravoid signing before publication".into())
        })?;
        signing.supports_policy(&contract.policy()?, contract.authentication == "apkKey")
            .map_err(|_| AppError::Conflict("Configured endpoint and online signing keys must match the APK's pinned policy".into()))?;
        if contract.authentication == "apkKey" && state.personalizer.is_none() {
            return Err(AppError::Conflict(
                "Configure APK personalization before publishing a keyed shell".into(),
            ));
        }
        let embedded: Option<String> = sqlx::query_scalar("SELECT embedded_vpk_id FROM paravoid_installers WHERE package_name=? AND installer_version=?")
            .bind(&package).bind(version.version_code).fetch_one(&state.db).await?;
        if let Some(id) = embedded.as_ref().or(request.bootstrap_vpk.as_ref()) {
            let release = db::paravoid::release(&state.db, &package, id).await?;
            let file = state.config.storage_path.join(&release.archive_path);
            if crate::services::upload::calculate_sha256_file(&file).await?
                != release.archive_sha256
                || tokio::fs::metadata(file).await?.len() != release.archive_size as u64
            {
                return Err(AppError::Conflict(
                    "Bootstrap payload failed stored integrity verification".into(),
                ));
            }
        }
    }
    let app = db::get_app(&state.db, &package)
        .await?
        .ok_or_else(|| AppError::NotFound("App not found".into()))?;
    let mut transition_signer = None;
    if app.distribution_mode != version.distribution_mode {
        let previous = db::get_app_versions(&state.db, &package)
            .await?
            .into_iter()
            .filter(|v| v.publication_state != "draft")
            .max_by_key(|v| v.version_code);
        if let Some(previous) = previous {
            let previous_path = state.config.storage_path.join(&previous.apk_path);
            if crate::services::upload::calculate_sha256_file(&previous_path).await?
                != previous.sha256
            {
                return Err(AppError::Conflict(
                    "Previous installer failed integrity verification".into(),
                ));
            }
            let previous_signer =
                crate::services::apk_signatures::verified_signer(&previous_path).await?;
            let target_signer = crate::services::apk_signatures::verified_signer(&path).await?;
            if previous_signer != target_signer {
                return Err(AppError::Conflict(
                    "APK signing identities differ; in-place distribution switching is unavailable"
                        .into(),
                ));
            }
            let highest: Option<i64> = sqlx::query_scalar(
                "SELECT MAX(version_code) FROM published_apk_identities WHERE package_name = ?",
            )
            .bind(&package)
            .fetch_one(&state.db)
            .await?;
            if highest != Some(previous.version_code) {
                return Err(AppError::Conflict(
                    "Retain the latest published installer for signer continuity verification"
                        .into(),
                ));
            }
            transition_signer = Some(target_signer);
        }
    }
    let result = db::publications::publish_checked(
        &state.db,
        &package,
        &admin.0.subject,
        &request,
        transition_signer.as_deref(),
    )
    .await?;
    if let Err(error) =
        crate::services::retention::cleanup_replaced(&state.db, &state.config.storage_path).await
    {
        tracing::warn!(%error, "Artifact cleanup will retry in the background");
    }
    state.catalog_events.notify_catalog_changed();
    Ok(Json(result))
}

pub async fn withdraw(
    admin: AdminUser,
    State(state): State<AppState>,
    Path((package, code)): Path<(String, i64)>,
    Json(request): Json<RevisionRequest>,
) -> Result<Json<PublicationResult>, AppError> {
    let result = db::publications::withdraw(
        &state.db,
        &package,
        code,
        &admin.0.subject,
        request.expected_revision,
    )
    .await?;
    if let Err(error) =
        crate::services::retention::cleanup_replaced(&state.db, &state.config.storage_path).await
    {
        tracing::warn!(%error, "Artifact cleanup will retry in the background");
    }
    state.catalog_events.notify_catalog_changed();
    Ok(Json(result))
}

pub async fn history(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(package): Path<String>,
) -> Result<Json<Vec<PublicationEvent>>, AppError> {
    Ok(Json(db::publications::history(&state.db, &package).await?))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DraftMetadata {
    pub release_notes: String,
    pub is_beta: bool,
}

pub async fn edit_draft(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path((package, code)): Path<(String, i64)>,
    Json(request): Json<DraftMetadata>,
) -> Result<Json<serde_json::Value>, AppError> {
    if request.release_notes.len() > 64 * 1024 {
        return Err(AppError::BadRequest("Release notes exceed 64 KiB".into()));
    }
    let mut tx = state.db.begin().await?;
    sqlx::query(
        "UPDATE apps SET publication_revision = publication_revision + 1 WHERE package_name = ?",
    )
    .bind(&package)
    .execute(&mut *tx)
    .await?;
    let pinned: Option<String> = sqlx::query_scalar("SELECT c.channel FROM paravoid_contracts c JOIN paravoid_installers i USING(package_name,contract_id) WHERE i.package_name = ? AND i.installer_version = ?")
        .bind(&package).bind(code).fetch_optional(&mut *tx).await?;
    if pinned.is_some_and(|channel| channel != if request.is_beta { "beta" } else { "stable" }) {
        return Err(AppError::Conflict(
            "The shell channel is pinned in the signed APK; upload a new installer to change it"
                .into(),
        ));
    }
    let changed = sqlx::query("UPDATE app_versions SET release_notes = ?, is_beta = ? WHERE package_name = ? AND version_code = ? AND publication_state = 'draft'")
        .bind(request.release_notes).bind(request.is_beta).bind(&package).bind(code).execute(&mut *tx).await?;
    if changed.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Only draft metadata can be edited".into(),
        ));
    }
    tx.commit().await?;
    Ok(Json(serde_json::json!({"status": "saved"})))
}
