//! Bounded, lower-priority DVPK generation. Full VPK publication never waits for
//! this worker, and no outcome here changes drafts, approvals or signed VPKs.
use crate::{
    config::DvpkConfig,
    db::{
        dvpk::{self, Delta, VerifiedPatch},
        paravoid::VpkRelease,
    },
    error::AppError,
    metrics,
    paravoid::dvpk::{self as codec, DvpkError, ALGORITHM, MAX_PATCH_BYTES, MIN_PATCH_BYTES},
};
use serde::Deserialize;
use simple_server::lifecycle::Shutdown;
use sqlx::SqlitePool;
use std::{
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

pub const WORK_NAMESPACE: &str = "dvpk-work";
const MAX_ATTEMPTS: i64 = 3;
const MAX_ENCODER_OUTPUT: usize = 64 * 1024;

pub struct Worker {
    pool: SqlitePool,
    storage: PathBuf,
    config: DvpkConfig,
    encoder: PathBuf,
}

/// Outcome of one claimed job; full delivery is unaffected by every variant.
enum Outcome {
    Ready(VerifiedPatch),
    /// A normal optimization result, such as insufficient savings.
    Skipped(String),
    /// Worth retrying with bounded backoff, such as a crashed encoder.
    Transient(String),
    Failed(String),
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct EncoderDelta {
    algorithm: String,
    base_archive_sha256: String,
    base_archive_size: u64,
    patch_sha256: String,
    patch_size: u64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct EncoderTarget {
    archive_sha256: String,
    archive_size: u64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct EncoderReport {
    delta: EncoderDelta,
    target: EncoderTarget,
}

impl Worker {
    pub fn new(pool: SqlitePool, storage: PathBuf, config: DvpkConfig) -> Result<Self, AppError> {
        let encoder = config
            .encoder
            .clone()
            .ok_or_else(|| AppError::Config("PARAVOID_DVPK_ENCODER is required".into()))?;
        Ok(Self {
            pool,
            storage,
            config,
            encoder,
        })
    }

    /// Never returns an error: generation is optional, so a failing job or
    /// database hiccup is logged and retried instead of stopping the server.
    pub async fn run(self, shutdown: Shutdown) -> Result<(), AppError> {
        let mut recovered = false;
        loop {
            if shutdown.is_requested() {
                return Ok(());
            }
            let step = async {
                if !recovered {
                    let count = dvpk::recover(&self.pool).await?;
                    if count > 0 {
                        tracing::info!(count, "Interrupted DVPK jobs requeued");
                    }
                    recovered = true;
                }
                dvpk::skip_orphaned(&self.pool, chrono::Utc::now().timestamp()).await?;
                let Some(job) = dvpk::claim(&self.pool).await? else {
                    return Ok(false);
                };
                self.process(&job).await?;
                Ok::<_, AppError>(true)
            };
            // Dropping a job on shutdown kills the encoder; the claim is
            // recovered on the next start.
            let worked = tokio::select! {
                _ = shutdown.requested() => return Ok(()),
                result = step => result,
            };
            let idle = match worked {
                Ok(worked) => !worked,
                Err(error) => {
                    tracing::warn!(%error, "DVPK worker step failed; retrying");
                    true
                }
            };
            if idle {
                tokio::select! {
                    _ = shutdown.requested() => return Ok(()),
                    _ = tokio::time::sleep(Duration::from_secs(5)) => {}
                }
            }
        }
    }

    /// Process one claimed job and record its outcome.
    pub async fn process(&self, job: &Delta) -> Result<(), AppError> {
        let started = Instant::now();
        let work = self.storage.join(WORK_NAMESPACE).join(&job.id);
        let outcome = match self.generate(job, &work).await {
            Ok(outcome) => outcome,
            Err(AppError::Io(error)) => Outcome::Transient(format!("I/O failure: {error}")),
            Err(error) => Outcome::Transient(error.to_string()),
        };
        let _ = tokio::fs::remove_dir_all(&work).await;
        let elapsed = started.elapsed();
        metrics::DVPK_GENERATION_SECONDS.observe(elapsed.as_secs_f64());
        let duration = Some(elapsed.as_millis() as i64);
        let label = match outcome {
            Outcome::Ready(mut patch) => {
                patch.duration_ms = elapsed.as_millis() as i64;
                if dvpk::mark_ready(&self.pool, job, &patch).await? {
                    tracing::info!(delta = %job.id, patch_size = patch.size, target_size = job.target_archive_size, "DVPK ready");
                }
                "ready"
            }
            Outcome::Skipped(reason) => {
                tracing::info!(delta = %job.id, %reason, "DVPK skipped");
                dvpk::finish(&self.pool, job, "skipped", &reason, None, duration).await?;
                "skipped"
            }
            Outcome::Transient(reason) if job.attempts < MAX_ATTEMPTS => {
                let retry = chrono::Utc::now().timestamp() + 60 * 4_i64.pow(job.attempts as u32);
                tracing::warn!(delta = %job.id, %reason, "DVPK generation will retry");
                dvpk::finish(&self.pool, job, "queued", &reason, Some(retry), duration).await?;
                "retry"
            }
            Outcome::Transient(reason) | Outcome::Failed(reason) => {
                tracing::warn!(delta = %job.id, %reason, "DVPK generation failed");
                dvpk::finish(&self.pool, job, "failed", &reason, None, duration).await?;
                "failed"
            }
        };
        metrics::DVPK_JOBS.with_label_values(&[label]).inc();
        Ok(())
    }

    async fn release(&self, id: &str, job: &Delta) -> Result<Option<VpkRelease>, AppError> {
        Ok(sqlx::query_as("SELECT * FROM vpk_releases WHERE id = ? AND package_name = ? AND contract_id = ? AND validation_state = 'verified' AND artifact_cleaned = 0")
            .bind(id).bind(&job.package_name).bind(&job.contract_id).fetch_optional(&self.pool).await?)
    }

    fn archive_path(&self, release: &VpkRelease) -> Result<PathBuf, AppError> {
        let relative = Path::new(&release.archive_path);
        if !relative.starts_with("vpks")
            || !relative
                .components()
                .all(|part| matches!(part, std::path::Component::Normal(_)))
        {
            return Err(AppError::Internal("Invalid stored VPK path".into()));
        }
        Ok(self.storage.join(relative))
    }

    async fn generate(&self, job: &Delta, work: &Path) -> Result<Outcome, AppError> {
        if job.algorithm != ALGORITHM {
            return Ok(Outcome::Failed("Unsupported algorithm".into()));
        }
        let (Some(base), Some(target)) = (
            self.release(&job.base_vpk_id, job).await?,
            self.release(&job.target_vpk_id, job).await?,
        ) else {
            return Ok(Outcome::Skipped(
                "Base or target archive is no longer stored".into(),
            ));
        };
        if target.artifact_removed || target.publication_state == "withdrawn" {
            return Ok(Outcome::Skipped("Target was superseded".into()));
        }
        if base.archive_sha256 != job.base_archive_sha256
            || base.archive_size != job.base_archive_size
            || target.archive_sha256 != job.target_archive_sha256
            || target.archive_size != job.target_archive_size
            || base.payload_version >= target.payload_version
        {
            return Ok(Outcome::Failed(
                "Archive identity differs from the job".into(),
            ));
        }
        let limit = self.config.max_input_bytes as i64;
        if base.archive_size > limit || target.archive_size > limit {
            return Ok(Outcome::Skipped(
                "Archive exceeds the worker input limit".into(),
            ));
        }
        let (base_path, target_path) = (self.archive_path(&base)?, self.archive_path(&target)?);
        // Recheck exact stored inputs before trusting them as encoder inputs.
        for (path, release) in [(&base_path, &base), (&target_path, &target)] {
            if tokio::fs::metadata(path).await?.len() != release.archive_size as u64
                || super::upload::calculate_sha256_file(path).await? != release.archive_sha256
            {
                return Ok(Outcome::Failed(format!(
                    "Stored archive {} failed checksum verification",
                    release.release_id
                )));
            }
        }
        metrics::DVPK_INPUT_BYTES.inc_by((base.archive_size + target.archive_size) as u64);
        let _ = tokio::fs::remove_dir_all(work).await;
        tokio::fs::create_dir_all(work).await?;
        let output = work.join("payload.dvpk");
        let report = match self.encode(&base_path, &target_path, &output).await? {
            Ok(report) => report,
            Err(outcome) => return Ok(outcome),
        };
        let size = tokio::fs::metadata(&output).await?.len();
        let sha256 = super::upload::calculate_sha256_file(&output).await?;
        let d = &report.delta;
        if d.algorithm != ALGORITHM
            || d.base_archive_sha256 != base.archive_sha256
            || d.base_archive_size != base.archive_size as u64
            || d.patch_sha256 != sha256
            || d.patch_size != size
            || report.target.archive_sha256 != target.archive_sha256
            || report.target.archive_size != target.archive_size as u64
        {
            return Ok(Outcome::Failed(
                "Encoder report differs from its inputs or output".into(),
            ));
        }
        if !(MIN_PATCH_BYTES..=MAX_PATCH_BYTES).contains(&size) {
            return Ok(Outcome::Skipped(
                "Patch is outside the DVPK size limits".into(),
            ));
        }
        if !codec::saves_enough(size, target.archive_size as u64) {
            return Ok(Outcome::Skipped(format!(
                "Insufficient savings: {size} of {} bytes",
                target.archive_size
            )));
        }
        // Independent reconstruction, not the encoder's own report, is the
        // publication authority.
        let (b, p, t) = (base_path.clone(), output.clone(), target_path.clone());
        match tokio::task::spawn_blocking(move || codec::verify(&b, &p, &t))
            .await
            .map_err(|_| AppError::Internal("DVPK verification task failed".into()))?
        {
            Ok(()) => {}
            Err(DvpkError::Io(error)) => return Err(error.into()),
            Err(error) => return Ok(Outcome::Failed(error.to_string())),
        }
        let path =
            super::vpks::store_immutable(&self.storage, "dvpks", "dvpk", &output, &sha256, size)
                .await?;
        Ok(Outcome::Ready(VerifiedPatch {
            sha256,
            size,
            path,
            encoder_version: self.encoder_version().await?,
            duration_ms: 0,
        }))
    }

    async fn encoder_version(&self) -> Result<String, AppError> {
        let digest = super::upload::calculate_sha256_file(&self.encoder).await?;
        Ok(format!("reference-dvpk.py:{}", &digest[..12]))
    }

    /// Run the reference encoder on verified immutable paths, as structured
    /// arguments, with memory, CPU, output-size and wall-clock limits.
    async fn encode(
        &self,
        base: &Path,
        target: &Path,
        output: &Path,
    ) -> Result<Result<EncoderReport, Outcome>, AppError> {
        let mut command = tokio::process::Command::new(&self.config.python);
        command
            .arg(&self.encoder)
            .arg(base)
            .arg(target)
            .arg(output)
            .env_clear()
            .env("PATH", "/usr/local/bin:/usr/bin:/bin")
            .env("PYTHONDONTWRITEBYTECODE", "1")
            .env("PYTHONNOUSERSITE", "1")
            .stdin(std::process::Stdio::null())
            .kill_on_drop(true);
        let memory = self.config.memory_limit_bytes as libc::rlim_t;
        let cpu = self.config.timeout_secs as libc::rlim_t;
        // Bound temporary disk use to one maximum-size patch.
        let file_size = (MAX_PATCH_BYTES + 1024 * 1024) as libc::rlim_t;
        // SAFETY: the closure only calls async-signal-safe libc functions.
        unsafe {
            command.pre_exec(move || {
                for (resource, limit) in [
                    (libc::RLIMIT_AS, memory),
                    (libc::RLIMIT_CPU, cpu),
                    (libc::RLIMIT_FSIZE, file_size),
                    (libc::RLIMIT_CORE, 0),
                ] {
                    let value = libc::rlimit {
                        rlim_cur: limit,
                        rlim_max: limit,
                    };
                    if libc::setrlimit(resource, &value) != 0 {
                        return Err(std::io::Error::last_os_error());
                    }
                }
                // Lower priority than request handling; failure is harmless.
                libc::setpriority(libc::PRIO_PROCESS, 0, 10);
                Ok(())
            });
        }
        let result = tokio::time::timeout(
            Duration::from_secs(self.config.timeout_secs),
            command.output(),
        )
        .await;
        let result = match result {
            Err(_) => {
                return Ok(Err(Outcome::Skipped(
                    "Encoder exceeded the time limit".into(),
                )))
            }
            Ok(result) => result?,
        };
        if !result.status.success() {
            use std::os::unix::process::ExitStatusExt;
            let stderr = String::from_utf8_lossy(&result.stderr);
            let outcome = if result.status.signal().is_some() || stderr.contains("MemoryError") {
                Outcome::Skipped("Encoder exceeded a resource limit".into())
            } else if stderr.contains("Delta exceeds wire limit")
                || stderr.contains("VPK size limit")
            {
                Outcome::Skipped("Patch is outside the DVPK size limits".into())
            } else if stderr.contains("Reconstructed target mismatch") {
                Outcome::Failed("Encoder self-check failed".into())
            } else {
                let last = stderr.lines().last().unwrap_or("").trim();
                Outcome::Transient(format!("Encoder failed: {last}"))
            };
            return Ok(Err(outcome));
        }
        if result.stdout.len() > MAX_ENCODER_OUTPUT {
            return Ok(Err(Outcome::Failed("Encoder report is too large".into())));
        }
        match serde_json::from_slice::<EncoderReport>(&result.stdout) {
            Ok(report) => Ok(Ok(report)),
            Err(_) => Ok(Err(Outcome::Failed("Encoder report is malformed".into()))),
        }
    }
}
