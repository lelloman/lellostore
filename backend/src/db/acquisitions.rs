use serde::{Deserialize, Serialize};
use sqlx::{SqliteConnection, SqlitePool};

use crate::error::AppError;

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AcquisitionPurpose {
    Install,
    Update,
    Repair,
}

impl AcquisitionPurpose {
    pub(crate) fn as_str(&self) -> &'static str {
        match self {
            Self::Install => "install",
            Self::Update => "update",
            Self::Repair => "repair",
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AcquisitionRequest {
    pub version_code: i64,
    pub purpose: AcquisitionPurpose,
    pub idempotency_key: String,
}

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct Acquisition {
    pub id: String,
    pub package_name: String,
    pub version_code: i64,
    pub purpose: String,
    pub size: i64,
    pub sha256: String,
    pub created_at: i64,
    pub expires_at: i64,
    #[serde(skip_serializing)]
    pub user_subject: String,
    #[serde(skip_serializing)]
    pub apk_path: String,
}

pub(crate) async fn authorize(
    conn: &mut SqliteConnection,
    subject: &str,
    package: &str,
    code: i64,
) -> Result<(), AppError> {
    // The entitlement rules match the catalog, including the protected all group.
    // Query under the same transaction as acquisition creation to avoid access races.
    let allowed: bool = sqlx::query_scalar(r#"
        SELECT EXISTS (
          SELECT 1 FROM app_versions v WHERE v.package_name = ? AND v.version_code = ? AND (
            EXISTS (SELECT 1 FROM user_app_grants g WHERE g.user_subject = ? AND g.package_name = v.package_name AND (g.access_level = 'beta' OR v.is_beta = 0))
            OR EXISTS (SELECT 1 FROM user_app_group_memberships m JOIN app_groups ag ON ag.id = m.group_id
                LEFT JOIN app_group_grants g ON g.group_id = m.group_id AND g.package_name = v.package_name
                WHERE m.user_subject = ? AND (ag.system_kind = 'all' OR g.access_level = 'beta' OR (g.access_level = 'stable' AND v.is_beta = 0)))
          )
        )"#).bind(package).bind(code).bind(subject).bind(subject).fetch_one(conn).await?;
    if !allowed {
        return Err(AppError::NotFound("Acquisition unavailable".into()));
    }
    Ok(())
}

pub async fn create(
    pool: &SqlitePool,
    subject: &str,
    package: &str,
    request: &AcquisitionRequest,
) -> Result<Acquisition, AppError> {
    if request.idempotency_key.is_empty()
        || request.idempotency_key.len() > 128
        || !request
            .idempotency_key
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"_-".contains(&c))
    {
        return Err(AppError::BadRequest("Use an opaque alphanumeric idempotency key of 1–128 characters (hyphens and underscores allowed)".into()));
    }
    let now = chrono::Utc::now().timestamp();
    let mut tx = pool.begin().await?;
    // Serialize concurrent retries without changing catalog/publication revisions.
    sqlx::query(
        "UPDATE apps SET publication_revision = publication_revision WHERE package_name = ?",
    )
    .bind(package)
    .execute(&mut *tx)
    .await?;
    authorize(&mut tx, subject, package, request.version_code).await?;
    if let Some(existing) = sqlx::query_as::<_, Acquisition>(
        "SELECT * FROM acquisitions WHERE user_subject = ? AND idempotency_key = ?",
    )
    .bind(subject)
    .bind(&request.idempotency_key)
    .fetch_optional(&mut *tx)
    .await?
    {
        if existing.package_name != package
            || existing.version_code != request.version_code
            || existing.purpose != request.purpose.as_str()
        {
            return Err(AppError::Conflict(
                "Idempotency key was used for a different acquisition".into(),
            ));
        }
        if existing.expires_at <= now {
            return Err(AppError::Conflict(
                "Acquisition expired; start a new acquisition with a new idempotency key".into(),
            ));
        }
        return Ok(existing);
    }
    let version = sqlx::query_as::<_, super::models::AppVersion>("SELECT * FROM app_versions WHERE package_name = ? AND version_code = ? AND publication_state = 'published'")
        .bind(package).bind(request.version_code).fetch_optional(&mut *tx).await?
        .ok_or_else(|| AppError::NotFound("Published installer not found".into()))?;
    if version.distribution_mode == "paravoid" {
        let public: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM paravoid_contracts c JOIN paravoid_installers i USING(package_name,contract_id) WHERE i.package_name = ? AND i.installer_version = ? AND verification_state = 'verified' AND authentication = 'public')")
            .bind(package).bind(request.version_code).fetch_one(&mut *tx).await?;
        if !public {
            return Err(AppError::Conflict(
                "This shell requires verified personalization".into(),
            ));
        }
    } else if request.purpose == AcquisitionPurpose::Repair {
        return Err(AppError::BadRequest(
            "Access repair requires a Paravoid shell".into(),
        ));
    }
    let reserved: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM personalization_jobs WHERE user_subject = ? AND idempotency_key = ?)").bind(subject).bind(&request.idempotency_key).fetch_one(&mut *tx).await?;
    if reserved {
        return Err(AppError::Conflict(
            "Idempotency key is reserved for a personalized acquisition".into(),
        ));
    }
    let acquisition = Acquisition {
        id: uuid::Uuid::new_v4().to_string(),
        package_name: package.into(),
        version_code: version.version_code,
        purpose: request.purpose.as_str().into(),
        size: version.size,
        sha256: version.sha256,
        created_at: now,
        expires_at: now + 24 * 60 * 60,
        user_subject: subject.into(),
        apk_path: version.apk_path,
    };
    sqlx::query("INSERT INTO acquisitions(id, user_subject, idempotency_key, package_name, version_code, purpose, apk_path, size, sha256, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .bind(&acquisition.id).bind(subject).bind(&request.idempotency_key).bind(package).bind(acquisition.version_code)
        .bind(&acquisition.purpose).bind(&acquisition.apk_path).bind(acquisition.size).bind(&acquisition.sha256)
        .bind(now).bind(acquisition.expires_at).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(acquisition)
}

pub async fn get(pool: &SqlitePool, subject: &str, id: &str) -> Result<Acquisition, AppError> {
    let mut tx = pool.begin().await?;
    let acquisition = sqlx::query_as::<_, Acquisition>(
        "SELECT * FROM acquisitions WHERE id = ? AND user_subject = ? AND expires_at > ?",
    )
    .bind(id)
    .bind(subject)
    .bind(chrono::Utc::now().timestamp())
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| AppError::NotFound("Acquisition unavailable or expired".into()))?;
    authorize(
        &mut tx,
        subject,
        &acquisition.package_name,
        acquisition.version_code,
    )
    .await?;
    Ok(acquisition)
}
