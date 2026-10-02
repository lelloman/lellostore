//! Durable DVPK generation jobs and verified delta relationships. A row is the
//! deduplicated job for one (contract, base archive, target archive, algorithm)
//! and, once `ready`, the only authority for advertising or serving its patch.
use super::paravoid::VpkRelease;
use crate::{error::AppError, paravoid::dvpk::ALGORITHM};
use serde::Serialize;
use sqlx::{SqliteConnection, SqlitePool};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};

/// Retired patches stay downloadable for outstanding heads and transfers.
pub const RETIRED_GRACE_SECS: i64 = 24 * 3600;
/// Default number of earlier published archives kept as delta bases.
pub const DEFAULT_RETAINED_BASES: i64 = 3;

static GENERATION: AtomicBool = AtomicBool::new(false);
static RETAINED_BASES: AtomicI64 = AtomicI64::new(DEFAULT_RETAINED_BASES);

/// Jobs are only enqueued while a generation worker is configured; otherwise
/// queued rows would pin superseded base archives against cleanup.
pub fn set_generation_enabled(enabled: bool) {
    GENERATION.store(enabled, Ordering::SeqCst);
}
pub fn generation_enabled() -> bool {
    GENERATION.load(Ordering::SeqCst)
}
pub fn set_retained_bases(count: i64) {
    RETAINED_BASES.store(count.max(1), Ordering::SeqCst);
}
/// How many earlier published archives of a contract stay stored after
/// replacement, and how many bases each target is diffed from. Zero while
/// generation is off: replaced archives are then removed as before.
pub fn retained_bases() -> i64 {
    if generation_enabled() {
        RETAINED_BASES.load(Ordering::SeqCst)
    } else {
        0
    }
}

#[derive(Clone, Debug, Serialize, sqlx::FromRow)]
pub struct Delta {
    pub id: String,
    pub package_name: String,
    pub contract_id: String,
    pub base_vpk_id: String,
    pub target_vpk_id: String,
    pub base_payload_version: i64,
    pub base_archive_sha256: String,
    pub base_archive_size: i64,
    pub target_archive_sha256: String,
    pub target_archive_size: i64,
    pub algorithm: String,
    pub state: String,
    pub attempts: i64,
    pub next_attempt_at: i64,
    pub claimed_at: Option<i64>,
    pub failure: Option<String>,
    pub patch_sha256: Option<String>,
    pub patch_size: Option<i64>,
    #[serde(skip_serializing)]
    pub patch_path: Option<String>,
    pub encoder_version: Option<String>,
    pub duration_ms: Option<i64>,
    pub verified_at: Option<i64>,
    pub retired_at: Option<i64>,
    pub file_removed: bool,
    pub created_at: i64,
    pub updated_at: i64,
}

fn now() -> i64 {
    chrono::Utc::now().timestamp()
}

/// Enqueue direct patches into a verified target from the most recent earlier
/// published, still-stored archives of the same app and shell contract.
/// Idempotent.
pub async fn enqueue_for_target(
    conn: &mut SqliteConnection,
    package: &str,
    target_vpk_id: &str,
) -> Result<u64, AppError> {
    if !generation_enabled() {
        return Ok(0);
    }
    let target: Option<VpkRelease> = sqlx::query_as("SELECT * FROM vpk_releases WHERE package_name = ? AND id = ? AND validation_state = 'verified' AND artifact_removed = 0")
        .bind(package).bind(target_vpk_id).fetch_optional(&mut *conn).await?;
    let Some(target) = target else {
        return Ok(0);
    };
    let bases: Vec<VpkRelease> = sqlx::query_as("SELECT b.* FROM vpk_releases b JOIN published_vpk_identities p ON p.package_name = b.package_name AND p.release_id = b.release_id AND p.archive_sha256 = b.archive_sha256 WHERE b.package_name = ? AND b.contract_id = ? AND b.validation_state = 'verified' AND b.artifact_removed = 0 AND b.payload_version < ? AND b.archive_sha256 != ? ORDER BY b.payload_version DESC LIMIT ?")
        .bind(package).bind(&target.contract_id).bind(target.payload_version).bind(&target.archive_sha256).bind(retained_bases()).fetch_all(&mut *conn).await?;
    let mut added = 0;
    let now = now();
    for base in bases {
        added += sqlx::query("INSERT OR IGNORE INTO vpk_deltas(id,package_name,contract_id,base_vpk_id,target_vpk_id,base_payload_version,base_archive_sha256,base_archive_size,target_archive_sha256,target_archive_size,algorithm,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .bind(uuid::Uuid::new_v4().to_string()).bind(package).bind(&target.contract_id).bind(&base.id).bind(&target.id)
            .bind(base.payload_version).bind(&base.archive_sha256).bind(base.archive_size).bind(&target.archive_sha256).bind(target.archive_size)
            .bind(ALGORITHM).bind(now).bind(now).execute(&mut *conn).await?.rows_affected();
    }
    Ok(added)
}

/// Verified offers for a selected, published target, in a deterministic order.
pub async fn ready_offers(
    conn: &mut SqliteConnection,
    target: &VpkRelease,
) -> Result<Vec<Delta>, AppError> {
    Ok(sqlx::query_as("SELECT * FROM vpk_deltas WHERE package_name = ? AND contract_id = ? AND target_vpk_id = ? AND target_archive_sha256 = ? AND target_archive_size = ? AND state = 'ready' AND file_removed = 0 ORDER BY base_payload_version DESC, base_archive_sha256, algorithm LIMIT ?")
        .bind(&target.package_name).bind(&target.contract_id).bind(&target.id).bind(&target.archive_sha256).bind(target.archive_size)
        .bind(crate::paravoid::dvpk::MAX_OFFERS as i64).fetch_all(conn).await?)
}

/// The patch for an authorized target and exact base. Retired patches remain
/// servable during their grace period; they are never advertised.
pub async fn servable(
    conn: &mut SqliteConnection,
    target: &VpkRelease,
    base_sha256: &str,
) -> Result<Option<Delta>, AppError> {
    Ok(sqlx::query_as("SELECT * FROM vpk_deltas WHERE package_name = ? AND contract_id = ? AND target_vpk_id = ? AND target_archive_sha256 = ? AND base_archive_sha256 = ? AND algorithm = ? AND state IN ('ready','retired') AND file_removed = 0")
        .bind(&target.package_name).bind(&target.contract_id).bind(&target.id).bind(&target.archive_sha256).bind(base_sha256).bind(ALGORITHM)
        .fetch_optional(conn).await?)
}

pub async fn list(pool: &SqlitePool, package: &str) -> Result<Vec<Delta>, AppError> {
    Ok(sqlx::query_as(
        "SELECT * FROM vpk_deltas WHERE package_name = ? ORDER BY created_at DESC, id LIMIT 200",
    )
    .bind(package)
    .fetch_all(pool)
    .await?)
}

/// Interrupted claims become queued again. Attempts already count them.
pub async fn recover(pool: &SqlitePool) -> Result<u64, AppError> {
    Ok(sqlx::query("UPDATE vpk_deltas SET state = 'queued', claimed_at = NULL, updated_at = ? WHERE state = 'running'")
        .bind(now()).execute(pool).await?.rows_affected())
}

pub async fn claim(pool: &SqlitePool) -> Result<Option<Delta>, AppError> {
    let now = now();
    Ok(sqlx::query_as("UPDATE vpk_deltas SET state = 'running', attempts = attempts + 1, claimed_at = ?, failure = NULL, updated_at = ? WHERE id = (SELECT id FROM vpk_deltas WHERE state = 'queued' AND next_attempt_at <= ? ORDER BY created_at, id LIMIT 1) AND state = 'queued' RETURNING *")
        .bind(now).bind(now).bind(now).fetch_optional(pool).await?)
}

pub struct VerifiedPatch {
    pub sha256: String,
    pub size: u64,
    pub path: String,
    pub encoder_version: String,
    pub duration_ms: i64,
}

/// Make a durable, verified patch visible. A published target's discovery
/// advances to a new revision before any head can include the offer.
pub async fn mark_ready(
    pool: &SqlitePool,
    delta: &Delta,
    patch: &VerifiedPatch,
) -> Result<bool, AppError> {
    let mut tx = pool.begin().await?;
    let now = now();
    let changed = sqlx::query("UPDATE vpk_deltas SET state = 'ready', patch_sha256 = ?, patch_size = ?, patch_path = ?, encoder_version = ?, duration_ms = ?, verified_at = ?, claimed_at = NULL, failure = NULL, updated_at = ? WHERE id = ? AND state = 'running' AND claimed_at = ?")
        .bind(&patch.sha256).bind(patch.size as i64).bind(&patch.path).bind(&patch.encoder_version).bind(patch.duration_ms)
        .bind(now).bind(now).bind(&delta.id).bind(delta.claimed_at).execute(&mut *tx).await?.rows_affected();
    if changed == 1 {
        invalidate_if_published(&mut tx, &delta.target_vpk_id).await?;
    }
    tx.commit().await?;
    Ok(changed == 1)
}

/// Terminal or retryable outcome of a claimed job. Never touches full delivery.
pub async fn finish(
    pool: &SqlitePool,
    delta: &Delta,
    state: &str,
    failure: &str,
    retry_at: Option<i64>,
    duration_ms: Option<i64>,
) -> Result<(), AppError> {
    let (state, next) = match retry_at {
        Some(at) => ("queued", at),
        None => (state, 0),
    };
    let failure: String = failure.chars().take(500).collect();
    sqlx::query("UPDATE vpk_deltas SET state = ?, failure = ?, next_attempt_at = ?, duration_ms = COALESCE(?, duration_ms), claimed_at = NULL, updated_at = ? WHERE id = ? AND state = 'running' AND claimed_at = ?")
        .bind(state).bind(failure).bind(next).bind(duration_ms).bind(now()).bind(&delta.id).bind(delta.claimed_at).execute(pool).await?;
    Ok(())
}

/// A missing or corrupt stored patch is never offered again. Advertised offers
/// are withdrawn through a new discovery revision; full delivery is unaffected.
pub async fn discard(pool: &SqlitePool, delta: &Delta, reason: &str) -> Result<(), AppError> {
    let mut tx = pool.begin().await?;
    let changed = sqlx::query("UPDATE vpk_deltas SET state = 'failed', failure = ?, updated_at = ? WHERE id = ? AND state IN ('ready','retired')")
        .bind(reason).bind(now()).bind(&delta.id).execute(&mut *tx).await?.rows_affected();
    if changed == 1 && delta.state == "ready" {
        invalidate_if_published(&mut tx, &delta.target_vpk_id).await?;
    }
    tx.commit().await?;
    Ok(())
}

async fn invalidate_if_published(
    conn: &mut SqliteConnection,
    target_vpk_id: &str,
) -> Result<(), AppError> {
    let target: Option<(String, String)> = sqlx::query_as("SELECT package_name, contract_id FROM vpk_releases WHERE id = ? AND publication_state = 'published'")
        .bind(target_vpk_id).fetch_optional(&mut *conn).await?;
    if let Some((package, contract)) = target {
        super::paravoid::invalidate_stream(conn, &package, &contract).await?;
    }
    Ok(())
}

/// Withdrawn targets (superseded or rolled back; withdrawal is terminal) are no
/// longer selected by discovery, so their patches retire without a new
/// revision, even while the target is retained as a base. Files outlive a
/// grace period.
pub async fn retire_superseded(pool: &SqlitePool, now: i64) -> Result<u64, AppError> {
    Ok(sqlx::query("UPDATE vpk_deltas SET state = 'retired', retired_at = ?, updated_at = ? WHERE state = 'ready' AND target_vpk_id IN (SELECT id FROM vpk_releases WHERE publication_state = 'withdrawn' OR artifact_removed = 1)")
        .bind(now).bind(now).execute(pool).await?.rows_affected())
}

/// Jobs that can no longer run are skipped rather than pinning their inputs.
pub async fn skip_orphaned(pool: &SqlitePool, now: i64) -> Result<u64, AppError> {
    Ok(sqlx::query("UPDATE vpk_deltas SET state = 'skipped', failure = 'Target was withdrawn or an input is no longer stored', updated_at = ? WHERE state = 'queued' AND (target_vpk_id IN (SELECT id FROM vpk_releases WHERE publication_state = 'withdrawn' OR artifact_removed = 1) OR base_vpk_id IN (SELECT id FROM vpk_releases WHERE artifact_cleaned = 1))")
        .bind(now).execute(pool).await?.rows_affected())
}

pub async fn expired_retired(pool: &SqlitePool, now: i64) -> Result<Vec<Delta>, AppError> {
    Ok(sqlx::query_as("SELECT * FROM vpk_deltas WHERE state IN ('retired','failed','skipped') AND file_removed = 0 AND patch_path IS NOT NULL AND COALESCE(retired_at, updated_at) < ? ORDER BY updated_at LIMIT 500")
        .bind(now - RETIRED_GRACE_SECS).fetch_all(pool).await?)
}
