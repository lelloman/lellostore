use crate::error::AppError;
use std::{path::Path, time::Duration};

/// Conservative continuity: one unchanged signer, verified v2/v3 APKs. Certificate
/// rotation needs a separately verified lineage and is deliberately not inferred.
pub async fn verified_signer(path: &Path) -> Result<String, AppError> {
    let tool = std::env::var_os("APKSIGNER_PATH").ok_or_else(|| {
        AppError::Config("APKSIGNER_PATH is required to verify a distribution transition".into())
    })?;
    verified_signer_with_tool(Path::new(&tool), path).await
}

pub async fn verified_signer_with_tool(tool: &Path, path: &Path) -> Result<String, AppError> {
    let output = tokio::time::timeout(
        Duration::from_secs(60),
        tokio::process::Command::new(tool)
            .args(["verify", "--verbose", "--print-certs"])
            .arg(path)
            .kill_on_drop(true)
            .output(),
    )
    .await
    .map_err(|_| AppError::Internal("APK signature verification timed out".into()))??;
    if !output.status.success() {
        return Err(AppError::BadRequest(
            "APK developer signature verification failed".into(),
        ));
    }
    let evidence = String::from_utf8_lossy(&output.stdout);
    let scheme = evidence.lines().any(|line| {
        (line.starts_with("Verified using v2 scheme ")
            || line.starts_with("Verified using v3 scheme "))
            && line.ends_with(": true")
    });
    let signers: Vec<_> = evidence
        .lines()
        .filter_map(|line| {
            let (prefix, value) = line.split_once(" certificate SHA-256 digest: ")?;
            prefix
                .starts_with("Signer #")
                .then_some(value.trim().to_ascii_lowercase())
        })
        .collect();
    if !scheme
        || signers.len() != 1
        || signers[0].len() != 64
        || !signers[0].bytes().all(|b| b.is_ascii_hexdigit())
    {
        return Err(AppError::BadRequest(
            "Transition requires a single verified v2/v3 APK signer".into(),
        ));
    }
    Ok(signers[0].clone())
}
