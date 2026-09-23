//! Publication changes are serialized by the application's optimistic revision.
//! Artifact identities and publication events survive withdrawal and deletion.
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use super::models::AppVersion;
use crate::error::AppError;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PublishRequest {
    pub version_code: i64,
    pub expected_revision: i64,
    #[serde(default)]
    pub replace_latest: bool,
    #[serde(default)]
    pub transition_review: Option<String>,
    #[serde(default)]
    pub bootstrap_vpk: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RevisionRequest {
    pub expected_revision: i64,
}

#[derive(Debug, Serialize)]
pub struct PublicationResult {
    pub package_name: String,
    pub version_code: i64,
    pub publication_revision: i64,
    pub publication_state: &'static str,
}

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct PublicationEvent {
    pub id: i64,
    pub version_code: i64,
    pub revision: i64,
    pub actor_subject: String,
    pub action: String,
    pub created_at: String,
}

pub async fn history(pool: &SqlitePool, package: &str) -> Result<Vec<PublicationEvent>, AppError> {
    Ok(sqlx::query_as("SELECT id, version_code, revision, actor_subject, action, created_at FROM publication_events WHERE package_name = ? ORDER BY revision DESC LIMIT 200")
        .bind(package).fetch_all(pool).await?)
}

pub async fn publish(
    pool: &SqlitePool,
    package: &str,
    actor: &str,
    request: &PublishRequest,
) -> Result<PublicationResult, AppError> {
    let mut tx = pool.begin().await?;
    // First statement acquires the write lock. No read-then-write race between publishers.
    let updated = sqlx::query("UPDATE apps SET publication_revision = publication_revision + 1 WHERE package_name = ? AND publication_revision = ?")
        .bind(package).bind(request.expected_revision).execute(&mut *tx).await?;
    if updated.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Publication changed. Refresh the review before publishing.".into(),
        ));
    }
    let version = sqlx::query_as::<_, AppVersion>(
        "SELECT * FROM app_versions WHERE package_name = ? AND version_code = ?",
    )
    .bind(package)
    .bind(request.version_code)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| AppError::NotFound("Draft release not found".into()))?;
    if version.publication_state != "draft" {
        return Err(AppError::Conflict(
            "Only a draft release can be published".into(),
        ));
    }
    let highest: Option<i64> = sqlx::query_scalar(
        "SELECT MAX(version_code) FROM published_apk_identities WHERE package_name = ?",
    )
    .bind(package)
    .fetch_one(&mut *tx)
    .await?;
    let current_mode: String =
        sqlx::query_scalar("SELECT distribution_mode FROM apps WHERE package_name = ?")
            .bind(package)
            .fetch_one(&mut *tx)
            .await?;
    if current_mode != version.distribution_mode {
        if version.is_beta {
            return Err(AppError::Conflict(
                "Distribution mode changes require a stable installer".into(),
            ));
        }
        let approved: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM distribution_reviews WHERE id = ? AND package_name = ? AND from_mode = ? AND to_mode = ? AND target_version = ? AND target_sha256 = ? AND review_revision = ?)")
            .bind(&request.transition_review).bind(package).bind(&current_mode).bind(&version.distribution_mode).bind(version.version_code).bind(&version.sha256).bind(request.expected_revision).fetch_one(&mut *tx).await?;
        if (highest.is_some()
            || current_mode != "normal"
            || version.distribution_mode != "paravoid")
            && !approved
        {
            return Err(AppError::Conflict("Verify signing continuity and review the data migration before changing distribution mode".into()));
        }
    }
    if version.distribution_mode == "paravoid" {
        let contract: super::paravoid::Contract = sqlx::query_as("SELECT c.* FROM paravoid_contracts c JOIN paravoid_installers i USING(package_name,contract_id) WHERE i.package_name = ? AND i.installer_version = ? AND c.verification_state = 'verified' AND i.signer_sha256 != ''")
            .bind(package).bind(version.version_code).fetch_optional(&mut *tx).await?
            .ok_or_else(|| AppError::Conflict("Signature-verified shell registration is required".into()))?;
        let policy = contract.shell_policy()?;
        if contract.channel != if version.is_beta { "beta" } else { "stable" } {
            return Err(AppError::Conflict(
                "Installer channel differs from pinned shell policy".into(),
            ));
        }
        let embedded: Option<String> = sqlx::query_scalar("SELECT embedded_vpk_id FROM paravoid_installers WHERE package_name=? AND installer_version=?")
            .bind(package).bind(version.version_code).fetch_one(&mut *tx).await?;
        let id = if contract.bootstrap == "embedded" {
            let id = embedded.as_deref().ok_or_else(|| {
                AppError::Conflict("Verified embedded payload registration is required".into())
            })?;
            if request
                .bootstrap_vpk
                .as_deref()
                .is_some_and(|selected| selected != id)
            {
                return Err(AppError::Conflict(
                    "The embedded bootstrap is fixed by the signed APK".into(),
                ));
            }
            id
        } else {
            request.bootstrap_vpk.as_deref().ok_or_else(|| {
                AppError::Conflict(
                    "Select a verified bootstrap payload for this empty shell".into(),
                )
            })?
        };
        let release: super::paravoid::VpkRelease = sqlx::query_as("SELECT * FROM vpk_releases WHERE package_name = ? AND id = ? AND contract_id = ? AND validation_state = 'verified'")
            .bind(package).bind(id).bind(&contract.contract_id).fetch_optional(&mut *tx).await?
            .ok_or_else(|| AppError::Conflict("Bootstrap payload must be verified for the exact shell contract".into()))?;
        let abis: Vec<String> = serde_json::from_str(&release.abis_json)
            .map_err(|_| AppError::Conflict("Invalid bootstrap ABI metadata".into()))?;
        let required: Vec<&str> = if policy.descriptor.installed.native_abis.is_empty() {
            vec!["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
        } else {
            policy
                .descriptor
                .installed
                .native_abis
                .keys()
                .map(String::as_str)
                .collect()
        };
        if release.min_sdk > version.min_sdk
            || release.max_sdk != 0
            || release.payload_version < policy.trust.minimum_payload_version() as i64
            || (!abis.is_empty() && required.iter().any(|abi| !abis.iter().any(|v| v == abi)))
        {
            return Err(AppError::Conflict(
                "Bootstrap payload must cover the installer's SDK and ABI range".into(),
            ));
        }
        // Mode activation and initial VPK publication share the same transaction.
        sqlx::query("UPDATE apps SET distribution_mode = 'paravoid' WHERE package_name = ?")
            .bind(package)
            .execute(&mut *tx)
            .await?;
        if release.publication_state == "draft" {
            super::paravoid::publish_tx(&mut tx, package, id, actor, request.expected_revision)
                .await?;
        } else if release.publication_state != "published" {
            return Err(AppError::Conflict(
                "A withdrawn payload cannot bootstrap a new installer".into(),
            ));
        }
        let retired: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM paravoid_streams WHERE package_name = ? AND contract_id = ? AND status = 'retired')").bind(package).bind(&contract.contract_id).fetch_one(&mut *tx).await?;
        if retired {
            return Err(AppError::Conflict(
                "Reactivate the shell stream before publishing a shell installer".into(),
            ));
        }
    } else if request.bootstrap_vpk.is_some() {
        return Err(AppError::BadRequest(
            "Normal installers do not use bootstrap payloads".into(),
        ));
    }
    if highest.is_some_and(|code| request.version_code <= code) {
        return Err(AppError::Conflict("APK version code must exceed every previously published version, including beta and withdrawn releases".into()));
    }
    if request.replace_latest {
        let previous: Option<i64> = sqlx::query_scalar("SELECT MAX(version_code) FROM app_versions WHERE package_name = ? AND is_beta = ? AND publication_state = 'published'")
            .bind(package).bind(version.is_beta).fetch_one(&mut *tx).await?;
        if let Some(code) = previous {
            sqlx::query("UPDATE app_versions SET publication_state = 'withdrawn' WHERE package_name = ? AND version_code = ?")
                .bind(package).bind(code).execute(&mut *tx).await?;
        }
    }
    sqlx::query(
        "INSERT INTO published_apk_identities(package_name, version_code, sha256) VALUES (?, ?, ?)",
    )
    .bind(package)
    .bind(version.version_code)
    .bind(&version.sha256)
    .execute(&mut *tx)
    .await?;
    sqlx::query("UPDATE app_versions SET publication_state = 'published', published_at = datetime('now') WHERE package_name = ? AND version_code = ?")
        .bind(package).bind(request.version_code).execute(&mut *tx).await?;
    sqlx::query("UPDATE apps SET name = COALESCE(?, name), description = COALESCE(?, description), updated_at = datetime('now') WHERE package_name = ?")
        .bind(&version.proposed_name).bind(&version.proposed_description).bind(package).execute(&mut *tx).await?;
    sqlx::query("UPDATE apps SET distribution_mode = ? WHERE package_name = ?")
        .bind(&version.distribution_mode)
        .bind(package)
        .execute(&mut *tx)
        .await?;
    let revision = request.expected_revision + 1;
    sqlx::query("INSERT INTO publication_events(package_name, version_code, revision, actor_subject, action) VALUES (?, ?, ?, ?, 'publish')")
        .bind(package).bind(request.version_code).bind(revision).bind(actor).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(PublicationResult {
        package_name: package.into(),
        version_code: request.version_code,
        publication_revision: revision,
        publication_state: "published",
    })
}

pub async fn withdraw(
    pool: &SqlitePool,
    package: &str,
    code: i64,
    actor: &str,
    expected_revision: i64,
) -> Result<PublicationResult, AppError> {
    let mut tx = pool.begin().await?;
    let updated = sqlx::query("UPDATE apps SET publication_revision = publication_revision + 1, updated_at = datetime('now') WHERE package_name = ? AND publication_revision = ?")
        .bind(package).bind(expected_revision).execute(&mut *tx).await?;
    if updated.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Publication changed. Refresh before withdrawing.".into(),
        ));
    }
    let changed = sqlx::query("UPDATE app_versions SET publication_state = 'withdrawn' WHERE package_name = ? AND version_code = ? AND publication_state = 'published'")
        .bind(package).bind(code).execute(&mut *tx).await?;
    if changed.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "Only a published release can be withdrawn".into(),
        ));
    }
    let revision = expected_revision + 1;
    sqlx::query("INSERT INTO publication_events(package_name, version_code, revision, actor_subject, action) VALUES (?, ?, ?, ?, 'withdraw')")
        .bind(package).bind(code).bind(revision).bind(actor).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(PublicationResult {
        package_name: package.into(),
        version_code: code,
        publication_revision: revision,
        publication_state: "withdrawn",
    })
}
