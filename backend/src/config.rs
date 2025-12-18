use std::net::SocketAddr;
use std::path::PathBuf;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("Missing environment variable: {0}")]
    MissingEnvVar(String),

    #[error("Invalid socket address: {0}")]
    InvalidSocketAddr(String),

    #[error("Invalid database URL: {0}")]
    InvalidDatabaseUrl(String),
}

#[derive(Debug, Clone)]
pub struct Config {
    pub listen_addr: SocketAddr,
    pub metrics_addr: SocketAddr,
    pub database_url: String,
    pub database_path: PathBuf,
    pub storage_path: PathBuf,
    pub oidc: OidcConfig,
}

#[derive(Debug, Clone)]
pub struct OidcConfig {
    pub issuer_url: String,
    pub audience: String,
    pub admin_role: String,
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
        };

        Ok(Config {
            listen_addr,
            metrics_addr,
            database_url,
            database_path,
            storage_path,
            oidc,
        })
    }
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
