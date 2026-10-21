//! Store-native device-code enrollment (RFC 8628 vocabulary).
//!
//! A device without a human login starts an enrollment and receives a secret
//! `device_code` and a short `user_code`. It shows the user code (and a QR of
//! the verification payload) and polls with the device code. An administrator
//! looks the user code up and approves it, which creates a device token (or
//! rekeys an existing one) whose secret nobody knows yet. The next poll
//! generates that secret, stores only its SHA-256 and returns it, once.
//!
//! Every state change is a conditional update inside a `BEGIN IMMEDIATE`
//! transaction, so approve, deny, expiry and redemption have a single winner.
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::{SqliteConnection, SqlitePool};

use super::device_tokens::{self, DeviceToken};
use super::token_secrets::{generate_secret, hash_secret, validate_name};
use crate::error::AppError;

/// Every device code starts with this prefix, so a leaked one is recognizable.
pub const DEVICE_CODE_PREFIX: &str = "lellostore_enr_";
/// Lifetime of a pending enrollment, and of an approval until it is redeemed.
pub const EXPIRES_IN_SECS: i64 = 600;
/// Minimum seconds between two polls of one enrollment.
pub const POLL_INTERVAL_SECS: i64 = 5;
/// A poll that arrives sooner than this after the previous one is `slow_down`.
/// Slightly under the interval, so network jitter does not punish a client
/// that sleeps exactly `interval` seconds.
const SLOW_DOWN_BELOW_SECS: f64 = POLL_INTERVAL_SECS as f64 * 0.8;
/// How long a denial that blocks its source refuses new enrollments from it.
pub const DENIAL_COOLDOWN_HOURS: i64 = 24;
/// At most this many enrollments per source address in [`QUOTA_WINDOW`].
pub const MAX_PER_SOURCE: i64 = 10;
/// At most this many pending enrollments at once, across all sources.
pub const MAX_PENDING: i64 = 100;
/// At most this many unknown user codes per administrator in [`QUOTA_WINDOW`].
pub const MAX_LOOKUP_FAILURES: i64 = 10;
const QUOTA_WINDOW: &str = "-10 minutes";
/// Unambiguous consonants (RFC 8628 section 6.1): no vowels, so no words, and
/// no characters that read alike.
const USER_CODE_ALPHABET: &[u8] = b"BCDFGHJKLMNPQRSTVWXZ";
const USER_CODE_LEN: usize = 8;
/// Audit actor of requests that come from an unauthenticated device.
const DEVICE_ACTOR: &str = "device-enrollment";
/// Audit actor of lazy expiry.
const SYSTEM_ACTOR: &str = "system";

#[derive(Debug, Clone, Deserialize)]
pub struct StartRequest {
    pub name: String,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub android_sdk: Option<i64>,
    #[serde(default)]
    pub client_version: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct Started {
    /// The device's secret. Only its SHA-256 is stored.
    pub device_code: String,
    /// What the device shows, for example `WDJB-MJHT`.
    pub user_code: String,
    pub verification_uri: String,
    pub verification_uri_complete: String,
    /// The JSON text for the QR code; it holds no secret.
    pub verification_payload: String,
    pub expires_in: i64,
    pub interval: i64,
}

#[derive(Debug)]
pub enum StartOutcome {
    Started(Started),
    /// A per-source or global quota is exhausted.
    RateLimited,
    /// A denial blocked this source for [`DENIAL_COOLDOWN_HOURS`].
    Blocked,
}

/// The `device-token.json` object a redeeming poll returns.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct DeviceTokenFile {
    pub version: u32,
    pub server_url: String,
    pub name: String,
    pub token: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PollOutcome {
    Pending,
    SlowDown,
    Expired,
    Denied,
    /// Unknown or already redeemed device code.
    Invalid,
    Issued(DeviceTokenFile),
}

impl PollOutcome {
    /// The RFC 8628 error code of a non-issuing outcome.
    pub fn error_code(&self) -> Option<&'static str> {
        match self {
            PollOutcome::Pending => Some("authorization_pending"),
            PollOutcome::SlowDown => Some("slow_down"),
            PollOutcome::Expired => Some("expired_token"),
            PollOutcome::Denied => Some("access_denied"),
            PollOutcome::Invalid => Some("invalid_grant"),
            PollOutcome::Issued(_) => None,
        }
    }
}

/// What an administrator sees before approving.
#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct PendingEnrollment {
    pub user_code: String,
    pub requested_name: String,
    pub model: Option<String>,
    pub android_sdk: Option<i64>,
    pub client_version: Option<String>,
    pub source_ip: String,
    pub created_at: String,
    pub expires_at: String,
    /// Seconds since the device started the enrollment.
    pub age_s: i64,
    /// Seconds until the code expires.
    pub expires_in: i64,
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ApproveRequest {
    /// The new token's name (default: the name the device asked for), or a
    /// new name for the rekeyed token (default: keep its name).
    #[serde(default)]
    pub name: Option<String>,
    /// Groups a new token joins. Absent means the system `all` group; an
    /// empty list means none. Not allowed with `replace_device_token_id`.
    #[serde(default)]
    pub group_ids: Option<Vec<i64>>,
    /// Rekey this existing token instead of creating one.
    #[serde(default)]
    pub replace_device_token_id: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct Approved {
    pub device_token: DeviceToken,
    pub rekeyed: bool,
}

/// Format a normalized code for display: `WDJBMJHT` → `WDJB-MJHT`.
pub fn format_user_code(code: &str) -> String {
    format!("{}-{}", &code[..4], &code[4..])
}

/// Accept any case, spaces and dashes; `None` when it cannot be a user code.
pub fn normalize_user_code(input: &str) -> Option<String> {
    let code: String = input
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| c.to_ascii_uppercase())
        .collect();
    (code.len() == USER_CODE_LEN && code.bytes().all(|b| USER_CODE_ALPHABET.contains(&b)))
        .then_some(code)
}

fn generate_user_code() -> Result<String, AppError> {
    let rng = SystemRandom::new();
    let limit = 256 - 256 % USER_CODE_ALPHABET.len();
    let mut code = String::with_capacity(USER_CODE_LEN);
    let mut byte = [0_u8; 1];
    while code.len() < USER_CODE_LEN {
        rng.fill(&mut byte)
            .map_err(|_| AppError::Internal("Secure random generator unavailable".into()))?;
        // Rejection sampling keeps every character equally likely.
        if (byte[0] as usize) < limit {
            code.push(USER_CODE_ALPHABET[byte[0] as usize % USER_CODE_ALPHABET.len()] as char);
        }
    }
    Ok(code)
}

fn optional_text(
    value: Option<String>,
    field: &str,
    max: usize,
) -> Result<Option<String>, AppError> {
    let Some(value) = value.map(|v| v.trim().to_owned()).filter(|v| !v.is_empty()) else {
        return Ok(None);
    };
    if value.chars().count() > max || value.chars().any(char::is_control) {
        return Err(AppError::BadRequest(format!(
            "{field} must contain at most {max} printable characters"
        )));
    }
    Ok(Some(value))
}

async fn begin(pool: &SqlitePool) -> Result<sqlx::Transaction<'static, sqlx::Sqlite>, AppError> {
    Ok(pool.begin_with("BEGIN IMMEDIATE").await?)
}

/// Expire what is past its time and forget what is old. Approvals that were
/// never redeemed revoke the token they prepared, since nobody holds its
/// secret. Rows are kept for a day after they end, so quotas, denials and
/// cooldowns can be evaluated.
async fn sweep(conn: &mut SqliteConnection) -> Result<(), AppError> {
    let stale: Vec<(String, Option<String>)> = sqlx::query_as(
        "UPDATE device_enrollments SET state = 'expired' WHERE state IN ('pending', 'approved') AND expires_at <= datetime('now') RETURNING id, device_token_id",
    )
    .fetch_all(&mut *conn)
    .await?;
    // Only an approval has a token; a pending row has none.
    for (id, token_id) in stale {
        if let Some(token_id) = &token_id {
            let revoked = sqlx::query(
                "UPDATE device_tokens SET revoked_at = datetime('now') WHERE id = ? AND revoked_at IS NULL",
            )
            .bind(token_id)
            .execute(&mut *conn)
            .await?
            .rows_affected();
            if revoked > 0 {
                super::admin::audit(
                    conn,
                    SYSTEM_ACTOR,
                    "device_token.revoke",
                    "device_token",
                    token_id,
                    json!({"reason": "enrollment_not_redeemed", "enrollment_id": id}),
                )
                .await?;
            }
        }
        super::admin::audit(
            conn,
            SYSTEM_ACTOR,
            "device_enrollment.expire",
            "device_enrollment",
            &id,
            json!({"approved": token_id.is_some()}),
        )
        .await?;
    }
    sqlx::query(
        "DELETE FROM device_enrollments WHERE state IN ('expired', 'redeemed', 'denied') AND expires_at < datetime('now', '-1 day') AND COALESCE(decided_at, created_at) < datetime('now', '-1 day')",
    )
    .execute(&mut *conn)
    .await?;
    Ok(())
}

/// Start an enrollment from `source_ip`. `server_url` is the store's public
/// origin, as the device reached it.
pub async fn start(
    pool: &SqlitePool,
    request: StartRequest,
    source_ip: &str,
    server_url: &str,
) -> Result<StartOutcome, AppError> {
    let name = validate_name(&request.name)?.to_owned();
    let model = optional_text(request.model, "model", 100)?;
    let client_version = optional_text(request.client_version, "client_version", 64)?;
    if let Some(sdk) = request.android_sdk {
        if !(1..=1000).contains(&sdk) {
            return Err(AppError::BadRequest("android_sdk is out of range".into()));
        }
    }
    let mut tx = begin(pool).await?;
    sweep(&mut tx).await?;
    let blocked: bool = sqlx::query_scalar(&format!(
        "SELECT EXISTS(SELECT 1 FROM device_enrollments WHERE source_ip = ? AND state = 'denied' AND blocks_source = 1 AND decided_at > datetime('now', '-{DENIAL_COOLDOWN_HOURS} hours'))"
    ))
    .bind(source_ip)
    .fetch_one(&mut *tx)
    .await?;
    if blocked {
        return Ok(StartOutcome::Blocked);
    }
    let (from_source, pending): (i64, i64) = sqlx::query_as(&format!(
        "SELECT (SELECT COUNT(*) FROM device_enrollments WHERE source_ip = ? AND created_at > datetime('now', '{QUOTA_WINDOW}')), (SELECT COUNT(*) FROM device_enrollments WHERE state = 'pending')"
    ))
    .bind(source_ip)
    .fetch_one(&mut *tx)
    .await?;
    if from_source >= MAX_PER_SOURCE || pending >= MAX_PENDING {
        tracing::warn!(
            source_ip,
            from_source,
            pending,
            "Device enrollment refused: quota exhausted"
        );
        return Ok(StartOutcome::RateLimited);
    }
    let id = uuid::Uuid::new_v4().to_string();
    let device_code = generate_secret(DEVICE_CODE_PREFIX)?;
    let mut user_code = generate_user_code()?;
    // 20^8 codes: a collision with one of at most a few hundred rows is rare,
    // but retry rather than fail.
    for _ in 0..5 {
        let taken: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM device_enrollments WHERE user_code = ?)",
        )
        .bind(&user_code)
        .fetch_one(&mut *tx)
        .await?;
        if !taken {
            break;
        }
        user_code = generate_user_code()?;
    }
    sqlx::query(&format!(
        "INSERT INTO device_enrollments (id, device_code_hash, user_code, requested_name, model, android_sdk, client_version, source_ip, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now', '+{EXPIRES_IN_SECS} seconds'))"
    ))
    .bind(&id)
    .bind(hash_secret(&device_code))
    .bind(&user_code)
    .bind(&name)
    .bind(&model)
    .bind(request.android_sdk)
    .bind(&client_version)
    .bind(source_ip)
    .execute(&mut *tx)
    .await?;
    super::admin::audit(
        &mut tx,
        DEVICE_ACTOR,
        "device_enrollment.create",
        "device_enrollment",
        &id,
        json!({"name": name, "model": model, "android_sdk": request.android_sdk, "client_version": client_version, "source_ip": source_ip}),
    )
    .await?;
    tx.commit().await?;
    let display = format_user_code(&user_code);
    let verification_uri = format!("{server_url}/admin/device-tokens/approve");
    Ok(StartOutcome::Started(Started {
        device_code,
        verification_uri_complete: format!("{verification_uri}?code={display}"),
        verification_uri,
        verification_payload: json!({
            "type": "device-enrollment",
            "version": 1,
            "server_url": server_url,
            "user_code": display,
        })
        .to_string(),
        user_code: display,
        expires_in: EXPIRES_IN_SECS,
        interval: POLL_INTERVAL_SECS,
    }))
}

#[derive(sqlx::FromRow)]
struct PollRow {
    id: String,
    state: String,
    expired: bool,
    too_soon: bool,
    device_token_id: Option<String>,
}

/// Poll with a device code. Approval is delivered at most once.
pub async fn poll(
    pool: &SqlitePool,
    device_code: &str,
    server_url: &str,
) -> Result<PollOutcome, AppError> {
    if !device_code.starts_with(DEVICE_CODE_PREFIX) {
        return Ok(PollOutcome::Invalid);
    }
    let mut tx = begin(pool).await?;
    sweep(&mut tx).await?;
    let row: Option<PollRow> = sqlx::query_as(
        "SELECT id, state, expires_at <= datetime('now') AS expired, last_polled_at IS NOT NULL AND (julianday('now') - julianday(last_polled_at)) * 86400.0 < ? AS too_soon, device_token_id FROM device_enrollments WHERE device_code_hash = ?",
    )
    .bind(SLOW_DOWN_BELOW_SECS)
    .bind(hash_secret(device_code))
    .fetch_optional(&mut *tx)
    .await?;
    let Some(row) = row else {
        return Ok(PollOutcome::Invalid);
    };
    let outcome = match row.state.as_str() {
        "denied" => PollOutcome::Denied,
        "redeemed" => PollOutcome::Invalid,
        "expired" => PollOutcome::Expired,
        _ if row.expired => PollOutcome::Expired,
        "pending" if row.too_soon => PollOutcome::SlowDown,
        "pending" => PollOutcome::Pending,
        "approved" => redeem(&mut tx, &row, server_url).await?,
        _ => PollOutcome::Invalid,
    };
    sqlx::query(
        "UPDATE device_enrollments SET last_polled_at = strftime('%Y-%m-%d %H:%M:%f', 'now') WHERE id = ?",
    )
    .bind(&row.id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(outcome)
}

async fn redeem(
    conn: &mut SqliteConnection,
    row: &PollRow,
    server_url: &str,
) -> Result<PollOutcome, AppError> {
    let Some(token_id) = &row.device_token_id else {
        return Ok(PollOutcome::Expired);
    };
    let won = sqlx::query(
        "UPDATE device_enrollments SET state = 'redeemed', redeemed_at = datetime('now') WHERE id = ? AND state = 'approved' AND expires_at > datetime('now')",
    )
    .bind(&row.id)
    .execute(&mut *conn)
    .await?
    .rows_affected();
    if won == 0 {
        return Ok(PollOutcome::Invalid);
    }
    let secret = generate_secret(device_tokens::SECRET_PREFIX)?;
    // A token revoked (or deleted) after approval is not handed out.
    let name: Option<String> = sqlx::query_scalar(
        "UPDATE device_tokens SET token_hash = ? WHERE id = ? AND revoked_at IS NULL RETURNING name",
    )
    .bind(hash_secret(&secret))
    .bind(token_id)
    .fetch_optional(&mut *conn)
    .await?;
    let Some(name) = name else {
        sqlx::query("UPDATE device_enrollments SET state = 'expired' WHERE id = ?")
            .bind(&row.id)
            .execute(&mut *conn)
            .await?;
        return Ok(PollOutcome::Expired);
    };
    super::admin::audit(
        conn,
        DEVICE_ACTOR,
        "device_enrollment.redeem",
        "device_enrollment",
        &row.id,
        json!({"device_token_id": token_id}),
    )
    .await?;
    tracing::info!(enrollment_id = %row.id, token_id = %token_id, "Device enrollment redeemed");
    Ok(PollOutcome::Issued(DeviceTokenFile {
        version: 1,
        server_url: server_url.to_owned(),
        name,
        token: secret,
    }))
}

/// Refuse an administrator who keeps presenting unknown codes, and record
/// each unknown code so the refusal can count them.
async fn check_lookup_quota(conn: &mut SqliteConnection, actor: &str) -> Result<(), AppError> {
    let failures: i64 = sqlx::query_scalar(&format!(
        "SELECT COUNT(*) FROM access_audit_log WHERE actor_subject = ? AND action = 'device_enrollment.lookup_failed' AND created_at > datetime('now', '{QUOTA_WINDOW}')"
    ))
    .bind(actor)
    .fetch_one(&mut *conn)
    .await?;
    if failures >= MAX_LOOKUP_FAILURES {
        return Err(AppError::TooManyRequests(
            "Too many unknown codes; wait a few minutes and try again".into(),
        ));
    }
    Ok(())
}

/// Find the pending, unexpired enrollment for `user_code` inside the
/// caller's transaction. An unknown code is recorded and counted against
/// the administrator; one that exists but is no longer pending is `409`.
async fn find_pending(
    conn: &mut SqliteConnection,
    actor: &str,
    user_code: &str,
) -> Result<(String, String), AppError> {
    check_lookup_quota(conn, actor).await?;
    let found: Option<(String, String, String)> = match normalize_user_code(user_code) {
        Some(code) => {
            sqlx::query_as(
                "SELECT id, state, requested_name FROM device_enrollments WHERE user_code = ?",
            )
            .bind(code)
            .fetch_optional(&mut *conn)
            .await?
        }
        None => None,
    };
    match found {
        Some((id, state, name)) if state == "pending" => Ok((id, name)),
        Some(_) => Err(AppError::Conflict(
            "This enrollment is no longer pending".into(),
        )),
        None => {
            super::admin::audit(
                conn,
                actor,
                "device_enrollment.lookup_failed",
                "device_enrollment",
                "",
                json!({}),
            )
            .await?;
            Err(AppError::NotFound("Unknown or expired code".into()))
        }
    }
}

/// Details of a pending enrollment for an administrator.
pub async fn lookup(
    pool: &SqlitePool,
    actor: &str,
    user_code: &str,
) -> Result<PendingEnrollment, AppError> {
    let mut tx = begin(pool).await?;
    sweep(&mut tx).await?;
    let result = find_pending(&mut tx, actor, user_code).await;
    // Commit the sweep and any failed-lookup record even when the lookup fails.
    let found = match result {
        Ok((id, _)) => id,
        Err(error) => {
            tx.commit().await?;
            return Err(match error {
                AppError::Conflict(_) => AppError::NotFound("Unknown or expired code".into()),
                other => other,
            });
        }
    };
    let pending = sqlx::query_as(
        "SELECT user_code, requested_name, model, android_sdk, client_version, source_ip, created_at, expires_at, CAST((julianday('now') - julianday(created_at)) * 86400 AS INTEGER) AS age_s, MAX(0, CAST((julianday(expires_at) - julianday('now')) * 86400 AS INTEGER)) AS expires_in FROM device_enrollments WHERE id = ?",
    )
    .bind(&found)
    .fetch_one(&mut *tx)
    .await
    .map(|mut row: PendingEnrollment| {
        row.user_code = format_user_code(&row.user_code);
        row
    })?;
    tx.commit().await?;
    Ok(pending)
}

/// Approve a pending enrollment: create a token, or rekey
/// `replace_device_token_id`, with an unusable secret that the device's next
/// poll replaces and receives.
pub async fn approve(
    pool: &SqlitePool,
    actor: &str,
    user_code: &str,
    request: ApproveRequest,
) -> Result<Approved, AppError> {
    let name = request.name.as_deref().map(validate_name).transpose()?;
    if request.replace_device_token_id.is_some() && request.group_ids.is_some() {
        return Err(AppError::BadRequest(
            "A rekeyed token keeps its groups; group_ids cannot be combined with replace_device_token_id".into(),
        ));
    }
    let mut tx = begin(pool).await?;
    sweep(&mut tx).await?;
    let (id, requested_name) = match find_pending(&mut tx, actor, user_code).await {
        Ok(found) => found,
        Err(error) => {
            tx.commit().await?;
            return Err(error);
        }
    };
    let placeholder = device_tokens::placeholder_hash()?;
    let rekeyed = request.replace_device_token_id.is_some();
    let token_id = match &request.replace_device_token_id {
        Some(token_id) => {
            device_tokens::rekey_in(
                &mut tx,
                actor,
                token_id,
                name,
                &placeholder,
                json!({"enrollment_id": id}),
            )
            .await?;
            token_id.clone()
        }
        None => {
            let group_ids = match request.group_ids {
                Some(group_ids) => group_ids,
                None => {
                    sqlx::query_scalar("SELECT id FROM app_groups WHERE system_kind = 'all'")
                        .fetch_all(&mut *tx)
                        .await?
                }
            };
            device_tokens::create_in(
                &mut tx,
                actor,
                name.unwrap_or(requested_name.as_str()),
                &None,
                &group_ids,
                &placeholder,
            )
            .await?
        }
    };
    let won = sqlx::query(&format!(
        "UPDATE device_enrollments SET state = 'approved', approved_by = ?, decided_at = datetime('now'), device_token_id = ?, rekeyed = ?, expires_at = datetime('now', '+{EXPIRES_IN_SECS} seconds') WHERE id = ? AND state = 'pending' AND expires_at > datetime('now')"
    ))
    .bind(actor)
    .bind(&token_id)
    .bind(rekeyed)
    .bind(&id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if won == 0 {
        return Err(AppError::Conflict(
            "This enrollment is no longer pending".into(),
        ));
    }
    super::admin::audit(
        &mut tx,
        actor,
        "device_enrollment.approve",
        "device_enrollment",
        &id,
        json!({"device_token_id": token_id, "rekeyed": rekeyed}),
    )
    .await?;
    tx.commit().await?;
    Ok(Approved {
        device_token: device_tokens::get(pool, &token_id).await?,
        rekeyed,
    })
}

/// Deny a pending enrollment. With `block_source`, new enrollments from the
/// same address are refused for [`DENIAL_COOLDOWN_HOURS`].
pub async fn deny(
    pool: &SqlitePool,
    actor: &str,
    user_code: &str,
    block_source: bool,
) -> Result<(), AppError> {
    let mut tx = begin(pool).await?;
    sweep(&mut tx).await?;
    let (id, _) = match find_pending(&mut tx, actor, user_code).await {
        Ok(found) => found,
        Err(error) => {
            tx.commit().await?;
            return Err(error);
        }
    };
    let won = sqlx::query(
        "UPDATE device_enrollments SET state = 'denied', approved_by = ?, decided_at = datetime('now'), blocks_source = ? WHERE id = ? AND state = 'pending'",
    )
    .bind(actor)
    .bind(block_source)
    .bind(&id)
    .execute(&mut *tx)
    .await?
    .rows_affected();
    if won == 0 {
        return Err(AppError::Conflict(
            "This enrollment is no longer pending".into(),
        ));
    }
    super::admin::audit(
        &mut tx,
        actor,
        "device_enrollment.deny",
        "device_enrollment",
        &id,
        json!({"block_source": block_source}),
    )
    .await?;
    tx.commit().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const SERVER: &str = "https://store.example.com";

    async fn pool() -> SqlitePool {
        let pool = sqlx::sqlite::SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        crate::db::run_migrations(&pool).await.unwrap();
        pool
    }

    fn request(name: &str) -> StartRequest {
        StartRequest {
            name: name.into(),
            model: Some("MiBOX4".into()),
            android_sdk: Some(28),
            client_version: Some("1.2.3".into()),
        }
    }

    async fn started(pool: &SqlitePool, ip: &str) -> Started {
        match start(pool, request("Living room TV"), ip, SERVER)
            .await
            .unwrap()
        {
            StartOutcome::Started(started) => started,
            other => panic!("not started: {other:?}"),
        }
    }

    /// Pretend the previous poll happened long enough ago.
    async fn wait_interval(pool: &SqlitePool) {
        sqlx::query(
            "UPDATE device_enrollments SET last_polled_at = datetime('now', '-10 seconds')",
        )
        .execute(pool)
        .await
        .unwrap();
    }

    async fn audits(pool: &SqlitePool, prefix: &str) -> Vec<String> {
        sqlx::query_scalar(
            "SELECT action FROM access_audit_log WHERE action LIKE ? || '%' ORDER BY id",
        )
        .bind(prefix)
        .fetch_all(pool)
        .await
        .unwrap()
    }

    async fn all_group(pool: &SqlitePool) -> i64 {
        sqlx::query_scalar("SELECT id FROM app_groups WHERE system_kind = 'all'")
            .fetch_one(pool)
            .await
            .unwrap()
    }

    #[test]
    fn user_codes_are_unambiguous_and_normalized() {
        for _ in 0..200 {
            let code = generate_user_code().unwrap();
            assert_eq!(code.len(), 8);
            assert!(code.bytes().all(|b| USER_CODE_ALPHABET.contains(&b)));
            assert_eq!(normalize_user_code(&format_user_code(&code)), Some(code));
        }
        assert_eq!(normalize_user_code(" wdjb-mjht "), Some("WDJBMJHT".into()));
        assert_eq!(normalize_user_code("WDJB MJHT"), Some("WDJBMJHT".into()));
        assert_eq!(normalize_user_code("WDJB-MJH"), None);
        assert_eq!(normalize_user_code("WDJB-MJHA"), None);
        assert_eq!(normalize_user_code("'; DROP--"), None);
        assert_eq!(format_user_code("WDJBMJHT"), "WDJB-MJHT");
    }

    #[tokio::test]
    async fn start_returns_rfc8628_fields_and_stores_only_hashes() {
        let pool = pool().await;
        let started = started(&pool, "198.51.100.7").await;
        assert!(started.device_code.starts_with(DEVICE_CODE_PREFIX));
        assert_eq!(started.device_code.len(), DEVICE_CODE_PREFIX.len() + 64);
        assert_eq!(started.user_code.len(), 9);
        assert_eq!(&started.user_code[4..5], "-");
        assert_eq!((started.expires_in, started.interval), (600, 5));
        assert_eq!(
            started.verification_uri,
            "https://store.example.com/admin/device-tokens/approve"
        );
        assert_eq!(
            started.verification_uri_complete,
            format!(
                "https://store.example.com/admin/device-tokens/approve?code={}",
                started.user_code
            )
        );
        let payload: serde_json::Value =
            serde_json::from_str(&started.verification_payload).unwrap();
        assert_eq!(
            payload,
            json!({"type": "device-enrollment", "version": 1, "server_url": SERVER, "user_code": started.user_code})
        );
        let (hash, code, state, ip, ttl): (String, String, String, String, i64) = sqlx::query_as(
            "SELECT device_code_hash, user_code, state, source_ip, CAST(round((julianday(expires_at) - julianday(created_at)) * 86400) AS INTEGER) FROM device_enrollments",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(hash, hash_secret(&started.device_code));
        assert_eq!(code, started.user_code.replace('-', ""));
        assert_eq!(
            (state.as_str(), ip.as_str(), ttl),
            ("pending", "198.51.100.7", 600)
        );
        assert_eq!(
            audits(&pool, "device_enrollment.").await,
            ["device_enrollment.create"]
        );
        let stored: String =
            sqlx::query_scalar("SELECT group_concat(details_json) FROM access_audit_log")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert!(!stored.contains(&started.device_code));
    }

    #[tokio::test]
    async fn start_validates_its_input() {
        let pool = pool().await;
        for bad in [
            StartRequest {
                name: " ".into(),
                ..request("x")
            },
            StartRequest {
                model: Some("m".repeat(101)),
                ..request("TV")
            },
            StartRequest {
                client_version: Some("1\n2".into()),
                ..request("TV")
            },
            StartRequest {
                android_sdk: Some(0),
                ..request("TV")
            },
        ] {
            assert!(matches!(
                start(&pool, bad, "198.51.100.7", SERVER).await,
                Err(AppError::BadRequest(_))
            ));
        }
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_enrollments")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(count, 0);
    }

    #[tokio::test]
    async fn approval_is_delivered_once_with_a_working_token_in_the_all_group() {
        let pool = pool().await;
        let started = started(&pool, "198.51.100.7").await;
        let code = &started.device_code;
        assert_eq!(
            poll(&pool, code, SERVER).await.unwrap(),
            PollOutcome::Pending
        );
        // Polling again at once is too fast.
        assert_eq!(
            poll(&pool, code, SERVER).await.unwrap(),
            PollOutcome::SlowDown
        );
        wait_interval(&pool).await;
        assert_eq!(
            poll(&pool, code, SERVER).await.unwrap(),
            PollOutcome::Pending
        );

        let details = lookup(&pool, "admin", &started.user_code.to_lowercase())
            .await
            .unwrap();
        assert_eq!(details.user_code, started.user_code);
        assert_eq!(details.requested_name, "Living room TV");
        assert_eq!(details.model.as_deref(), Some("MiBOX4"));
        assert_eq!(details.android_sdk, Some(28));
        assert_eq!(details.client_version.as_deref(), Some("1.2.3"));
        assert_eq!(details.source_ip, "198.51.100.7");
        assert!(details.expires_in > 590 && details.expires_in <= 600);
        assert!(details.age_s >= 0 && details.age_s < 10);

        let approved = approve(
            &pool,
            "admin",
            &started.user_code,
            ApproveRequest::default(),
        )
        .await
        .unwrap();
        assert!(!approved.rekeyed);
        let token = &approved.device_token;
        assert_eq!(token.name, "Living room TV");
        assert_eq!(token.created_by, "admin");
        assert_eq!(
            token.groups.iter().map(|g| g.id).collect::<Vec<_>>(),
            [all_group(&pool).await]
        );
        // Approval no longer looks up as pending, and cannot be repeated or denied.
        assert!(matches!(
            lookup(&pool, "admin", &started.user_code).await,
            Err(AppError::NotFound(_))
        ));
        assert!(matches!(
            approve(
                &pool,
                "admin",
                &started.user_code,
                ApproveRequest::default()
            )
            .await,
            Err(AppError::Conflict(_))
        ));
        assert!(matches!(
            deny(&pool, "admin", &started.user_code, true).await,
            Err(AppError::Conflict(_))
        ));

        // An approval is delivered even to a fast poll, exactly once.
        let PollOutcome::Issued(file) = poll(&pool, code, SERVER).await.unwrap() else {
            panic!("not issued");
        };
        assert_eq!(file.version, 1);
        assert_eq!(file.server_url, SERVER);
        assert_eq!(file.name, "Living room TV");
        assert!(file.token.starts_with(device_tokens::SECRET_PREFIX));
        assert_eq!(
            serde_json::to_value(&file)
                .unwrap()
                .as_object()
                .unwrap()
                .keys()
                .collect::<Vec<_>>(),
            ["name", "server_url", "token", "version"]
        );
        let resolved = device_tokens::authenticate(&pool, &file.token)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(resolved.id, token.id);
        wait_interval(&pool).await;
        assert_eq!(
            poll(&pool, code, SERVER).await.unwrap(),
            PollOutcome::Invalid
        );
        assert_eq!(
            audits(&pool, "device_enrollment.").await,
            [
                "device_enrollment.create",
                "device_enrollment.approve",
                "device_enrollment.redeem"
            ]
        );
        let stored: String =
            sqlx::query_scalar("SELECT group_concat(details_json) FROM access_audit_log")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert!(!stored.contains(&file.token));
    }

    #[tokio::test]
    async fn approval_sets_name_and_explicit_groups() {
        let pool = pool().await;
        let tv = crate::db::admin::create_group(&pool, "admin", "TV")
            .await
            .unwrap()
            .id;
        let started = started(&pool, "198.51.100.7").await;
        let approved = approve(
            &pool,
            "admin",
            &started.user_code,
            ApproveRequest {
                name: Some(" Bedroom box ".into()),
                group_ids: Some(vec![tv]),
                replace_device_token_id: None,
            },
        )
        .await
        .unwrap();
        assert_eq!(approved.device_token.name, "Bedroom box");
        assert_eq!(
            approved
                .device_token
                .groups
                .iter()
                .map(|g| g.name.as_str())
                .collect::<Vec<_>>(),
            ["TV"]
        );
        let other = started_named(&pool, "Kitchen").await;
        let approved = approve(
            &pool,
            "admin",
            &other.user_code,
            ApproveRequest {
                group_ids: Some(vec![]),
                ..Default::default()
            },
        )
        .await
        .unwrap();
        assert!(approved.device_token.groups.is_empty());
        // An unknown group approves nothing.
        let third = started_named(&pool, "Garage").await;
        assert!(matches!(
            approve(
                &pool,
                "admin",
                &third.user_code,
                ApproveRequest {
                    group_ids: Some(vec![9999]),
                    ..Default::default()
                },
            )
            .await,
            Err(AppError::NotFound(_))
        ));
        assert!(lookup(&pool, "admin", &third.user_code).await.is_ok());
        assert!(matches!(
            approve(
                &pool,
                "admin",
                &third.user_code,
                ApproveRequest {
                    name: Some(" ".into()),
                    ..Default::default()
                },
            )
            .await,
            Err(AppError::BadRequest(_))
        ));
    }

    async fn started_named(pool: &SqlitePool, name: &str) -> Started {
        match start(pool, request(name), "198.51.100.8", SERVER)
            .await
            .unwrap()
        {
            StartOutcome::Started(started) => started,
            other => panic!("not started: {other:?}"),
        }
    }

    #[tokio::test]
    async fn rekey_keeps_the_principal_and_invalidates_the_old_secret() {
        let pool = pool().await;
        let tv = crate::db::admin::create_group(&pool, "admin", "TV")
            .await
            .unwrap()
            .id;
        let old = device_tokens::create(&pool, "admin", "Old box", None, &[tv])
            .await
            .unwrap();
        sqlx::query("INSERT INTO apps (package_name, name) VALUES ('com.example.tv', 'TV')")
            .execute(&pool)
            .await
            .unwrap();
        crate::db::admin::set_direct_grant(
            &pool,
            "admin",
            &old.token.subject,
            "com.example.tv",
            crate::db::access::AppAccessLevel::Stable,
        )
        .await
        .unwrap();
        device_tokens::revoke(&pool, "admin", &old.token.id)
            .await
            .unwrap();
        let started = started(&pool, "198.51.100.7").await;
        assert!(matches!(
            approve(
                &pool,
                "admin",
                &started.user_code,
                ApproveRequest {
                    replace_device_token_id: Some(old.token.id.clone()),
                    group_ids: Some(vec![tv]),
                    ..Default::default()
                },
            )
            .await,
            Err(AppError::BadRequest(_))
        ));
        assert!(matches!(
            approve(
                &pool,
                "admin",
                &started.user_code,
                ApproveRequest {
                    replace_device_token_id: Some("missing".into()),
                    ..Default::default()
                },
            )
            .await,
            Err(AppError::NotFound(_))
        ));
        let approved = approve(
            &pool,
            "admin",
            &started.user_code,
            ApproveRequest {
                replace_device_token_id: Some(old.token.id.clone()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
        assert!(approved.rekeyed);
        assert_eq!(approved.device_token.id, old.token.id);
        assert_eq!(approved.device_token.name, "Old box");
        assert!(approved.device_token.revoked_at.is_none());
        assert_eq!(approved.device_token.groups, old.token.groups);
        let PollOutcome::Issued(file) = poll(&pool, &started.device_code, SERVER).await.unwrap()
        else {
            panic!("not issued");
        };
        assert_eq!(file.name, "Old box");
        assert!(device_tokens::authenticate(&pool, &old.secret)
            .await
            .unwrap()
            .is_none());
        let resolved = device_tokens::authenticate(&pool, &file.token)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(resolved.id, old.token.id);
        let access = crate::db::admin::get_user_access(&pool, &old.token.subject)
            .await
            .unwrap();
        assert_eq!(access.direct_grants.len(), 1);
        assert_eq!(access.groups.len(), 1);
        let tokens: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM device_tokens")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(tokens, 1);
        assert!(audits(&pool, "device_token.")
            .await
            .contains(&"device_token.rekey".to_string()));

        // A rekey can also rename.
        let again = started_named(&pool, "Reset box").await;
        let approved = approve(
            &pool,
            "admin",
            &again.user_code,
            ApproveRequest {
                name: Some("New box".into()),
                replace_device_token_id: Some(old.token.id.clone()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
        assert_eq!(approved.device_token.name, "New box");
        // Approval alone already locks out the previous secret.
        assert!(device_tokens::authenticate(&pool, &file.token)
            .await
            .unwrap()
            .is_none());
    }

    #[tokio::test]
    async fn denial_answers_access_denied_and_can_block_the_source() {
        let pool = pool().await;
        let first = started(&pool, "198.51.100.7").await;
        deny(&pool, "admin", &first.user_code, false).await.unwrap();
        assert_eq!(
            poll(&pool, &first.device_code, SERVER).await.unwrap(),
            PollOutcome::Denied
        );
        assert!(matches!(
            approve(&pool, "admin", &first.user_code, ApproveRequest::default()).await,
            Err(AppError::Conflict(_))
        ));
        // Without blocking, the same address may try again.
        let second = started(&pool, "198.51.100.7").await;
        deny(&pool, "admin", &second.user_code, true).await.unwrap();
        assert!(matches!(
            start(&pool, request("TV"), "198.51.100.7", SERVER)
                .await
                .unwrap(),
            StartOutcome::Blocked
        ));
        assert!(matches!(
            start(&pool, request("TV"), "198.51.100.99", SERVER)
                .await
                .unwrap(),
            StartOutcome::Started(_)
        ));
        // Even after the code expires, the denial is still reported.
        sqlx::query("UPDATE device_enrollments SET expires_at = datetime('now', '-1 minute') WHERE state = 'denied'")
            .execute(&pool)
            .await
            .unwrap();
        wait_interval(&pool).await;
        assert_eq!(
            poll(&pool, &second.device_code, SERVER).await.unwrap(),
            PollOutcome::Denied
        );
        // The block lasts 24 hours.
        sqlx::query("UPDATE device_enrollments SET decided_at = datetime('now', '-25 hours') WHERE state = 'denied'")
            .execute(&pool)
            .await
            .unwrap();
        assert!(matches!(
            start(&pool, request("TV"), "198.51.100.7", SERVER)
                .await
                .unwrap(),
            StartOutcome::Started(_)
        ));
        assert_eq!(audits(&pool, "device_enrollment.deny").await.len(), 2);
    }

    #[tokio::test]
    async fn pending_codes_expire() {
        let pool = pool().await;
        let started = started(&pool, "198.51.100.7").await;
        sqlx::query("UPDATE device_enrollments SET expires_at = datetime('now', '-1 second')")
            .execute(&pool)
            .await
            .unwrap();
        assert_eq!(
            poll(&pool, &started.device_code, SERVER).await.unwrap(),
            PollOutcome::Expired
        );
        assert!(matches!(
            lookup(&pool, "admin", &started.user_code).await,
            Err(AppError::NotFound(_))
        ));
        assert!(matches!(
            approve(
                &pool,
                "admin",
                &started.user_code,
                ApproveRequest::default()
            )
            .await,
            Err(AppError::Conflict(_))
        ));
        let state: String = sqlx::query_scalar("SELECT state FROM device_enrollments")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(state, "expired");
        assert_eq!(audits(&pool, "device_enrollment.expire").await.len(), 1);
        // A day later the row is forgotten and the code is simply unknown.
        sqlx::query("UPDATE device_enrollments SET created_at = datetime('now', '-2 days'), expires_at = datetime('now', '-2 days')")
            .execute(&pool)
            .await
            .unwrap();
        assert_eq!(
            poll(&pool, &started.device_code, SERVER).await.unwrap(),
            PollOutcome::Invalid
        );
    }

    #[tokio::test]
    async fn an_unredeemed_approval_expires_and_revokes_its_token() {
        let pool = pool().await;
        let started = started(&pool, "198.51.100.7").await;
        let approved = approve(
            &pool,
            "admin",
            &started.user_code,
            ApproveRequest::default(),
        )
        .await
        .unwrap();
        // The approval gives the device another 10 minutes to collect it.
        let ttl: i64 = sqlx::query_scalar(
            "SELECT CAST(round((julianday(expires_at) - julianday(decided_at)) * 86400) AS INTEGER) FROM device_enrollments",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(ttl, 600);
        sqlx::query("UPDATE device_enrollments SET expires_at = datetime('now', '-1 second')")
            .execute(&pool)
            .await
            .unwrap();
        assert_eq!(
            poll(&pool, &started.device_code, SERVER).await.unwrap(),
            PollOutcome::Expired
        );
        let token = device_tokens::get(&pool, &approved.device_token.id)
            .await
            .unwrap();
        assert!(token.revoked_at.is_some());
    }

    #[tokio::test]
    async fn a_token_revoked_or_deleted_before_redemption_is_not_delivered() {
        let pool = pool().await;
        let started = started(&pool, "198.51.100.7").await;
        let approved = approve(
            &pool,
            "admin",
            &started.user_code,
            ApproveRequest::default(),
        )
        .await
        .unwrap();
        device_tokens::revoke(&pool, "admin", &approved.device_token.id)
            .await
            .unwrap();
        assert_eq!(
            poll(&pool, &started.device_code, SERVER).await.unwrap(),
            PollOutcome::Expired
        );
        let other = started_named(&pool, "Other").await;
        let approved = approve(&pool, "admin", &other.user_code, ApproveRequest::default())
            .await
            .unwrap();
        device_tokens::revoke(&pool, "admin", &approved.device_token.id)
            .await
            .unwrap();
        device_tokens::purge(&pool, "admin", &approved.device_token.id)
            .await
            .unwrap();
        assert_eq!(
            poll(&pool, &other.device_code, SERVER).await.unwrap(),
            PollOutcome::Expired
        );
    }

    #[tokio::test]
    async fn unknown_device_codes_are_invalid_grants() {
        let pool = pool().await;
        for code in ["", "nope", "lellostore_enr_unknown", "lellostore_dev_x"] {
            assert_eq!(
                poll(&pool, code, SERVER).await.unwrap(),
                PollOutcome::Invalid
            );
        }
        assert_eq!(
            PollOutcome::Pending.error_code(),
            Some("authorization_pending")
        );
        assert_eq!(PollOutcome::SlowDown.error_code(), Some("slow_down"));
        assert_eq!(PollOutcome::Expired.error_code(), Some("expired_token"));
        assert_eq!(PollOutcome::Denied.error_code(), Some("access_denied"));
        assert_eq!(PollOutcome::Invalid.error_code(), Some("invalid_grant"));
    }

    #[tokio::test]
    async fn creation_is_quota_limited_per_source_and_globally() {
        let pool = pool().await;
        for _ in 0..MAX_PER_SOURCE {
            started(&pool, "198.51.100.7").await;
        }
        assert!(matches!(
            start(&pool, request("TV"), "198.51.100.7", SERVER)
                .await
                .unwrap(),
            StartOutcome::RateLimited
        ));
        // The window slides.
        sqlx::query("UPDATE device_enrollments SET created_at = datetime('now', '-11 minutes')")
            .execute(&pool)
            .await
            .unwrap();
        started(&pool, "198.51.100.7").await;
        // At most MAX_PENDING pending codes exist at once, whatever their source.
        let pending: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM device_enrollments WHERE state = 'pending'")
                .fetch_one(&pool)
                .await
                .unwrap();
        for i in pending..MAX_PENDING {
            started(&pool, &format!("10.0.{}.{}", i / 250, i % 250)).await;
        }
        assert!(matches!(
            start(&pool, request("TV"), "192.0.2.1", SERVER)
                .await
                .unwrap(),
            StartOutcome::RateLimited
        ));
    }

    #[tokio::test]
    async fn unknown_user_codes_are_limited_per_administrator() {
        let pool = pool().await;
        let started = started(&pool, "198.51.100.7").await;
        for i in 0..MAX_LOOKUP_FAILURES {
            let guess = if i % 2 == 0 {
                "BBBB-BBBB"
            } else {
                "not a code"
            };
            assert!(matches!(
                lookup(&pool, "admin", guess).await,
                Err(AppError::NotFound(_))
            ));
        }
        // Even the right code is refused now, for this administrator only.
        assert!(matches!(
            lookup(&pool, "admin", &started.user_code).await,
            Err(AppError::TooManyRequests(_))
        ));
        assert!(matches!(
            approve(
                &pool,
                "admin",
                &started.user_code,
                ApproveRequest::default()
            )
            .await,
            Err(AppError::TooManyRequests(_))
        ));
        assert!(lookup(&pool, "other-admin", &started.user_code)
            .await
            .is_ok());
        sqlx::query("UPDATE access_audit_log SET created_at = datetime('now', '-11 minutes')")
            .execute(&pool)
            .await
            .unwrap();
        assert!(lookup(&pool, "admin", &started.user_code).await.is_ok());
    }
}
