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

    #[error("Invalid value for {0}")]
    InvalidValue(String),
}

#[derive(Debug, Clone)]
pub struct Config {
    pub clients: ClientConfig,
    pub notifications_enabled: bool,
    pub push_public_base_url: String,
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
    pub dvpk: DvpkConfig,
}

/// Public client registrations belong to the deployment, never to a client build.
#[derive(Debug, Clone)]
pub struct ClientConfig {
    pub store_name: String,
    pub android_client_id: String,
    pub web_client_id: String,
    pub publisher_client_id: String,
    pub scopes: Vec<String>,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self {
            store_name: "Android App Store".into(),
            android_client_id: String::new(),
            web_client_id: String::new(),
            publisher_client_id: String::new(),
            scopes: vec!["openid".into(), "profile".into(), "email".into()],
        }
    }
}

impl ClientConfig {
    fn from_env() -> Result<Self, ConfigError> {
        let defaults = Self::default();
        let config = Self {
            store_name: std::env::var("STORE_NAME").unwrap_or(defaults.store_name),
            android_client_id: std::env::var("OIDC_ANDROID_CLIENT_ID").unwrap_or_default(),
            web_client_id: std::env::var("OIDC_WEB_CLIENT_ID").unwrap_or_default(),
            publisher_client_id: std::env::var("OIDC_PUBLISHER_CLIENT_ID").unwrap_or_default(),
            scopes: std::env::var("OIDC_SCOPES")
                .map(|value| value.split_whitespace().map(str::to_owned).collect())
                .unwrap_or(defaults.scopes),
        };
        config.validate()?;
        Ok(config)
    }

    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.store_name.trim().is_empty()
            || self.store_name.len() > 120
            || self.store_name.chars().any(char::is_control)
        {
            return Err(ConfigError::InvalidValue("STORE_NAME".into()));
        }
        for (name, value) in [
            ("OIDC_ANDROID_CLIENT_ID", &self.android_client_id),
            ("OIDC_WEB_CLIENT_ID", &self.web_client_id),
            ("OIDC_PUBLISHER_CLIENT_ID", &self.publisher_client_id),
        ] {
            if value.len() > 256 || value.chars().any(char::is_whitespace) {
                return Err(ConfigError::InvalidValue(name.into()));
            }
        }
        if !self.scopes.iter().any(|scope| scope == "openid")
            || self.scopes.len() > 32
            || self.scopes.iter().any(|scope| {
                scope.is_empty()
                    || scope.len() > 128
                    || !scope.bytes().all(|c| {
                        c == 0x21 || (0x23..=0x5b).contains(&c) || (0x5d..=0x7e).contains(&c)
                    })
            })
        {
            return Err(ConfigError::InvalidValue("OIDC_SCOPES".into()));
        }
        Ok(())
    }

    pub fn is_configured(&self, issuer: &str) -> bool {
        self.validate().is_ok()
            && !self.android_client_id.is_empty()
            && !self.web_client_id.is_empty()
            && !self.publisher_client_id.is_empty()
            && issuer != "https://example.com"
            && reqwest::Url::parse(issuer).is_ok_and(|url| {
                url.scheme() == "https"
                    && url.host_str().is_some()
                    && url.username().is_empty()
                    && url.password().is_none()
                    && url.query().is_none()
                    && url.fragment().is_none()
            })
    }
}

/// Optional DVPK delta delivery. Generation and advertising are separate so
/// patches can be inspected before any shell is offered one.
#[derive(Debug, Clone)]
pub struct DvpkConfig {
    pub generation: bool,
    pub advertising: bool,
    /// Reference `dvpk.py` encoder, run with `python` in a bounded subprocess.
    pub encoder: Option<PathBuf>,
    pub python: PathBuf,
    /// Earlier published archives kept after replacement as delta bases.
    pub retained_bases: u64,
    pub max_input_bytes: u64,
    pub memory_limit_bytes: u64,
    pub timeout_secs: u64,
}
impl Default for DvpkConfig {
    fn default() -> Self {
        Self {
            generation: false,
            advertising: false,
            encoder: None,
            python: PathBuf::from("python3"),
            retained_bases: 3,
            max_input_bytes: 256 * 1024 * 1024,
            memory_limit_bytes: 4 * 1024 * 1024 * 1024,
            timeout_secs: 900,
        }
    }
}
impl DvpkConfig {
    fn from_env() -> Result<Self, ConfigError> {
        let defaults = Self::default();
        let number = |name: &str, default: u64| -> Result<u64, ConfigError> {
            match std::env::var(name) {
                Ok(value) => value
                    .parse()
                    .ok()
                    .filter(|v| *v > 0)
                    .ok_or_else(|| ConfigError::InvalidValue(name.to_string())),
                Err(_) => Ok(default),
            }
        };
        let config = Self {
            generation: std::env::var("PARAVOID_DVPK_GENERATION").as_deref() == Ok("true"),
            advertising: std::env::var("PARAVOID_DVPK_ADVERTISING").as_deref() == Ok("true"),
            encoder: std::env::var("PARAVOID_DVPK_ENCODER")
                .ok()
                .map(PathBuf::from),
            python: std::env::var("PARAVOID_DVPK_PYTHON")
                .map(PathBuf::from)
                .unwrap_or(defaults.python),
            retained_bases: number("PARAVOID_DVPK_RETAINED_BASES", defaults.retained_bases)?,
            max_input_bytes: number("PARAVOID_DVPK_MAX_INPUT_BYTES", defaults.max_input_bytes)?
                .min(crate::paravoid::MAX_ARCHIVE_BYTES),
            memory_limit_bytes: number(
                "PARAVOID_DVPK_MEMORY_LIMIT_BYTES",
                defaults.memory_limit_bytes,
            )?,
            timeout_secs: number("PARAVOID_DVPK_TIMEOUT_SECS", defaults.timeout_secs)?,
        };
        if config.retained_bases > 16 {
            return Err(ConfigError::InvalidValue(
                "PARAVOID_DVPK_RETAINED_BASES".into(),
            ));
        }
        if config.generation && config.encoder.is_none() {
            return Err(ConfigError::MissingEnvVar("PARAVOID_DVPK_ENCODER".into()));
        }
        Ok(config)
    }
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

        let notifications_enabled = std::env::var("NOTIFICATIONS_ENABLED").as_deref() == Ok("true");
        let mut push_public_base_url = std::env::var("PUSH_PUBLIC_BASE_URL")
            .unwrap_or_default()
            .trim_end_matches('/')
            .to_owned();
        if notifications_enabled {
            let url = reqwest::Url::parse(&push_public_base_url)
                .map_err(|_| ConfigError::InvalidValue("PUSH_PUBLIC_BASE_URL".into()))?;
            if url.scheme() != "https"
                || url.host_str().is_none()
                || !url.username().is_empty()
                || url.password().is_some()
                || url.query().is_some()
                || url.fragment().is_some()
                || url.path() != "/"
                || push_public_base_url.len() > 900
            {
                return Err(ConfigError::InvalidValue(
                    "PUSH_PUBLIC_BASE_URL (HTTPS origin required)".into(),
                ));
            }
            push_public_base_url = url.origin().ascii_serialization();
        }
        Ok(Config {
            clients: ClientConfig::from_env()?,
            notifications_enabled,
            push_public_base_url,
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
            dvpk: DvpkConfig::from_env()?,
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
