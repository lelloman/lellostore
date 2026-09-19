use std::net::SocketAddr;
use std::path::PathBuf;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("Missing environment variable: {0}")]
    MissingEnvVar(String),

    #[error("Invalid socket address: {0}")]
    InvalidSocketAddr(String),

    #[error("Invalid SHUTDOWN_GRACE_SECS: {0}")]
    InvalidShutdownGrace(String),

    #[error("Invalid database URL: {0}")]
    InvalidDatabaseUrl(String),
}

#[derive(Debug, Clone)]
pub struct Config {
    pub listen_addr: SocketAddr,
    pub metrics_addr: SocketAddr,
    pub shutdown_grace_secs: u64,
    pub database_url: String,
    pub database_path: PathBuf,
    pub storage_path: PathBuf,
    pub oidc: OidcConfig,
    // APK processing
    pub aapt2_path: Option<PathBuf>,
    pub bundletool_path: Option<PathBuf>,
    pub java_path: Option<PathBuf>,
    pub max_upload_size: u64,
}

#[derive(Debug, Clone)]
pub struct OidcConfig {
    pub issuer_url: String,
    pub audience: String,
    pub admin_role: String,
    /// Dot-separated path to roles claim in JWT (e.g., "realm_access.roles" for Keycloak)
    pub role_claim_path: String,
}

impl Config {
    pub fn from_env() -> Result<Self, ConfigError> {
        let listen_addr = std::env::var("LISTEN_ADDR")
            .unwrap_or_else(|_| "127.0.0.1:8080".to_string())
            .parse()
            .map_err(|_| ConfigError::InvalidSocketAddr("LISTEN_ADDR".to_string()))?;

        let metrics_addr = std::env::var("METRICS_ADDR")
            .unwrap_or_else(|_| "127.0.0.1:9091".to_string())
            .parse()
            .map_err(|_| ConfigError::InvalidSocketAddr("METRICS_ADDR".to_string()))?;

        let shutdown_grace_secs =
            parse_shutdown_grace_secs(std::env::var("SHUTDOWN_GRACE_SECS").ok().as_deref())?;

        let database_url = std::env::var("DATABASE_URL")
            .unwrap_or_else(|_| "sqlite:data/lellostore.db?mode=rwc".to_string());

        let database_path = extract_db_path(&database_url)?;

        let storage_path = std::env::var("STORAGE_PATH")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("data/storage"));

        let oidc = OidcConfig {
            issuer_url: std::env::var("OIDC_ISSUER_URL")
                .unwrap_or_else(|_| "https://example.com".to_string()),
            audience: std::env::var("OIDC_AUDIENCE").unwrap_or_else(|_| "lellostore".to_string()),
            admin_role: std::env::var("OIDC_ADMIN_ROLE").unwrap_or_else(|_| "admin".to_string()),
            role_claim_path: std::env::var("OIDC_ROLE_CLAIM_PATH")
                .unwrap_or_else(|_| "realm_access.roles".to_string()),
        };

        let aapt2_path = std::env::var("AAPT2_PATH").ok().map(PathBuf::from);
        let bundletool_path = std::env::var("BUNDLETOOL_PATH").ok().map(PathBuf::from);
        let java_path = std::env::var("JAVA_PATH").ok().map(PathBuf::from);
        let max_upload_size = std::env::var("MAX_UPLOAD_SIZE")
            .ok()
            .and_then(|s| s.parse().ok())
            .unwrap_or(500 * 1024 * 1024); // 500MB default

        Ok(Config {
            listen_addr,
            metrics_addr,
            shutdown_grace_secs,
            database_url,
            database_path,
            storage_path,
            oidc,
            aapt2_path,
            bundletool_path,
            java_path,
            max_upload_size,
        })
    }
}

fn parse_shutdown_grace_secs(value: Option<&str>) -> Result<u64, ConfigError> {
    let value = value.unwrap_or("30");
    let seconds = value
        .parse::<u64>()
        .map_err(|_| ConfigError::InvalidShutdownGrace(value.to_owned()))?;
    if std::time::Instant::now()
        .checked_add(std::time::Duration::from_secs(seconds))
        .is_none()
    {
        return Err(ConfigError::InvalidShutdownGrace(value.to_owned()));
    }
    Ok(seconds)
}

fn extract_db_path(url: &str) -> Result<PathBuf, ConfigError> {
    url.strip_prefix("sqlite:")
        .and_then(|s| s.split('?').next())
        .filter(|s| !s.is_empty())
        .map(PathBuf::from)
        .ok_or_else(|| ConfigError::InvalidDatabaseUrl(url.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shutdown_budget_defaults_and_validation() {
        assert_eq!(parse_shutdown_grace_secs(None).unwrap(), 30);
        assert_eq!(parse_shutdown_grace_secs(Some("7")).unwrap(), 7);
        assert_eq!(parse_shutdown_grace_secs(Some("0")).unwrap(), 0);
        for value in ["", "-1", "abc", "18446744073709551615"] {
            assert!(parse_shutdown_grace_secs(Some(value)).is_err());
        }
    }

    #[test]
    fn test_extract_db_path_simple() {
        let url = "sqlite:data/lellostore.db";
        let path = extract_db_path(url).unwrap();
        assert_eq!(path, PathBuf::from("data/lellostore.db"));
    }

    #[test]
    fn test_extract_db_path_with_params() {
        let url = "sqlite:data/lellostore.db?mode=rwc";
        let path = extract_db_path(url).unwrap();
        assert_eq!(path, PathBuf::from("data/lellostore.db"));
    }

    #[test]
    fn test_extract_db_path_absolute() {
        let url = "sqlite:/var/data/lellostore.db?mode=rwc";
        let path = extract_db_path(url).unwrap();
        assert_eq!(path, PathBuf::from("/var/data/lellostore.db"));
    }

    #[test]
    fn test_extract_db_path_invalid() {
        let url = "postgres://localhost/db";
        let result = extract_db_path(url);
        assert!(result.is_err());
    }
}
