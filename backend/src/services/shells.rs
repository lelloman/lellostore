use super::{apk_signatures, ApkMetadata};
use crate::{
    error::AppError,
    paravoid::{apk_grant, apk_policy, shell_policy::ShellPolicyDocument},
};
use std::path::Path;

pub struct VerifiedShell {
    pub policy: ShellPolicyDocument,
    pub signer: String,
    pub embedded: Option<(
        tempfile::NamedTempFile,
        crate::paravoid::compatibility::CompatibilityInspection,
    )>,
}
/// Verify the developer APK before trusting its public policy. Never accept a
/// previously personalized copy as the canonical installer.
pub async fn verify(
    path: &Path,
    metadata: &ApkMetadata,
    is_beta: bool,
) -> Result<VerifiedShell, AppError> {
    let signer = apk_signatures::verified_signer(path).await?;
    let path = path.to_owned();
    let (policy, embedded) = tokio::task::spawn_blocking(move || {
        let mut file = std::fs::File::open(path)?;
        let carrier =
            apk_grant::inspect(&mut file).map_err(|e| AppError::BadRequest(e.to_string()))?;
        if carrier.grant.is_some() {
            return Err(AppError::BadRequest(
                "Upload the developer-signed installer without an issued grant".into(),
            ));
        }
        let policy =
            apk_policy::read(&mut file).map_err(|e| AppError::BadRequest(e.to_string()))?;
        if policy.descriptor.distribution.authentication == "apkKey"
            && !carrier.personalization_compatible
        {
            return Err(AppError::BadRequest(
                "Keyed shell signing layout is not supported by the personalization tool".into(),
            ));
        }
        // Policy parsing already rejected duplicate APK entry names.
        let mut archive = zip::ZipArchive::new(file)
            .map_err(|_| AppError::BadRequest("Invalid shell APK".into()))?;
        let embedded = archive
            .file_names()
            .any(|name| name == "assets/paravoid/payload.vpk");
        if embedded != (policy.descriptor.distribution.bootstrap == "embedded") {
            return Err(AppError::BadRequest(
                "APK payload carrier differs from its bootstrap policy".into(),
            ));
        }
        let embedded = if embedded {
            use std::io::{Read, Seek};
            let entry = archive
                .by_name("assets/paravoid/payload.vpk")
                .map_err(|_| AppError::BadRequest("Missing embedded VPK".into()))?;
            let size = entry.size();
            if size == 0
                || size > crate::paravoid::MAX_ARCHIVE_BYTES
                || entry.is_dir()
                || entry.unix_mode().is_some_and(|m| m & 0o170000 == 0o120000)
            {
                return Err(AppError::BadRequest(
                    "Invalid embedded VPK size or entry type".into(),
                ));
            }
            let mut extracted = tempfile::NamedTempFile::new()?;
            if std::io::copy(&mut entry.take(size + 1), &mut extracted)? != size {
                return Err(AppError::BadRequest("Invalid embedded VPK length".into()));
            }
            extracted.rewind()?;
            let checked = crate::paravoid::compatibility::inspect(
                &mut extracted,
                &policy.trust,
                &policy.contract_id,
                &policy.descriptor.installed.ledger_reservations,
            )
            .map_err(|e| AppError::BadRequest(e.to_string()))?;
            Some((extracted, checked))
        } else {
            None
        };
        Ok((policy, embedded))
    })
    .await
    .map_err(|_| AppError::Internal("Shell verification task failed".into()))??;
    let b = &policy.descriptor.installed;
    let d = &policy.descriptor.distribution;
    if b.application_id != metadata.package_name
        || b.min_sdk != metadata.min_sdk as u64
        || b.apk_signers != [signer.clone()]
        || !d.enabled
        || d.channel != if is_beta { "beta" } else { "stable" }
    {
        return Err(AppError::BadRequest("Shell policy must match APK package, SDK, signer and selected channel, with updates enabled".into()));
    }
    Ok(VerifiedShell {
        policy,
        signer,
        embedded,
    })
}

pub async fn register(
    conn: &mut sqlx::SqliteConnection,
    package: &str,
    version: i64,
    shell: &VerifiedShell,
) -> Result<(), AppError> {
    let policy = &shell.policy;
    let d = &policy.descriptor.distribution;
    let descriptor = String::from_utf8(policy.descriptor_bytes.clone()).unwrap();
    let existing: Option<String> = sqlx::query_scalar(
        "SELECT descriptor_json FROM paravoid_contracts WHERE package_name = ? AND contract_id = ?",
    )
    .bind(package)
    .bind(&policy.contract_id)
    .fetch_optional(&mut *conn)
    .await?;
    if existing.as_ref().is_some_and(|v| v != &descriptor) {
        return Err(AppError::Conflict(
            "Registered contract differs from APK policy".into(),
        ));
    }
    if existing.is_none() {
        sqlx::query("INSERT INTO paravoid_contracts(package_name,contract_id,installer_version,channel,authentication,bootstrap,base_url,trust_json,descriptor_json,verification_state,validation_report) VALUES (?,?,?,?,?,?,?,?,?,'verified',?)")
            .bind(package).bind(&policy.contract_id).bind(version).bind(&d.channel).bind(&d.authentication).bind(&d.bootstrap).bind(&d.base_url)
            .bind(policy.descriptor.trust_policy.to_string()).bind(descriptor)
            .bind(serde_json::json!({"apk_signature":"passed","apk_policy":"passed","signer_sha256":shell.signer,"runtime_acceptance":"not_evaluated"}).to_string()).execute(&mut *conn).await?;
    }
    sqlx::query("INSERT INTO paravoid_installers(package_name,installer_version,contract_id,signer_sha256) VALUES (?,?,?,?) ON CONFLICT(package_name,installer_version) DO UPDATE SET signer_sha256 = excluded.signer_sha256 WHERE contract_id = excluded.contract_id").bind(package).bind(version).bind(&policy.contract_id).bind(&shell.signer).execute(&mut *conn).await?;
    if let Some((_, checked)) = &shell.embedded {
        let id =
            super::vpks::insert_release(conn, package, &policy.contract_id, checked, true, true)
                .await?;
        sqlx::query("UPDATE paravoid_installers SET embedded_vpk_id=? WHERE package_name=? AND installer_version=?")
            .bind(id).bind(package).bind(version).execute(&mut *conn).await?;
    }
    sqlx::query("UPDATE app_versions SET distribution_mode = 'paravoid' WHERE package_name = ? AND version_code = ?").bind(package).bind(version).execute(&mut *conn).await?;
    sqlx::query(
        "UPDATE apps SET publication_revision = publication_revision + 1 WHERE package_name = ?",
    )
    .bind(package)
    .execute(&mut *conn)
    .await?;
    Ok(())
}
