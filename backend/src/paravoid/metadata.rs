use super::{
    json::ordinary_strings, parse_json, VerificationError as Error, MAX_ARCHIVE_BYTES,
    MAX_GRANT_BYTES, MAX_HEAD_BYTES, MAX_RELEASE_BYTES,
};
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use ring::signature::{RsaPublicKeyComponents, RSA_PKCS1_2048_8192_SHA256};
use serde::Deserialize;
use serde_json::Value;
use std::collections::{BTreeMap, HashSet};

type Keys = BTreeMap<String, Vec<u8>>;

fn identifier(value: &str) -> Result<(), Error> {
    if value.is_empty()
        || value.len() > 64
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
    {
        return Err(Error::Malformed);
    }
    Ok(())
}
fn hash(value: &str) -> Result<(), Error> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(Error::Malformed);
    }
    Ok(())
}
fn base64(value: &str) -> Result<Vec<u8>, Error> {
    let bytes = STANDARD.decode(value).map_err(|_| Error::Malformed)?;
    if STANDARD.encode(&bytes) != value {
        return Err(Error::Malformed);
    }
    Ok(bytes)
}
fn fields(value: &Value, expected: &[&str]) -> Result<(), Error> {
    let object = value.as_object().ok_or(Error::Malformed)?;
    if object.len() != expected.len() || expected.iter().any(|key| !object.contains_key(*key)) {
        return Err(Error::Malformed);
    }
    Ok(())
}

// The selected profile has one DER encoding: rsaEncryption + NULL parameters,
// a 3072-bit positive modulus, and exponent 65537. Fixed framing also rejects BER,
// alternate algorithm parameters, redundant INTEGER padding and trailing bytes.
pub(super) const SPKI_PREFIX: &[u8] = &[
    0x30, 0x82, 0x01, 0xa2, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
    0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x8f, 0x00, 0x30, 0x82, 0x01, 0x8a, 0x02, 0x82, 0x01, 0x81,
    0x00,
];
pub(super) fn modulus(der: &[u8]) -> Result<&[u8], Error> {
    if der.len() != 422
        || !der.starts_with(SPKI_PREFIX)
        || der[33] & 0x80 == 0
        || der[417..] != [2, 3, 1, 0, 1]
    {
        return Err(Error::Incompatible);
    }
    Ok(&der[33..417])
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TrustDocument {
    version: u64,
    application_id: String,
    release_keys: BTreeMap<String, String>,
    head_keys: BTreeMap<String, String>,
    grant_keys: BTreeMap<String, String>,
    minimum_payload_version: u64,
    minimum_head_revision: u64,
}

/// Validated APK-pinned public trust. It must never be replaced by keys supplied
/// alongside a remotely fetched head, grant or VPK.
pub struct TrustPolicy {
    application_id: String,
    release_keys: Keys,
    head_keys: Keys,
    grant_keys: Keys,
    minimum_payload_version: u64,
    minimum_head_revision: u64,
}
impl TrustPolicy {
    pub fn application_id(&self) -> &str {
        &self.application_id
    }
    pub fn minimum_payload_version(&self) -> u64 {
        self.minimum_payload_version
    }
    pub fn minimum_head_revision(&self) -> u64 {
        self.minimum_head_revision
    }

    pub fn parse(bytes: &[u8]) -> Result<Self, Error> {
        let value = parse_json(bytes, MAX_RELEASE_BYTES)?;
        ordinary_strings(&value)?;
        let doc: TrustDocument = serde_json::from_value(value).map_err(|_| Error::Malformed)?;
        if doc.version != 1 || doc.application_id.is_empty() || doc.release_keys.is_empty() {
            return Err(Error::Incompatible);
        }
        let mut previous = HashSet::new();
        let mut roles = Vec::new();
        for role in [doc.release_keys, doc.head_keys, doc.grant_keys] {
            let mut keys = Keys::new();
            for (id, encoded) in role {
                identifier(&id)?;
                let der = base64(&encoded)?;
                modulus(&der)?;
                if previous.contains(&der) {
                    return Err(Error::Incompatible);
                }
                keys.insert(id, der);
            }
            previous.extend(keys.values().cloned());
            roles.push(keys);
        }
        let mut roles = roles.into_iter();
        Ok(Self {
            application_id: doc.application_id,
            release_keys: roles.next().unwrap(),
            head_keys: roles.next().unwrap(),
            grant_keys: roles.next().unwrap(),
            minimum_payload_version: doc.minimum_payload_version,
            minimum_head_revision: doc.minimum_head_revision,
        })
    }

    /// Authenticate a release envelope only. The caller must additionally verify
    /// its schema, inventory, archive and compatibility before admitting a VPK.
    pub fn authenticate_release(&self, bytes: &[u8]) -> Result<AuthenticatedBody, Error> {
        authenticate(bytes, "release", &self.release_keys, MAX_RELEASE_BYTES)
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Authentication {
    Public,
    ApkKey,
}

/// Construct from verified installer metadata, never remote response fields.
pub struct InstalledPolicy {
    trust: TrustPolicy,
    contract: String,
    base_url: String,
    channel: String,
    authentication: Authentication,
}
impl InstalledPolicy {
    pub(crate) fn trusts_online_key(&self, role: &str, id: &str, spki: &[u8]) -> bool {
        let keys = match role {
            "head" => &self.trust.head_keys,
            "grant" => &self.trust.grant_keys,
            _ => return false,
        };
        keys.get(id).is_some_and(|pinned| pinned == spki)
    }
    pub fn minimum_head_revision(&self) -> u64 {
        self.trust.minimum_head_revision()
    }
    pub fn minimum_payload_version(&self) -> u64 {
        self.trust.minimum_payload_version()
    }
    pub fn endpoint(&self) -> &str {
        &self.base_url
    }
    pub fn new(
        trust: TrustPolicy,
        contract: String,
        base_url: String,
        channel: String,
        authentication: Authentication,
    ) -> Result<Self, Error> {
        hash(&contract)?;
        identifier(&channel)?;
        let url = reqwest::Url::parse(&base_url).map_err(|_| Error::Incompatible)?;
        if url.scheme() != "https"
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || !base_url.ends_with('/')
            || url.as_str() != base_url
            || url.port() == Some(0)
            || trust.head_keys.is_empty()
            || (authentication == Authentication::ApkKey && trust.grant_keys.is_empty())
        {
            return Err(Error::Incompatible);
        }
        Ok(Self {
            trust,
            contract,
            base_url,
            channel,
            authentication,
        })
    }

    pub fn verify_head(
        &self,
        bytes: &[u8],
        sdk: u64,
        abis: &[String],
    ) -> Result<VerifiedHead, Error> {
        if sdk < 30 {
            return Err(Error::Incompatible);
        }
        validate_abis(abis)?;
        let authenticated = authenticate(bytes, "head", &self.trust.head_keys, MAX_HEAD_BYTES)?;
        fields(
            &authenticated.value,
            &[
                "version",
                "applicationId",
                "shellContractId",
                "channel",
                "sdk",
                "abis",
                "runtimeAbi",
                "formatVersion",
                "headRevision",
                "issuedAt",
                "expiresAt",
                "status",
                "release",
            ],
        )?;
        let body: HeadBody =
            serde_json::from_value(authenticated.value.clone()).map_err(|_| Error::Malformed)?;
        hash(&body.shell_contract_id)?;
        identifier(&body.channel)?;
        validate_abis(&body.abis)?;
        if body.version != 1
            || body.format_version != 1
            || body.runtime_abi != 1
            || body.application_id != self.trust.application_id
            || body.shell_contract_id != self.contract
            || body.channel != self.channel
            || body.sdk != sdk
            || body.abis != abis
        {
            return Err(Error::Incompatible);
        }
        if body.head_revision == 0
            || body.expires_at <= body.issued_at
            || body.expires_at - body.issued_at > 86400
        {
            return Err(Error::Malformed);
        }
        match (&body.status, &body.release) {
            (HeadStatus::Available, Some(release)) => {
                identifier(&release.release_id)?;
                hash(&release.manifest_sha256)?;
                hash(&release.archive_sha256)?;
                if release.payload_version == 0 || release.archive_size == 0 {
                    return Err(Error::Malformed);
                }
                if release.archive_size > MAX_ARCHIVE_BYTES {
                    return Err(Error::LimitExceeded);
                }
            }
            (HeadStatus::Available, None) => return Err(Error::Malformed),
            (_, Some(_)) => return Err(Error::Malformed),
            (_, None) => {}
        }
        Ok(VerifiedHead {
            body,
            authenticated,
        })
    }

    pub fn verify_grant(&self, bytes: &[u8]) -> Result<VerifiedGrant, Error> {
        if self.authentication != Authentication::ApkKey {
            return Err(Error::Incompatible);
        }
        let authenticated = authenticate(bytes, "grant", &self.trust.grant_keys, MAX_GRANT_BYTES)?;
        let body: GrantBody =
            serde_json::from_value(authenticated.value.clone()).map_err(|_| Error::Malformed)?;
        hash(&body.shell_contract_id)?;
        if body.version != 1
            || body.application_id != self.trust.application_id
            || body.shell_contract_id != self.contract
            || body.audience != self.base_url
        {
            return Err(Error::Incompatible);
        }
        identifier(&body.grant_id)?;
        identifier(&body.key_id)?;
        if body.expires_at != 0 && body.expires_at <= body.issued_at {
            return Err(Error::Malformed);
        }
        let key = URL_SAFE_NO_PAD
            .decode(&body.key)
            .map_err(|_| Error::Malformed)?;
        if key.len() != 32 || URL_SAFE_NO_PAD.encode(&key) != body.key {
            return Err(Error::Malformed);
        }
        Ok(VerifiedGrant {
            body,
            authenticated,
        })
    }
}

fn validate_abis(abis: &[String]) -> Result<(), Error> {
    let mut seen = HashSet::new();
    for abi in abis {
        if !["arm64-v8a", "armeabi-v7a", "x86", "x86_64"].contains(&abi.as_str())
            || !seen.insert(abi)
        {
            return Err(Error::Malformed);
        }
    }
    Ok(())
}

// Deliberately no Debug or Serialize: authenticated grant bodies contain secrets.
pub struct AuthenticatedBody {
    key_id: String,
    body_bytes: Vec<u8>,
    value: Value,
}
impl AuthenticatedBody {
    pub fn signing_key_id(&self) -> &str {
        &self.key_id
    }
    pub fn exact_bytes(&self) -> &[u8] {
        &self.body_bytes
    }
    pub fn value(&self) -> &Value {
        &self.value
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Envelope {
    key_id: String,
    body: String,
    signature: String,
}
fn authenticate(
    bytes: &[u8],
    role: &str,
    keys: &Keys,
    limit: usize,
) -> Result<AuthenticatedBody, Error> {
    let envelope: Envelope =
        serde_json::from_value(parse_json(bytes, limit)?).map_err(|_| Error::Malformed)?;
    identifier(&envelope.key_id)?;
    let key = keys.get(&envelope.key_id).ok_or(Error::UntrustedKey)?;
    let body = base64(&envelope.body)?;
    let signature = base64(&envelope.signature)?;
    if signature.len() != 384 {
        return Err(Error::InvalidSignature);
    }
    let mut input = format!("paravoid/v1/{role}\n").into_bytes();
    input.extend_from_slice(&body);
    RsaPublicKeyComponents {
        n: modulus(key)?,
        e: &[1, 0, 1],
    }
    .verify(&RSA_PKCS1_2048_8192_SHA256, &input, &signature)
    .map_err(|_| Error::InvalidSignature)?;
    let value = parse_json(&body, limit)?;
    if !value.is_object() {
        return Err(Error::Malformed);
    }
    ordinary_strings(&value)?;
    Ok(AuthenticatedBody {
        key_id: envelope.key_id,
        body_bytes: body,
        value,
    })
}

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum HeadStatus {
    Available,
    NoCompatibleRelease,
    ShellUpdateRequired,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ArchiveOffer {
    pub release_id: String,
    pub payload_version: u64,
    pub manifest_sha256: String,
    pub archive_sha256: String,
    pub archive_size: u64,
}
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HeadBody {
    version: u64,
    application_id: String,
    shell_contract_id: String,
    channel: String,
    sdk: u64,
    abis: Vec<String>,
    runtime_abi: u64,
    format_version: u64,
    pub head_revision: u64,
    pub issued_at: u64,
    pub expires_at: u64,
    pub status: HeadStatus,
    pub release: Option<ArchiveOffer>,
}
pub struct VerifiedHead {
    body: HeadBody,
    authenticated: AuthenticatedBody,
}
impl VerifiedHead {
    pub fn body(&self) -> &HeadBody {
        &self.body
    }
    pub fn authenticated(&self) -> &AuthenticatedBody {
        &self.authenticated
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct GrantBody {
    version: u64,
    application_id: String,
    shell_contract_id: String,
    audience: String,
    grant_id: String,
    key_id: String,
    key: String,
    issued_at: u64,
    expires_at: u64,
}
pub struct VerifiedGrant {
    body: GrantBody,
    authenticated: AuthenticatedBody,
}
impl VerifiedGrant {
    pub fn grant_id(&self) -> &str {
        &self.body.grant_id
    }
    pub fn key_id(&self) -> &str {
        &self.body.key_id
    }
    pub fn credential(&self) -> &str {
        &self.body.key
    }
    pub fn issued_at(&self) -> u64 {
        self.body.issued_at
    }
    pub fn expires_at(&self) -> u64 {
        self.body.expires_at
    }
    pub fn signing_key_id(&self) -> &str {
        self.authenticated.signing_key_id()
    }
}
