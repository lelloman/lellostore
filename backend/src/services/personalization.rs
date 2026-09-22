use crate::{
    db::{
        acquisitions::{self, Acquisition, AcquisitionRequest},
        models::AppVersion,
        paravoid::Contract,
    },
    error::AppError,
    paravoid::{apk_grant, signing::OnlineSigning},
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ring::rand::{SecureRandom, SystemRandom};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::{io::AsyncWriteExt, sync::Mutex};

pub struct Personalizer {
    script: PathBuf,
    apksigner: PathBuf,
    verifier: PathBuf,
    serial: Mutex<()>,
}
#[derive(sqlx::FromRow)]
struct Job {
    id: String,
    user_subject: String,
    idempotency_key: String,
    package_name: String,
    version_code: i64,
    purpose: String,
    contract_id: String,
    grant_id: String,
    key_id: String,
    credential_sha256: String,
    created_at: i64,
    expires_at: i64,
}
impl Personalizer {
    pub fn new(script: PathBuf, apksigner: PathBuf, verifier: PathBuf) -> Self {
        Self {
            script,
            apksigner,
            verifier,
            serial: Mutex::new(()),
        }
    }
    pub fn from_env() -> Result<Option<Arc<Self>>, AppError> {
        let Some(script) = std::env::var_os("PARAVOID_PERSONALIZER") else {
            return Ok(None);
        };
        let apksigner = std::env::var_os("APKSIGNER_PATH").ok_or_else(|| {
            AppError::Config("APKSIGNER_PATH is required for personalization".into())
        })?;
        let verifier = std::env::var_os("PARAVOID_GRANT_VERIFIER").ok_or_else(|| {
            AppError::Config("PARAVOID_GRANT_VERIFIER is required for personalization".into())
        })?;
        for path in [&script, &apksigner, &verifier] {
            if !Path::new(path).is_file() {
                return Err(AppError::Config("Personalization tool missing".into()));
            }
        }
        Ok(Some(Arc::new(Self::new(
            script.into(),
            apksigner.into(),
            verifier.into(),
        ))))
    }
    /// The single Store instance serializes personalization, including retries.
    /// The durable job keeps its grant/copy identity across process restarts.
    pub async fn acquire(
        &self,
        pool: &SqlitePool,
        storage: &Path,
        signing: &OnlineSigning,
        user: &str,
        package: &str,
        request: &AcquisitionRequest,
    ) -> Result<Acquisition, AppError> {
        if request.idempotency_key.is_empty()
            || request.idempotency_key.len() > 128
            || !request
                .idempotency_key
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
        {
            return Err(AppError::BadRequest(
                "Invalid acquisition idempotency key".into(),
            ));
        }
        let _guard = self.serial.lock().await;
        let now = chrono::Utc::now().timestamp();
        let mut tx = pool.begin().await?;
        sqlx::query(
            "UPDATE apps SET publication_revision = publication_revision WHERE package_name = ?",
        )
        .bind(package)
        .execute(&mut *tx)
        .await?;
        acquisitions::authorize(&mut tx, user, package, request.version_code).await?;
        if let Some(existing) = sqlx::query_as::<_, Acquisition>(
            "SELECT * FROM acquisitions WHERE user_subject = ? AND idempotency_key = ?",
        )
        .bind(user)
        .bind(&request.idempotency_key)
        .fetch_optional(&mut *tx)
        .await?
        {
            if existing.package_name != package
                || existing.version_code != request.version_code
                || existing.purpose != request.purpose.as_str()
                || existing.expires_at <= now
            {
                return Err(AppError::Conflict(
                    "Acquisition key belongs to another or expired request".into(),
                ));
            }
            return Ok(existing);
        }
        let version:AppVersion=sqlx::query_as("SELECT * FROM app_versions WHERE package_name = ? AND version_code = ? AND publication_state = 'published' AND distribution_mode = 'paravoid'").bind(package).bind(request.version_code).fetch_optional(&mut *tx).await?.ok_or_else(||AppError::NotFound("Published shell installer not found".into()))?;
        let contract:Contract=sqlx::query_as("SELECT c.* FROM paravoid_contracts c JOIN paravoid_installers i USING(package_name,contract_id) WHERE i.package_name = ? AND i.installer_version = ? AND verification_state = 'verified' AND authentication = 'apkKey'").bind(package).bind(request.version_code).fetch_optional(&mut *tx).await?.ok_or_else(||AppError::Conflict("Verified keyed shell required".into()))?;
        let existing: Option<Job> = sqlx::query_as(
            "SELECT * FROM personalization_jobs WHERE user_subject = ? AND idempotency_key = ?",
        )
        .bind(user)
        .bind(&request.idempotency_key)
        .fetch_optional(&mut *tx)
        .await?;
        let job = if let Some(job) = existing {
            if job.package_name != package
                || job.version_code != request.version_code
                || job.purpose != request.purpose.as_str()
                || job.contract_id != contract.contract_id
                || job.expires_at <= now
            {
                return Err(AppError::Conflict(
                    "Personalization key belongs to another or expired request".into(),
                ));
            }
            job
        } else {
            let id = uuid::Uuid::new_v4().to_string();
            let grant_id = uuid::Uuid::new_v4().to_string();
            let key_id = uuid::Uuid::new_v4().to_string();
            let mut secret = [0_u8; 32];
            SystemRandom::new()
                .fill(&mut secret)
                .map_err(|_| AppError::Internal("Cannot generate grant credential".into()))?;
            let credential = URL_SAFE_NO_PAD.encode(secret);
            secret.fill(0);
            let hash = hex::encode(Sha256::digest(credential.as_bytes()));
            let body = serde_json::json!({"version":1,"applicationId":package,"shellContractId":contract.contract_id,"audience":contract.base_url,"grantId":grant_id,"keyId":key_id,"key":credential,"issuedAt":now,"expiresAt":0});
            let envelope = signing
                .sign_grant(&body, &contract.policy()?)
                .map_err(|_| {
                    AppError::Config("No signing authority pinned by this shell".into())
                })?;
            let directory = storage.join("acquisitions").join(&id);
            tokio::fs::create_dir_all(&directory).await?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                tokio::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o700))
                    .await?;
            }
            private_file(&directory.join("grant.json"), &envelope).await?;
            private_file(
                &directory.join("trust.json"),
                contract.trust_json.as_bytes(),
            )
            .await?;
            tokio::fs::File::open(&directory).await?.sync_all().await?;
            tokio::fs::File::open(storage.join("acquisitions"))
                .await?
                .sync_all()
                .await?;
            sqlx::query("INSERT INTO personalization_jobs(id,user_subject,idempotency_key,package_name,version_code,purpose,contract_id,grant_id,key_id,credential_sha256,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")
                .bind(&id).bind(user).bind(&request.idempotency_key).bind(package).bind(request.version_code).bind(request.purpose.as_str()).bind(&contract.contract_id).bind(&grant_id).bind(&key_id).bind(&hash).bind(now).bind(now+86400).execute(&mut *tx).await?;
            Job {
                id,
                user_subject: user.into(),
                idempotency_key: request.idempotency_key.clone(),
                package_name: package.into(),
                version_code: request.version_code,
                purpose: request.purpose.as_str().into(),
                contract_id: contract.contract_id.clone(),
                grant_id,
                key_id,
                credential_sha256: hash,
                created_at: now,
                expires_at: now + 86400,
            }
        };
        tx.commit().await?;
        let original = storage.join(&version.apk_path);
        if super::upload::calculate_sha256_file(&original).await? != version.sha256 {
            return Err(AppError::Conflict("Installer template changed".into()));
        }
        let directory = storage.join("acquisitions").join(&job.id);
        let output = directory.join("installer.apk");
        if !tokio::fs::try_exists(&output).await? {
            let mut command = tokio::process::Command::new("python3");
            command
                .arg(&self.script)
                .arg(&original)
                .arg(&output)
                .arg("--grant")
                .arg(directory.join("grant.json"))
                .arg("--trust")
                .arg(directory.join("trust.json"))
                .arg("--contract")
                .arg(&contract.contract_id)
                .arg("--audience")
                .arg(&contract.base_url)
                .arg("--channel")
                .arg(&contract.channel)
                .arg("--apksigner")
                .arg(&self.apksigner)
                .arg("--verifier")
                .arg(&self.verifier)
                .kill_on_drop(true);
            let result = tokio::time::timeout(Duration::from_secs(180), command.output())
                .await
                .map_err(|_| {
                    AppError::Internal(
                        "APK personalization timed out; retry the same acquisition".into(),
                    )
                })??;
            if !result.status.success() {
                return Err(AppError::Internal("APK personalization failed; retry the same acquisition after fixing server tooling".into()));
            }
        }
        let source_signatures = apk_grant::inspect(&mut std::fs::File::open(&original)?)
            .map_err(|_| AppError::Conflict("Invalid template signing block".into()))?
            .signatures;
        let copy_signatures = apk_grant::inspect(&mut std::fs::File::open(&output)?)
            .map_err(|_| AppError::Conflict("Invalid personalized signing block".into()))?
            .signatures;
        if source_signatures != copy_signatures {
            return Err(AppError::Conflict(
                "Personalization changed developer signing evidence".into(),
            ));
        }
        // A recovered output is checked again, including exact job/grant binding.
        let bytes = apk_grant::read(&mut std::fs::File::open(&output)?).map_err(|_| {
            AppError::Conflict("Personalized APK carrier failed verification".into())
        })?;
        let verified = contract
            .policy()?
            .verify_grant(&bytes)
            .map_err(|_| AppError::Conflict("Personalized grant failed verification".into()))?;
        if verified.grant_id() != job.grant_id
            || verified.key_id() != job.key_id
            || hex::encode(Sha256::digest(verified.credential().as_bytes()))
                != job.credential_sha256
        {
            return Err(AppError::Conflict(
                "Personalized APK belongs to a different acquisition".into(),
            ));
        }
        let evidence = tokio::time::timeout(
            Duration::from_secs(60),
            tokio::process::Command::new(&self.apksigner)
                .args(["verify", "--verbose", "--print-certs"])
                .arg(&output)
                .kill_on_drop(true)
                .output(),
        )
        .await
        .map_err(|_| AppError::Internal("APK signature verification timed out".into()))??;
        if !evidence.status.success() {
            return Err(AppError::Conflict(
                "Personalized developer signature is invalid".into(),
            ));
        }
        let sha = super::upload::calculate_sha256_file(&output).await?;
        let size = tokio::fs::metadata(&output).await?.len() as i64;
        tokio::fs::File::open(&directory).await?.sync_all().await?;
        let mut tx = pool.begin().await?;
        sqlx::query(
            "UPDATE apps SET publication_revision = publication_revision WHERE package_name = ?",
        )
        .bind(package)
        .execute(&mut *tx)
        .await?;
        acquisitions::authorize(&mut tx, user, package, request.version_code).await?;
        let published:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM app_versions WHERE package_name = ? AND version_code = ? AND publication_state = 'published')").bind(package).bind(request.version_code).fetch_one(&mut *tx).await?;
        if !published || chrono::Utc::now().timestamp() >= job.expires_at {
            return Err(AppError::Conflict(
                "Acquisition expired or installer was withdrawn during personalization".into(),
            ));
        }
        let path = format!("acquisitions/{}/installer.apk", job.id);
        sqlx::query("INSERT INTO acquisitions(id,user_subject,idempotency_key,package_name,version_code,purpose,apk_path,size,sha256,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")
            .bind(&job.id).bind(&job.user_subject).bind(&job.idempotency_key).bind(&job.package_name).bind(job.version_code).bind(&job.purpose).bind(&path).bind(size).bind(&sha).bind(job.created_at).bind(job.expires_at).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO paravoid_grants(id,key_id,credential_sha256,package_name,contract_id,installer_version,user_subject,acquisition_id,issued_at) VALUES (?,?,?,?,?,?,?,?,?)")
            .bind(&job.grant_id).bind(&job.key_id).bind(&job.credential_sha256).bind(package).bind(&job.contract_id).bind(job.version_code).bind(user).bind(&job.id).bind(job.created_at).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(Acquisition {
            id: job.id,
            package_name: package.into(),
            version_code: job.version_code,
            purpose: job.purpose,
            size,
            sha256: sha,
            created_at: job.created_at,
            expires_at: job.expires_at,
            user_subject: user.into(),
            apk_path: path,
        })
    }
}
async fn private_file(path: &Path, bytes: &[u8]) -> Result<(), AppError> {
    let mut options = tokio::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    options.mode(0o600);
    let mut file = options.open(path).await?;
    file.write_all(bytes).await?;
    file.sync_all().await?;
    Ok(())
}
