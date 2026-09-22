//! Offline-configured online authorities. No HTTP endpoint accepts private keys
//! or signs caller-supplied metadata. Release signing stays outside the Store.
use super::{
    canonical_json,
    metadata::{modulus, SPKI_PREFIX},
    parse_json, InstalledPolicy, VerificationError, MAX_RELEASE_BYTES,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use ring::{
    rand::SystemRandom,
    signature::{KeyPair, RsaKeyPair, RSA_PKCS1_SHA256},
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashSet},
    path::Path,
};

#[derive(Debug, thiserror::Error)]
pub enum SigningError {
    #[error("Invalid Paravoid signing configuration")]
    Configuration,
    #[error("Cannot read Paravoid signing configuration or key")]
    Read,
    #[error("Paravoid private keys must have owner-only file permissions")]
    Permissions,
    #[error("Paravoid signing failed")]
    Sign,
    #[error(transparent)]
    Verification(#[from] VerificationError),
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Configuration {
    version: u64,
    base_url: String,
    head_keys: BTreeMap<String, String>,
    grant_keys: BTreeMap<String, String>,
    active_head_key: String,
    active_grant_key: String,
}
struct Signer {
    key: RsaKeyPair,
    spki: Vec<u8>,
}
impl Signer {
    fn load(path: &Path) -> Result<Self, SigningError> {
        // Read and inspect the same open handle, rather than checking one path
        // and then opening a potentially replaced file.
        use std::io::Read;
        let mut file = std::fs::File::open(path).map_err(|_| SigningError::Read)?;
        let info = file.metadata().map_err(|_| SigningError::Read)?;
        if !info.is_file() || info.len() > 64 * 1024 {
            return Err(SigningError::Configuration);
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if info.permissions().mode() & 0o077 != 0 {
                return Err(SigningError::Permissions);
            }
        }
        let mut bytes = Vec::new();
        file.by_ref()
            .take(64 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| SigningError::Read)?;
        if bytes.len() > 64 * 1024 {
            return Err(SigningError::Configuration);
        }
        let parsed = RsaKeyPair::from_pkcs8(&bytes);
        bytes.fill(0);
        let key = parsed.map_err(|_| SigningError::Configuration)?;
        let mut spki = SPKI_PREFIX[..24].to_vec();
        spki.extend_from_slice(key.public_key().as_ref());
        modulus(&spki)?;
        Ok(Self { key, spki })
    }
    fn sign(&self, id: &str, role: &str, value: &Value) -> Result<Vec<u8>, SigningError> {
        let body = canonical_json(value)?;
        let mut input = format!("paravoid/v1/{role}\n").into_bytes();
        input.extend_from_slice(&body);
        let mut signature = vec![0; 384];
        self.key
            .sign(
                &RSA_PKCS1_SHA256,
                &SystemRandom::new(),
                &input,
                &mut signature,
            )
            .map_err(|_| SigningError::Sign)?;
        Ok(canonical_json(
            &json!({"keyId": id, "body": STANDARD.encode(body), "signature": STANDARD.encode(signature)}),
        )?)
    }
}

/// Private keys are intentionally neither Debug nor Serialize.
pub struct OnlineSigning {
    head_keys: BTreeMap<String, Signer>,
    grant_keys: BTreeMap<String, Signer>,
    active_head_key: String,
    active_grant_key: String,
    base_url: String,
}
#[derive(Serialize)]
pub struct PublicSigningConfiguration {
    pub base_url: String,
    pub head_keys: BTreeMap<String, String>,
    pub grant_keys: BTreeMap<String, String>,
    pub head_fingerprints: BTreeMap<String, String>,
    pub grant_fingerprints: BTreeMap<String, String>,
    pub active_head_key: String,
    pub active_grant_key: String,
}
impl OnlineSigning {
    /// Paths in this operator-owned file are relative to its directory.
    pub fn load(path: &Path) -> Result<Self, SigningError> {
        use std::io::Read;
        let file = std::fs::File::open(path).map_err(|_| SigningError::Read)?;
        let mut bytes = Vec::new();
        file.take(MAX_RELEASE_BYTES as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| SigningError::Read)?;
        let config: Configuration = serde_json::from_value(parse_json(&bytes, MAX_RELEASE_BYTES)?)
            .map_err(|_| SigningError::Configuration)?;
        let url = reqwest::Url::parse(&config.base_url).map_err(|_| SigningError::Configuration)?;
        if config.version != 1
            || url.scheme() != "https"
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || !config.base_url.ends_with('/')
            || url.as_str() != config.base_url
            || url.port() == Some(0)
        {
            return Err(SigningError::Configuration);
        }
        let mut roles = Vec::new();
        let mut previous = HashSet::new();
        for role in [config.head_keys, config.grant_keys] {
            let mut keys = BTreeMap::new();
            for (id, file) in role {
                if id.is_empty()
                    || id.len() > 64
                    || !id
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
                {
                    return Err(SigningError::Configuration);
                }
                let key = Signer::load(&path.parent().unwrap_or(Path::new(".")).join(file))?;
                if previous.contains(&key.spki) {
                    return Err(SigningError::Configuration);
                }
                keys.insert(id, key);
            }
            previous.extend(keys.values().map(|k| k.spki.clone()));
            roles.push(keys);
        }
        let mut roles = roles.into_iter();
        let head_keys = roles.next().unwrap();
        let grant_keys = roles.next().unwrap();
        if !head_keys.contains_key(&config.active_head_key)
            || !grant_keys.contains_key(&config.active_grant_key)
        {
            return Err(SigningError::Configuration);
        }
        Ok(Self {
            head_keys,
            grant_keys,
            active_head_key: config.active_head_key,
            active_grant_key: config.active_grant_key,
            base_url: config.base_url,
        })
    }
    pub fn public_configuration(&self) -> PublicSigningConfiguration {
        let public = |keys: &BTreeMap<String, Signer>| {
            keys.iter()
                .map(|(id, signer)| (id.clone(), STANDARD.encode(&signer.spki)))
                .collect()
        };
        let fingerprints = |keys: &BTreeMap<String, Signer>| {
            keys.iter()
                .map(|(id, signer)| (id.clone(), hex::encode(Sha256::digest(&signer.spki))))
                .collect()
        };
        PublicSigningConfiguration {
            base_url: self.base_url.clone(),
            head_keys: public(&self.head_keys),
            grant_keys: public(&self.grant_keys),
            head_fingerprints: fingerprints(&self.head_keys),
            grant_fingerprints: fingerprints(&self.grant_keys),
            active_head_key: self.active_head_key.clone(),
            active_grant_key: self.active_grant_key.clone(),
        }
    }
    /// Called only after the delivery service chooses and authorizes its snapshot.
    /// Verification enforces the installed schema/scope and that the signing key
    /// was pinned by that shell, including during key rotation.
    pub fn supports_policy(
        &self,
        policy: &InstalledPolicy,
        keyed: bool,
    ) -> Result<(), SigningError> {
        if policy.endpoint() != self.base_url {
            return Err(SigningError::Configuration);
        }
        select_signer(&self.head_keys, &self.active_head_key, "head", policy)?;
        if keyed {
            select_signer(&self.grant_keys, &self.active_grant_key, "grant", policy)?;
        }
        Ok(())
    }
    pub fn sign_head(
        &self,
        body: &Value,
        policy: &InstalledPolicy,
        sdk: u64,
        abis: &[String],
    ) -> Result<Vec<u8>, SigningError> {
        let (id, signer) = select_signer(&self.head_keys, &self.active_head_key, "head", policy)?;
        let bytes = signer.sign(id, "head", body)?;
        policy.verify_head(&bytes, sdk, abis)?;
        Ok(bytes)
    }
    pub fn sign_grant(
        &self,
        body: &Value,
        policy: &InstalledPolicy,
    ) -> Result<Vec<u8>, SigningError> {
        let (id, signer) =
            select_signer(&self.grant_keys, &self.active_grant_key, "grant", policy)?;
        let bytes = signer.sign(id, "grant", body)?;
        policy.verify_grant(&bytes)?;
        Ok(bytes)
    }
}

fn select_signer<'a>(
    keys: &'a BTreeMap<String, Signer>,
    active: &'a str,
    role: &str,
    policy: &InstalledPolicy,
) -> Result<(&'a str, &'a Signer), SigningError> {
    // Prefer the operator's active key, but retain service for installed policies
    // which only trust an older configured key. Both ID and material must match.
    std::iter::once((active, &keys[active]))
        .chain(
            keys.iter()
                .filter(|(id, _)| id.as_str() != active)
                .map(|(id, signer)| (id.as_str(), signer)),
        )
        .find(|(id, signer)| policy.trusts_online_key(role, id, &signer.spki))
        .ok_or(SigningError::Configuration)
}
