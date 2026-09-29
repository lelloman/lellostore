use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{Error, Result};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Application {
    pub package: String,
    pub certificates: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Strategy {
    Queue,
    Latest,
    OnlineOnly,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Policy {
    pub strategy: Strategy,
    pub ttl_seconds: Option<i64>,
}
impl Policy {
    pub fn validate(&self) -> Result<()> {
        if self
            .ttl_seconds
            .is_some_and(|n| !(1..=315360000).contains(&n))
        {
            return Err(Error::bad(
                "ttl_seconds must be null or between 1 and 315360000",
            ));
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct MessageType {
    pub application: String,
    pub name: String,
    pub levels: Vec<String>,
    pub default: Policy,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Rule {
    pub application: String,
    #[serde(default)]
    pub message_type: Option<String>,
    #[serde(default)]
    pub level: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    pub policy: Policy,
}
impl Rule {
    pub(crate) fn matches(&self, message: &Publication) -> bool {
        self.application == message.application
            && self
                .message_type
                .as_ref()
                .is_none_or(|v| v == &message.message_type)
            && self.level.as_ref().is_none_or(|v| v == &message.level)
            && self.tags.iter().all(|v| message.tags.contains(v))
    }
}
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub types: Vec<MessageType>,
    #[serde(default)]
    pub rules: Vec<Rule>,
}
impl Manifest {
    pub fn validate(&self, applications: &[Application]) -> Result<()> {
        if self.types.is_empty() || self.types.len() > 128 || self.rules.len() > 128 {
            return Err(Error::bad(
                "manifest needs 1..128 types and at most 128 rules",
            ));
        }
        let mut names = std::collections::HashSet::new();
        for t in &self.types {
            identifier(&t.name)?;
            if !applications.iter().any(|a| a.package == t.application)
                || !names.insert((&t.application, &t.name))
                || t.levels.is_empty()
                || t.levels.len() > 16
            {
                return Err(Error::bad("invalid or duplicate message type"));
            }
            for level in &t.levels {
                identifier(level)?;
            }
            t.default.validate()?;
        }
        validate_rules(&self.rules, applications)
    }
    pub fn policy(&self, message: &Publication, overrides: &[Rule]) -> Result<Policy> {
        let t = self
            .types
            .iter()
            .find(|t| t.application == message.application && t.name == message.message_type)
            .ok_or_else(|| Error::bad("unregistered message type"))?;
        if !t.levels.contains(&message.level) {
            return Err(Error::bad("unregistered level"));
        }
        Ok(overrides
            .iter()
            .chain(self.rules.iter())
            .find(|r| r.matches(message))
            .map(|r| &r.policy)
            .unwrap_or(&t.default)
            .clone())
    }
}
pub fn validate_rules(rules: &[Rule], apps: &[Application]) -> Result<()> {
    if rules.len() > 128 {
        return Err(Error::bad("too many policy rules"));
    }
    for r in rules {
        if !apps.iter().any(|a| a.package == r.application) || r.tags.len() > 16 {
            return Err(Error::bad("rule is outside application scope"));
        }
        r.policy.validate()?;
        for tag in &r.tags {
            identifier(tag)?;
        }
    }
    Ok(())
}
pub fn validate_apps(apps: &[Application]) -> Result<()> {
    if apps.is_empty() || apps.len() > 32 {
        return Err(Error::bad("need 1..32 applications"));
    }
    let mut seen = std::collections::HashSet::new();
    for app in apps {
        identifier(&app.package)?;
        if !seen.insert(&app.package)
            || app.certificates.is_empty()
            || app.certificates.len() > 8
            || app.certificates.iter().any(|s| {
                s.len() != 64
                    || !s
                        .bytes()
                        .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
            })
        {
            return Err(Error::bad(
                "unique packages and lowercase SHA-256 signing certificates required",
            ));
        }
    }
    Ok(())
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Target {
    pub subject: String,
    #[serde(default)]
    pub subscription_id: Option<String>,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Publication {
    pub event_id: String,
    pub application: String,
    #[serde(rename = "type")]
    pub message_type: String,
    pub target: Target,
    pub level: String,
    #[serde(default)]
    pub tags: Vec<String>,
    pub occurred_at: i64,
    #[serde(default)]
    pub expires_at: Option<i64>,
    #[serde(default)]
    pub replacement_key: Option<String>,
    #[serde(default)]
    pub occurrence: i64,
    #[serde(default)]
    pub revision: i64,
    pub payload: Value,
}
impl Publication {
    pub fn validate(&self, now: i64) -> Result<()> {
        for v in [
            &self.event_id,
            &self.application,
            &self.message_type,
            &self.target.subject,
            &self.level,
        ] {
            identifier(v)?;
        }
        if self.tags.len() > 16
            || self.occurrence < 0
            || self.revision < 0
            || self.occurred_at > now + 300
            || self.occurred_at < now - 30 * 86400
        {
            return Err(Error::bad("invalid event metadata or occurrence time"));
        }
        for t in &self.tags {
            identifier(t)?;
        }
        if let Some(k) = &self.replacement_key {
            identifier(k)?;
        }
        if serde_json::to_vec(&self.payload)?.len() > 16384 {
            return Err(Error::bad("payload exceeds 16 KiB"));
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Envelope {
    pub version: u8,
    pub delivery_id: String,
    pub sender_id: String,
    pub subscription_id: String,
    pub generation: String,
    pub installation: String,
    pub component: String,
    pub certificate: String,
    pub policy_version: i64,
    pub accepted_at: i64,
    pub expires_at: Option<i64>,
    pub message: Publication,
}
pub fn identifier(s: &str) -> Result<()> {
    if s.is_empty() || s.len() > 256 || s.chars().any(char::is_control) {
        Err(Error::bad("invalid identifier"))
    } else {
        Ok(())
    }
}
