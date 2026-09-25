use crate::{
    error::AppError,
    paravoid::{Authentication, InstalledPolicy, TrustPolicy, MAX_INTEGER},
};
use serde::Serialize;
use sqlx::{SqliteConnection, SqlitePool};

#[derive(Clone, Serialize, sqlx::FromRow)]
pub struct Contract {
    pub package_name: String,
    pub contract_id: String,
    pub installer_version: i64,
    pub channel: String,
    pub authentication: String,
    pub bootstrap: String,
    pub base_url: String,
    #[serde(skip_serializing)]
    pub trust_json: String,
    #[serde(skip_serializing)]
    pub descriptor_json: String,
    pub verification_state: String,
    pub validation_report: String,
    pub created_at: String,
}
impl Contract {
    pub fn shell_policy(
        &self,
    ) -> Result<crate::paravoid::shell_policy::ShellPolicyDocument, AppError> {
        let descriptor = crate::paravoid::parse_json(
            self.descriptor_json.as_bytes(),
            crate::paravoid::MAX_RELEASE_BYTES,
        )
        .map_err(|e| AppError::Conflict(e.to_string()))?;
        let version = if descriptor["distribution"].get("updates").is_some() {
            2
        } else {
            1
        };
        let envelope = serde_json::json!({"version":version,"contractId":self.contract_id,"descriptor":descriptor});
        let policy = crate::paravoid::shell_policy::ShellPolicyDocument::parse(
            &serde_json::to_vec(&envelope).unwrap(),
        )
        .map_err(|e| AppError::Conflict(e.to_string()))?;
        let d = &policy.descriptor.distribution;
        if policy.trust.application_id() != self.package_name
            || d.channel != self.channel
            || d.authentication != self.authentication
            || d.bootstrap != self.bootstrap
            || d.base_url != self.base_url
            || !d.enabled
            || policy.descriptor.trust_policy
                != serde_json::from_str::<serde_json::Value>(&self.trust_json)
                    .map_err(|_| AppError::Conflict("Invalid registered trust".into()))?
        {
            return Err(AppError::Conflict(
                "Registered shell fields differ from pinned policy".into(),
            ));
        }
        Ok(policy)
    }
    pub fn policy(&self) -> Result<InstalledPolicy, AppError> {
        let trust = TrustPolicy::parse(self.trust_json.as_bytes())
            .map_err(|_| AppError::Conflict("Invalid pinned trust policy".into()))?;
        if trust.application_id() != self.package_name {
            return Err(AppError::Conflict("Pinned app identity mismatch".into()));
        }
        InstalledPolicy::new(
            trust,
            self.contract_id.clone(),
            self.base_url.clone(),
            self.channel.clone(),
            if self.authentication == "apkKey" {
                Authentication::ApkKey
            } else {
                Authentication::Public
            },
        )
        .map_err(|_| AppError::Conflict("Invalid installed distribution policy".into()))
    }
}

#[cfg(test)]
mod shell_policy_tests {
    use super::Contract;
    use serde_json::json;
    use sha2::{Digest, Sha256};

    #[test]
    fn reopens_registered_version_two_shell_policy() {
        let trust: serde_json::Value = serde_json::from_str(include_str!(
            "../../tests/fixtures/paravoid-metadata/trust.json"
        ))
        .unwrap();
        let application_id = trust["applicationId"].as_str().unwrap();
        let descriptor = json!({
            "profile": "complete-apk-v1",
            "runtimeAbi": 1,
            "trustPolicy": trust,
            "installed": {
                "applicationId": application_id,
                "minSdk": 30,
                "manifestSha256": "b".repeat(64),
                "declarations": {"application": "example.App"},
                "pinnedResources": {},
                "runtimeClasses": {},
                "nativeAbis": {},
                "ledgerReservations": {},
                "apkSigners": ["e".repeat(64)],
                "toolchain": {}
            },
            "distribution": {
                "bootstrap": "embedded",
                "enabled": true,
                "baseUrl": "https://updates.example.test/",
                "channel": "stable",
                "authentication": "apkKey",
                "debugHttpAllowed": false,
                "updates": {"mode": "api", "pushEnabled": "true", "pushWebSocketUrl": "wss://updates.example.test/v1/events"}
            }
        });
        let contract = Contract {
            package_name: application_id.to_string(),
            contract_id: hex::encode(Sha256::digest(
                crate::paravoid::canonical_json(&descriptor).unwrap(),
            )),
            installer_version: 8,
            channel: "stable".to_string(),
            authentication: "apkKey".to_string(),
            bootstrap: "embedded".to_string(),
            base_url: "https://updates.example.test/".to_string(),
            trust_json: descriptor["trustPolicy"].to_string(),
            descriptor_json: descriptor.to_string(),
            verification_state: "verified".to_string(),
            validation_report: String::new(),
            created_at: String::new(),
        };
        assert_eq!(
            contract.shell_policy().unwrap().contract_id,
            contract.contract_id
        );
    }
}
#[derive(Clone, Serialize, sqlx::FromRow)]
pub struct VpkRelease {
    pub id: String,
    pub package_name: String,
    pub contract_id: String,
    pub release_id: String,
    pub payload_version: i64,
    #[serde(skip_serializing)]
    pub archive_path: String,
    pub archive_size: i64,
    pub archive_sha256: String,
    pub manifest_sha256: String,
    pub manifest_json: String,
    pub min_sdk: i64,
    pub max_sdk: i64,
    pub abis_json: String,
    pub signing_key_id: String,
    pub validation_state: String,
    pub validation_report: String,
    pub publication_state: String,
    pub release_notes: String,
    pub created_at: String,
    pub published_at: Option<String>,
}
#[derive(Clone, Serialize, sqlx::FromRow)]
pub struct Stream {
    pub package_name: String,
    pub contract_id: String,
    pub revision: i64,
    pub status: String,
    pub issued_at: i64,
    pub expires_at: i64,
}
#[derive(Serialize, sqlx::FromRow)]
pub struct Grant {
    pub id: String,
    pub key_id: String,
    pub package_name: String,
    pub contract_id: String,
    pub installer_version: i64,
    pub user_subject: String,
    pub acquisition_id: String,
    pub issued_at: i64,
    pub expires_at: i64,
    pub revoked_at: Option<i64>,
    pub revoked_by: Option<String>,
    pub last_used_at: Option<i64>,
    pub request_count: i64,
}
#[derive(Serialize, sqlx::FromRow)]
pub struct Event {
    pub id: i64,
    pub package_name: String,
    pub contract_id: String,
    pub release_id: Option<String>,
    pub grant_id: Option<String>,
    pub action: String,
    pub actor_subject: String,
    pub revision: i64,
    pub created_at: String,
}
pub async fn contract(pool: &SqlitePool, package: &str, id: &str) -> Result<Contract, AppError> {
    sqlx::query_as("SELECT * FROM paravoid_contracts WHERE package_name = ? AND contract_id = ?")
        .bind(package)
        .bind(id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| AppError::NotFound("Shell contract not found".into()))
}
pub async fn release(pool: &SqlitePool, package: &str, id: &str) -> Result<VpkRelease, AppError> {
    sqlx::query_as("SELECT * FROM vpk_releases WHERE package_name = ? AND id = ?")
        .bind(package)
        .bind(id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| AppError::NotFound("Payload release not found".into()))
}
pub async fn contracts(pool: &SqlitePool, package: &str) -> Result<Vec<Contract>, AppError> {
    Ok(sqlx::query_as(
        "SELECT * FROM paravoid_contracts WHERE package_name = ? ORDER BY installer_version DESC",
    )
    .bind(package)
    .fetch_all(pool)
    .await?)
}
pub async fn releases(pool: &SqlitePool, package: &str) -> Result<Vec<VpkRelease>, AppError> {
    Ok(sqlx::query_as(
        "SELECT * FROM vpk_releases WHERE package_name = ? ORDER BY payload_version DESC",
    )
    .bind(package)
    .fetch_all(pool)
    .await?)
}
pub async fn lock_app(
    conn: &mut SqliteConnection,
    package: &str,
    revision: i64,
) -> Result<(), AppError> {
    if revision < 0 || revision as u64 >= MAX_INTEGER {
        return Err(AppError::Conflict(
            "Invalid or exhausted publication revision".into(),
        ));
    }
    let changed = sqlx::query("UPDATE apps SET publication_revision = publication_revision + 1 WHERE package_name = ? AND publication_revision = ?")
        .bind(package).bind(revision).execute(conn).await?.rows_affected();
    if changed != 1 {
        return Err(AppError::Conflict(
            "Publication changed. Refresh the review.".into(),
        ));
    }
    Ok(())
}
async fn invalidate_stream(
    conn: &mut SqliteConnection,
    package: &str,
    contract: &str,
) -> Result<(), AppError> {
    sqlx::query("INSERT OR IGNORE INTO paravoid_streams(package_name,contract_id) VALUES (?,?)")
        .bind(package)
        .bind(contract)
        .execute(&mut *conn)
        .await?;
    let updated = sqlx::query("UPDATE paravoid_streams SET revision = revision + 1, expires_at = 0 WHERE package_name = ? AND contract_id = ? AND revision < ?")
        .bind(package).bind(contract).bind(MAX_INTEGER as i64).execute(conn).await?.rows_affected();
    if updated != 1 {
        return Err(AppError::Conflict("Stream revision exhausted".into()));
    }
    Ok(())
}
async fn event(
    conn: &mut SqliteConnection,
    package: &str,
    contract: &str,
    release: Option<&str>,
    action: &str,
    actor: &str,
    revision: i64,
) -> Result<(), AppError> {
    sqlx::query("INSERT INTO paravoid_events(package_name,contract_id,release_id,action,actor_subject,revision) VALUES (?,?,?,?,?,?)")
        .bind(package).bind(contract).bind(release).bind(action).bind(actor).bind(revision).execute(conn).await?;
    Ok(())
}
pub async fn publish(
    pool: &SqlitePool,
    package: &str,
    id: &str,
    actor: &str,
    revision: i64,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await?;
    lock_app(&mut tx, package, revision).await?;
    publish_tx(&mut tx, package, id, actor, revision).await?;
    tx.commit().await?;
    Ok(())
}
pub(crate) async fn publish_tx(
    conn: &mut SqliteConnection,
    package: &str,
    id: &str,
    actor: &str,
    revision: i64,
) -> Result<(), AppError> {
    let release: VpkRelease =
        sqlx::query_as("SELECT * FROM vpk_releases WHERE package_name = ? AND id = ?")
            .bind(package)
            .bind(id)
            .fetch_optional(&mut *conn)
            .await?
            .ok_or_else(|| AppError::NotFound("Payload draft not found".into()))?;
    let eligible: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM paravoid_contracts c JOIN apps a ON a.package_name = c.package_name WHERE c.package_name = ? AND c.contract_id = ? AND c.verification_state = 'verified' AND a.distribution_mode = 'paravoid')")
        .bind(package).bind(&release.contract_id).fetch_one(&mut *conn).await?;
    if !eligible || release.validation_state != "verified" || release.publication_state != "draft" {
        return Err(AppError::Conflict("Publication requires a fully verified VPK, verified shell contract and active Paravoid distribution".into()));
    }
    let high: i64 = sqlx::query_scalar("SELECT COALESCE(MAX(payload_version),0) FROM published_vpk_identities WHERE package_name = ?").bind(package).fetch_one(&mut *conn).await?;
    if release.payload_version <= high {
        return Err(AppError::Conflict("Payload version must exceed every previously published version across all contracts and channels".into()));
    }
    sqlx::query("INSERT INTO published_vpk_identities(package_name,payload_version,release_id,archive_sha256,manifest_sha256) VALUES (?,?,?,?,?)")
        .bind(package).bind(release.payload_version).bind(&release.release_id).bind(&release.archive_sha256).bind(&release.manifest_sha256).execute(&mut *conn).await?;
    sqlx::query("UPDATE vpk_releases SET publication_state = 'published', published_at = datetime('now') WHERE id = ?").bind(id).execute(&mut *conn).await?;
    invalidate_stream(&mut *conn, package, &release.contract_id).await?;
    event(
        &mut *conn,
        package,
        &release.contract_id,
        Some(&release.release_id),
        "publish-vpk",
        actor,
        revision + 1,
    )
    .await?;
    Ok(())
}
pub async fn withdraw(
    pool: &SqlitePool,
    package: &str,
    id: &str,
    actor: &str,
    revision: i64,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await?;
    lock_app(&mut tx, package, revision).await?;
    let release: VpkRelease = sqlx::query_as("UPDATE vpk_releases SET publication_state = 'withdrawn' WHERE package_name = ? AND id = ? AND publication_state = 'published' RETURNING *")
        .bind(package).bind(id).fetch_optional(&mut *tx).await?.ok_or_else(|| AppError::Conflict("Only published payloads can be withdrawn".into()))?;
    invalidate_stream(&mut tx, package, &release.contract_id).await?;
    event(
        &mut tx,
        package,
        &release.contract_id,
        Some(&release.release_id),
        "withdraw-vpk",
        actor,
        revision + 1,
    )
    .await?;
    tx.commit().await?;
    Ok(())
}
pub async fn set_stream(
    pool: &SqlitePool,
    package: &str,
    contract: &str,
    retired: bool,
    actor: &str,
    revision: i64,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await?;
    lock_app(&mut tx, package, revision).await?;
    let exists: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM paravoid_contracts WHERE package_name = ? AND contract_id = ? AND verification_state = 'verified')").bind(package).bind(contract).fetch_one(&mut *tx).await?;
    if !exists {
        return Err(AppError::Conflict(
            "A verified shell contract is required".into(),
        ));
    }
    invalidate_stream(&mut tx, package, contract).await?;
    sqlx::query(
        "UPDATE paravoid_streams SET status = ? WHERE package_name = ? AND contract_id = ?",
    )
    .bind(if retired { "retired" } else { "active" })
    .bind(package)
    .bind(contract)
    .execute(&mut *tx)
    .await?;
    event(
        &mut tx,
        package,
        contract,
        None,
        if retired {
            "retire-stream"
        } else {
            "activate-stream"
        },
        actor,
        revision + 1,
    )
    .await?;
    tx.commit().await?;
    Ok(())
}
pub async fn grants(pool: &SqlitePool, package: &str) -> Result<Vec<Grant>, AppError> {
    Ok(sqlx::query_as("SELECT id,key_id,package_name,contract_id,installer_version,user_subject,acquisition_id,issued_at,expires_at,revoked_at,revoked_by,last_used_at,request_count FROM paravoid_grants WHERE package_name = ? ORDER BY issued_at DESC LIMIT 200")
        .bind(package).fetch_all(pool).await?)
}
pub async fn revoke(
    pool: &SqlitePool,
    package: &str,
    id: &str,
    actor: &str,
    revision: i64,
) -> Result<(), AppError> {
    let mut tx = pool.begin().await?;
    lock_app(&mut tx, package, revision).await?;
    let contract: String = sqlx::query_scalar("UPDATE paravoid_grants SET revoked_at = COALESCE(revoked_at,?), revoked_by = COALESCE(revoked_by,?) WHERE package_name = ? AND id = ? RETURNING contract_id")
        .bind(chrono::Utc::now().timestamp()).bind(actor).bind(package).bind(id).fetch_optional(&mut *tx).await?.ok_or_else(|| AppError::NotFound("Grant not found".into()))?;
    sqlx::query("INSERT INTO paravoid_events(package_name,contract_id,grant_id,action,actor_subject,revision) VALUES (?,?,?,'revoke-grant',?,?)")
        .bind(package).bind(&contract).bind(id).bind(actor).bind(revision + 1).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(())
}
