//! Decoder for the APK-carried complete-apk-v1 policy. Parsing alone does not
//! establish APK signature validity, registration or publication eligibility.
use super::{
    canonical_json, parse_json, Authentication, InstalledPolicy, TrustPolicy,
    VerificationError as Error,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

pub const APK_PATH: &str = "assets/paravoid/shell-policy.json";
pub const MAX_POLICY_BYTES: usize = 1024 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Envelope {
    version: u64,
    contract_id: String,
    descriptor: Value,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Descriptor {
    pub profile: String,
    pub installed: Boundary,
    pub runtime_abi: u64,
    pub distribution: Distribution,
    pub trust_policy: Value,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Boundary {
    pub application_id: String,
    pub min_sdk: u64,
    pub manifest_sha256: String,
    pub declarations: BTreeMap<String, String>,
    pub pinned_resources: BTreeMap<String, ResourcePin>,
    pub runtime_classes: BTreeMap<String, String>,
    pub native_abis: BTreeMap<String, String>,
    pub ledger_reservations: BTreeMap<String, String>,
    pub apk_signers: Vec<String>,
    pub toolchain: BTreeMap<String, String>,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ResourcePin {
    pub id: String,
    pub sha256: String,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Distribution {
    pub bootstrap: String,
    pub enabled: bool,
    pub base_url: String,
    pub channel: String,
    pub authentication: String,
    pub debug_http_allowed: bool,
}
pub struct ShellPolicyDocument {
    pub contract_id: String,
    pub descriptor: Descriptor,
    pub descriptor_bytes: Vec<u8>,
    pub trust: TrustPolicy,
}
fn hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
impl ShellPolicyDocument {
    /// Production Store policy: debug HTTP is rejected, including in debug APKs.
    /// Offline embedded shells may parse, but are not online distribution policies.
    pub fn parse(bytes: &[u8]) -> Result<Self, Error> {
        let value = parse_json(bytes, MAX_POLICY_BYTES)?;
        super::json::ordinary_strings(&value)?;
        let envelope: Envelope = serde_json::from_value(value).map_err(|_| Error::Malformed)?;
        let descriptor_bytes = canonical_json(&envelope.descriptor)?;
        if envelope.version != 1
            || !hash(&envelope.contract_id)
            || hex::encode(Sha256::digest(&descriptor_bytes)) != envelope.contract_id
        {
            return Err(Error::Incompatible);
        }
        let descriptor: Descriptor =
            serde_json::from_value(envelope.descriptor).map_err(|_| Error::Malformed)?;
        let b = &descriptor.installed;
        let d = &descriptor.distribution;
        if descriptor.profile != "complete-apk-v1"
            || descriptor.runtime_abi != 1
            || !(30..=i32::MAX as u64).contains(&b.min_sdk)
            || !hash(&b.manifest_sha256)
            || b.apk_signers.is_empty()
            || b.apk_signers.iter().any(|v| !hash(v))
            || b.runtime_classes
                .values()
                .chain(b.native_abis.values())
                .any(|v| !hash(v))
            || b.native_abis
                .keys()
                .any(|v| !["arm64-v8a", "armeabi-v7a", "x86", "x86_64"].contains(&v.as_str()))
            || !["public", "apkKey"].contains(&d.authentication.as_str())
            || !["empty", "embedded"].contains(&d.bootstrap.as_str())
            || (d.bootstrap == "empty" && !d.enabled)
            || d.debug_http_allowed
            || d.channel.is_empty()
            || d.channel.len() > 64
            || !d
                .channel
                .bytes()
                .all(|v| v.is_ascii_alphanumeric() || b"_-".contains(&v))
        {
            return Err(Error::Incompatible);
        }
        validate_reservations(&b.ledger_reservations)?;
        if b.pinned_resources.iter().any(|(name, pin)| {
            !hash(&pin.sha256) || b.ledger_reservations.get(name) != Some(&pin.id)
        }) {
            return Err(Error::Incompatible);
        }
        let trust_bytes = canonical_json(&descriptor.trust_policy)?;
        let trust = TrustPolicy::parse(&trust_bytes)?;
        if b.application_id != trust.application_id()
            || (d.authentication == "apkKey"
                && descriptor.trust_policy["grantKeys"]
                    .as_object()
                    .is_none_or(|keys| keys.is_empty()))
        {
            return Err(Error::Incompatible);
        }
        if d.enabled {
            InstalledPolicy::new(
                TrustPolicy::parse(&trust_bytes)?,
                envelope.contract_id.clone(),
                d.base_url.clone(),
                d.channel.clone(),
                if d.authentication == "apkKey" {
                    Authentication::ApkKey
                } else {
                    Authentication::Public
                },
            )?;
        }
        Ok(Self {
            contract_id: envelope.contract_id,
            descriptor,
            descriptor_bytes,
            trust,
        })
    }
}

pub(super) fn validate_reservations(reservations: &BTreeMap<String, String>) -> Result<(), Error> {
    let mut ids = BTreeSet::new();
    let mut types = BTreeMap::new();
    let mut type_ids = BTreeMap::new();
    for (name, id) in reservations {
        if name.len() > 4096
            || !super::compatibility::resource_name(name)
            || id.len() != 10
            || !id.starts_with("0x7f")
            || !id[4..]
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || !ids.insert(id)
        {
            return Err(Error::Malformed);
        }
        let kind = name.split('/').next().unwrap();
        let type_id = &id[4..6];
        if type_id == "00"
            || types.insert(kind, type_id).is_some_and(|v| v != type_id)
            || type_ids.insert(type_id, kind).is_some_and(|v| v != kind)
        {
            return Err(Error::Malformed);
        }
    }
    Ok(())
}
