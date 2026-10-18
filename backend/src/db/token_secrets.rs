//! Shared helpers for bearer-secret machine credentials (publish and device
//! tokens): secret generation, hashing and expiry normalization.
use ring::rand::{SecureRandom, SystemRandom};
use sha2::{Digest, Sha256};

use crate::error::AppError;

const SECRET_BYTES: usize = 32;

/// SHA-256 hex digest of a secret; only this is ever stored.
pub fn hash_secret(secret: &str) -> String {
    hex::encode(Sha256::digest(secret.as_bytes()))
}

/// A fresh `prefix` + 64 hex characters (32 random bytes) secret.
pub(crate) fn generate_secret(prefix: &str) -> Result<String, AppError> {
    let mut bytes = [0_u8; SECRET_BYTES];
    SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| AppError::Internal("Secure random generator unavailable".into()))?;
    Ok(format!("{prefix}{}", hex::encode(bytes)))
}

/// Trim a token name and require 1 to 100 characters.
pub(crate) fn validate_name(name: &str) -> Result<&str, AppError> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        return Err(AppError::BadRequest(
            "name must contain 1 to 100 characters".into(),
        ));
    }
    Ok(name)
}

/// Normalize an RFC 3339 expiry into SQLite's UTC `datetime()` format so it
/// compares correctly with `datetime('now')`.
pub(crate) fn normalize_expiry(expires_at: Option<&str>) -> Result<Option<String>, AppError> {
    let Some(raw) = expires_at.map(str::trim).filter(|v| !v.is_empty()) else {
        return Ok(None);
    };
    let parsed = chrono::DateTime::parse_from_rfc3339(raw)
        .map_err(|_| AppError::BadRequest("expires_at must be an RFC 3339 timestamp".into()))?
        .with_timezone(&chrono::Utc);
    if parsed <= chrono::Utc::now() {
        return Err(AppError::BadRequest(
            "expires_at must be in the future".into(),
        ));
    }
    Ok(Some(parsed.format("%Y-%m-%d %H:%M:%S").to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secrets_are_prefixed_random_and_hashed() {
        let first = generate_secret("p_").unwrap();
        let second = generate_secret("p_").unwrap();
        assert!(first.starts_with("p_"));
        assert_eq!(first.len(), 2 + 64);
        assert!(first[2..].chars().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(first, second);
        assert_eq!(hash_secret(&first).len(), 64);
        assert_ne!(hash_secret(&first), first);
    }

    #[test]
    fn name_and_expiry_validation() {
        assert_eq!(validate_name("  TV ").unwrap(), "TV");
        assert!(validate_name(" ").is_err());
        assert!(validate_name(&"x".repeat(101)).is_err());
        assert_eq!(normalize_expiry(None).unwrap(), None);
        assert_eq!(normalize_expiry(Some(" ")).unwrap(), None);
        assert!(normalize_expiry(Some("2000-01-01T00:00:00Z")).is_err());
        assert!(normalize_expiry(Some("tomorrow")).is_err());
        assert_eq!(
            normalize_expiry(Some("2999-01-01T02:00:00+02:00")).unwrap(),
            Some("2999-01-01 00:00:00".into())
        );
    }
}
