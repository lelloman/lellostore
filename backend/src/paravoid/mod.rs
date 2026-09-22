//! Paravoid v1 protocol primitives. These are stateless checks, not publication
//! authorization, durable replay admission, or complete VPK verification.
pub mod apk_grant;
pub mod apk_policy;
pub mod archive;
pub mod compatibility;
mod components;
pub use components::{ComponentEntry, ComponentInspection};
mod json;
mod metadata;
pub mod shell_policy;
pub mod signing;
pub use json::{canonical_json, parse_json};
pub use metadata::*;

pub const MAX_INTEGER: u64 = 9_007_199_254_740_991;
pub const MAX_HEAD_BYTES: usize = 64 * 1024;
pub const MAX_GRANT_BYTES: usize = 16 * 1024;
pub const MAX_RELEASE_BYTES: usize = 1024 * 1024;
pub const MAX_ARCHIVE_BYTES: u64 = 1024 * 1024 * 1024;

/// Errors deliberately contain no untrusted input or credentials.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum VerificationError {
    #[error("Malformed Paravoid metadata")]
    Malformed,
    #[error("Paravoid metadata exceeds a protocol limit")]
    LimitExceeded,
    #[error("Untrusted Paravoid signing key")]
    UntrustedKey,
    #[error("Invalid Paravoid signature")]
    InvalidSignature,
    #[error("Incompatible Paravoid metadata or installed policy")]
    Incompatible,
}
