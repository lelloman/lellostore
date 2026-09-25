use super::AppState;
use crate::{
    auth::AdminUser,
    db,
    error::AppError,
    services::{apk_signatures, upload::calculate_sha256_file},
};
use serde::{Deserialize, Serialize};
use simple_server::web::{
    extract::{Path, State},
    Json,
};

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MigrationEvidence {
    pub tested_upgrade: bool,
    pub database_preserved: bool,
    pub authentication_preserved: bool,
    pub files_preserved: bool,
    pub evidence: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ReviewRequest {
    pub version_code: i64,
    pub expected_revision: i64,
    pub migration: MigrationEvidence,
}

pub async fn review(
    admin: AdminUser,
    State(state): State<AppState>,
    Path(package): Path<String>,
    Json(request): Json<ReviewRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let evidence = &request.migration;
    if !evidence.tested_upgrade
        || !evidence.database_preserved
        || !evidence.authentication_preserved
        || !evidence.files_preserved
        || evidence.evidence.trim().is_empty()
        || evidence.evidence.len() > 8192
    {
        return Err(AppError::BadRequest("Confirm the tested upgrade preserves the database, authentication and files, and record test evidence (up to 8 KiB)".into()));
    }
    let app = db::get_app(&state.db, &package)
        .await?
        .ok_or_else(|| AppError::NotFound("App not found".into()))?;
    let versions = db::get_app_versions(&state.db, &package).await?;
    let target = versions
        .iter()
        .find(|v| v.version_code == request.version_code && v.publication_state == "draft")
        .ok_or_else(|| AppError::NotFound("Draft not found".into()))?;
    if target.is_beta || target.distribution_mode == app.distribution_mode {
        return Err(AppError::BadRequest(
            "A distribution transition requires a stable draft in the other mode".into(),
        ));
    }
    let highest: Option<i64> = sqlx::query_scalar(
        "SELECT MAX(version_code) FROM published_apk_identities WHERE package_name = ?",
    )
    .bind(&package)
    .fetch_one(&state.db)
    .await?;
    let previous = versions
        .iter()
        .filter(|v| v.publication_state != "draft")
        .max_by_key(|v| v.version_code)
        .ok_or_else(|| AppError::Conflict("No retained published installer to compare".into()))?;
    if highest != Some(previous.version_code) {
        return Err(AppError::Conflict(
            "The latest published installer must be retained for signing comparison".into(),
        ));
    }
    if highest.is_some_and(|v| v >= target.version_code)
        || target.version_code <= previous.version_code
    {
        return Err(AppError::Conflict(
            "Transition APK must exceed every previously published APK version".into(),
        ));
    }
    let mut signer = None;
    for version in [previous, target] {
        let path = state.config.storage_path.join(&version.apk_path);
        if calculate_sha256_file(&path).await? != version.sha256 {
            return Err(AppError::Conflict("Stored APK changed".into()));
        }
        let fingerprint = apk_signatures::verified_signer(&path).await?;
        if signer
            .as_ref()
            .is_some_and(|expected| expected != &fingerprint)
        {
            return Err(AppError::Conflict(
                "APK signing identities differ; in-place distribution switching is unavailable"
                    .into(),
            ));
        }
        signer = Some(fingerprint);
    }
    let id = uuid::Uuid::new_v4().to_string();
    let signer = signer.unwrap();
    let mut tx = state.db.begin().await?;
    db::paravoid::lock_app(&mut tx, &package, request.expected_revision).await?;
    sqlx::query("INSERT INTO distribution_reviews(id,package_name,from_mode,to_mode,from_version,target_version,target_sha256,signer_sha256,review_revision,migration_evidence,actor_subject) VALUES (?,?,?,?,?,?,?,?,?,?,?)")
        .bind(&id).bind(&package).bind(&app.distribution_mode).bind(&target.distribution_mode).bind(previous.version_code).bind(target.version_code).bind(&target.sha256).bind(&signer).bind(request.expected_revision+1).bind(serde_json::to_string(&request.migration).unwrap()).bind(&admin.0.subject).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(Json(
        serde_json::json!({"id":id,"publication_revision":request.expected_revision+1,"signer_sha256":signer,"from_mode":app.distribution_mode,"to_mode":target.distribution_mode}),
    ))
}

#[derive(Serialize, sqlx::FromRow)]
pub struct SavedReview {
    pub id: String,
    pub from_mode: String,
    pub to_mode: String,
    pub from_version: i64,
    pub target_version: i64,
    pub target_sha256: String,
    pub signer_sha256: String,
    pub review_revision: i64,
    pub migration_evidence: String,
    pub actor_subject: String,
    pub created_at: String,
}
pub async fn history(
    _admin: AdminUser,
    State(state): State<AppState>,
    Path(package): Path<String>,
) -> Result<Json<Vec<SavedReview>>, AppError> {
    Ok(Json(sqlx::query_as("SELECT * FROM distribution_reviews WHERE package_name = ? ORDER BY review_revision DESC LIMIT 100").bind(package).fetch_all(&state.db).await?))
}
