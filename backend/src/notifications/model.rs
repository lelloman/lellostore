use super::{Error, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::Deserialize;
use simple_server::web::http::HeaderMap;

pub const MAX_TTL: i64 = 28 * 86400;
pub fn identifier(value: &str) -> Result<()> {
    if value.is_empty() || value.len() > 256 || value.chars().any(char::is_control) {
        return Err(Error::bad("invalid identifier"));
    }
    Ok(())
}
pub fn public_key(value: &str) -> Result<Vec<u8>> {
    let key = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Error::bad("invalid VAPID key"))?;
    if value.len() != 87 || key.len() != 65 || key[0] != 4 {
        return Err(Error::bad("invalid VAPID key"));
    }
    // ECDH's public-key validation checks that this is a point on P-256.
    let private = ring::agreement::EphemeralPrivateKey::generate(
        &ring::agreement::ECDH_P256,
        &ring::rand::SystemRandom::new(),
    )
    .map_err(|_| Error::bad("key validation failed"))?;
    ring::agreement::agree_ephemeral(
        private,
        &ring::agreement::UnparsedPublicKey::new(&ring::agreement::ECDH_P256, &key),
        |_| (),
    )
    .map_err(|_| Error::bad("invalid VAPID point"))?;
    Ok(key)
}
pub fn vapid(authorization: Option<&str>, expected: &str, origin: &str, time: i64) -> Result<()> {
    let value = authorization.ok_or_else(Error::unauthorized)?;
    let (scheme, parameters) = value.split_once(' ').ok_or_else(Error::denied)?;
    if !scheme.eq_ignore_ascii_case("vapid") || parameters.len() > 4096 {
        return Err(Error::denied());
    }
    let mut t = None;
    let mut k = None;
    for parameter in parameters.split(',') {
        let (name, value) = parameter.trim().split_once('=').ok_or_else(Error::denied)?;
        let value = value.trim().trim_matches('"');
        match name.trim() {
            "t" if t.is_none() => t = Some(value),
            "k" if k.is_none() => k = Some(value),
            _ => return Err(Error::denied()),
        }
    }
    if k != Some(expected) {
        return Err(Error::denied());
    }
    let jwt = t.ok_or_else(Error::denied)?;
    let parts: Vec<_> = jwt.split('.').collect();
    if parts.len() != 3 {
        return Err(Error::denied());
    }
    let decode = |s: &str| URL_SAFE_NO_PAD.decode(s).map_err(|_| Error::denied());
    let header: serde_json::Value =
        serde_json::from_slice(&decode(parts[0])?).map_err(|_| Error::denied())?;
    if header["alg"] != "ES256" || header.get("crit").is_some() {
        return Err(Error::denied());
    }
    let key = decode(expected)?;
    ring::signature::UnparsedPublicKey::new(&ring::signature::ECDSA_P256_SHA256_FIXED, key)
        .verify(
            format!("{}.{}", parts[0], parts[1]).as_bytes(),
            &decode(parts[2])?,
        )
        .map_err(|_| Error::denied())?;
    let claims: serde_json::Value =
        serde_json::from_slice(&decode(parts[1])?).map_err(|_| Error::denied())?;
    let exp = claims["exp"].as_i64().ok_or_else(Error::denied)?;
    let audience = claims["aud"].as_str() == Some(origin)
        || claims["aud"]
            .as_array()
            .is_some_and(|a| a.iter().any(|v| v.as_str() == Some(origin)));
    if !audience || exp <= time || exp > time + 86400 {
        return Err(Error::denied());
    }
    Ok(())
}
#[derive(Debug)]
pub struct Publication {
    pub payload: Vec<u8>,
    pub ttl: i64,
    pub urgency: String,
    pub topic: Option<String>,
}
impl Publication {
    pub fn parse(headers: &HeaderMap, payload: Vec<u8>) -> Result<Self> {
        let field = |name: &str| -> Result<Option<&str>> {
            if headers.get_all(name).iter().count() > 1 {
                return Err(Error::bad("duplicate push header"));
            }
            headers
                .get(name)
                .map(|v| v.to_str().map_err(|_| Error::bad("invalid header")))
                .transpose()
        };
        if payload.is_empty() || payload.len() > 4096 {
            return Err(Error(
                simple_server::web::http::StatusCode::PAYLOAD_TOO_LARGE,
                "payload must be 1..4096 bytes".into(),
            ));
        }
        if field("content-encoding")? != Some("aes128gcm") {
            return Err(Error(
                simple_server::web::http::StatusCode::UNSUPPORTED_MEDIA_TYPE,
                "aes128gcm required".into(),
            ));
        }
        let ttl = field("ttl")?.ok_or_else(|| Error::bad("TTL required"))?;
        if ttl.is_empty() || !ttl.bytes().all(|c| c.is_ascii_digit()) {
            return Err(Error::bad("invalid TTL"));
        }
        let ttl = ttl
            .parse::<u64>()
            .map_err(|_| Error::bad("invalid TTL"))?
            .min(MAX_TTL as u64) as i64;
        let urgency = field("urgency")?.unwrap_or("normal");
        if !["very-low", "low", "normal", "high"].contains(&urgency) {
            return Err(Error::bad("invalid urgency"));
        }
        let topic = field("topic")?.map(str::to_owned);
        if topic.as_ref().is_some_and(|t| {
            t.is_empty()
                || t.len() > 32
                || !t
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
        }) {
            return Err(Error::bad("invalid topic"));
        }
        Ok(Self {
            payload,
            ttl,
            urgency: urgency.into(),
            topic,
        })
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Subscription {
    pub token: String,
    pub package: String,
    pub vapid: String,
    pub endpoint_secret: String,
}
