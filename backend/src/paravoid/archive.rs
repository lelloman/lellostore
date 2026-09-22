//! Bounded, extraction-free inspection of the selected v1 outer archive and its
//! signed inventory. This is NOT complete VPK verification: Android components,
//! resource-ledger compatibility and executable content still need verification.
use super::{TrustPolicy, VerificationError, MAX_ARCHIVE_BYTES, MAX_RELEASE_BYTES};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    io::{Read, Seek, SeekFrom},
};

const MAX_ENTRIES: usize = 4096;
#[derive(Debug, thiserror::Error)]
pub enum InspectionError {
    #[error("Malformed or unsupported VPK archive")]
    Archive,
    #[error("VPK exceeds an archive limit")]
    Limit,
    #[error("VPK content checksum mismatch")]
    Integrity,
    #[error("VPK inventory does not match archive contents")]
    Inventory,
    #[error("VPK release scope or requirements are incompatible")]
    Incompatible,
    #[error("Could not read VPK archive")]
    Read,
    #[error(transparent)]
    Metadata(#[from] VerificationError),
}
type Result<T> = std::result::Result<T, InspectionError>;
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InventoryEntry {
    pub path: String,
    pub size: u64,
    pub sha256: String,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReleaseManifest {
    pub version: u64,
    pub application_id: String,
    pub shell_contract_id: String,
    pub release_id: String,
    pub payload_version: u64,
    pub runtime_abi: u64,
    pub format_version: u64,
    pub min_sdk: u64,
    pub max_sdk: u64,
    pub abis: Vec<String>,
    pub ledger_sha256: String,
    pub inventory: Vec<InventoryEntry>,
}
/// Inspection evidence only. It must never be used as publication/admission proof.
#[derive(Debug, Serialize)]
pub struct ArchiveInspection {
    pub archive_size: u64,
    pub archive_sha256: String,
    pub manifest_sha256: String,
    pub signing_key_id: String,
    pub release: ReleaseManifest,
    pub components: Vec<super::ComponentInspection>,
}
struct Entry {
    name: String,
    offset: u64,
    size: u64,
    crc: u32,
    version: u16,
    flags: u16,
    time: u16,
    date: u16,
}
fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes(b[at..at + 2].try_into().unwrap())
}
fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}
fn read<const N: usize>(reader: &mut impl Read) -> Result<[u8; N]> {
    let mut bytes = [0; N];
    reader
        .read_exact(&mut bytes)
        .map_err(|_| InspectionError::Read)?;
    Ok(bytes)
}
fn seek(reader: &mut impl Seek, position: u64) -> Result<()> {
    reader
        .seek(SeekFrom::Start(position))
        .map_err(|_| InspectionError::Read)?;
    Ok(())
}
fn hash_valid(hash: &str) -> bool {
    hash.len() == 64
        && hash
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn identifier(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
}
fn dex_index(path: &str) -> Option<usize> {
    if path == "code/classes.dex" {
        return Some(1);
    }
    let digits = path.strip_prefix("code/classes")?.strip_suffix(".dex")?;
    if digits.starts_with('0') || !digits.bytes().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let index: usize = digits.parse().ok()?;
    (2..=MAX_ENTRIES).contains(&index).then_some(index)
}
fn native_abi(path: &str) -> Option<&str> {
    let rest = path.strip_prefix("native/")?;
    let (abi, name) = rest.split_once('/')?;
    if !["arm64-v8a", "armeabi-v7a", "x86", "x86_64"].contains(&abi)
        || !name.ends_with(".so")
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-+.".contains(&b))
    {
        return None;
    }
    Some(abi)
}
fn validate_path(path: &str, size: u64) -> Result<()> {
    let limit = match path {
        "release.json" => MAX_RELEASE_BYTES as u64,
        "resources.apk" | "java-resources.jar" | "resource-ledger.json" => MAX_ARCHIVE_BYTES,
        p if dex_index(p).is_some() => 128 * 1024 * 1024,
        p if native_abi(p).is_some() => 256 * 1024 * 1024,
        _ => return Err(InspectionError::Archive),
    };
    if size > limit {
        return Err(InspectionError::Limit);
    }
    Ok(())
}

fn directory<R: Read + Seek>(reader: &mut R, length: u64) -> Result<(Vec<Entry>, u64)> {
    if length > MAX_ARCHIVE_BYTES {
        return Err(InspectionError::Limit);
    }
    if length < 22 {
        return Err(InspectionError::Archive);
    }
    seek(reader, length - 22)?;
    let end = read::<22>(reader)?;
    if end[..4] != *b"PK\x05\x06"
        || u16_at(&end, 4) != 0
        || u16_at(&end, 6) != 0
        || u16_at(&end, 8) != u16_at(&end, 10)
        || u16_at(&end, 20) != 0
    {
        return Err(InspectionError::Archive);
    }
    let count = u16_at(&end, 10) as usize;
    if count == 0 || count > MAX_ENTRIES {
        return Err(InspectionError::Limit);
    }
    let central = u32_at(&end, 16) as u64;
    if central + u32_at(&end, 12) as u64 != length - 22 {
        return Err(InspectionError::Archive);
    }
    seek(reader, central)?;
    let mut entries = Vec::with_capacity(count);
    let mut names = BTreeSet::new();
    let mut position = central;
    for _ in 0..count {
        if position + 46 > length - 22 {
            return Err(InspectionError::Archive);
        }
        let header = read::<46>(reader)?;
        let name_len = u16_at(&header, 28) as usize;
        if header[..4] != *b"PK\x01\x02"
            || name_len == 0
            || name_len > 255
            || u16_at(&header, 30) != 0
            || u16_at(&header, 32) != 0
            || u16_at(&header, 34) != 0
            || u16_at(&header, 10) != 0
        {
            return Err(InspectionError::Archive);
        }
        let version = u16_at(&header, 6);
        let flags = u16_at(&header, 8);
        let attributes = u32_at(&header, 38);
        let file_type = (attributes >> 16) & 0o170000;
        if ![10, 20].contains(&version)
            || flags & !0x0800 != 0
            || attributes & 0x10 != 0
            || (file_type != 0 && file_type != 0o100000)
            || u32_at(&header, 20) != u32_at(&header, 24)
        {
            return Err(InspectionError::Archive);
        }
        position += 46 + name_len as u64;
        if position > length - 22 {
            return Err(InspectionError::Archive);
        }
        let mut name = vec![0; name_len];
        reader
            .read_exact(&mut name)
            .map_err(|_| InspectionError::Read)?;
        if !name.is_ascii() {
            return Err(InspectionError::Archive);
        }
        let name = String::from_utf8(name).map_err(|_| InspectionError::Archive)?;
        let size = u32_at(&header, 24) as u64;
        validate_path(&name, size)?;
        if !names.insert(name.clone()) {
            return Err(InspectionError::Archive);
        }
        entries.push(Entry {
            name,
            size,
            version,
            flags,
            offset: u32_at(&header, 42) as u64,
            crc: u32_at(&header, 16),
            time: u16_at(&header, 12),
            date: u16_at(&header, 14),
        });
    }
    if position != length - 22 {
        return Err(InspectionError::Archive);
    }
    for required in [
        "release.json",
        "code/classes.dex",
        "resources.apk",
        "java-resources.jar",
        "resource-ledger.json",
    ] {
        if !names.contains(required) {
            return Err(InspectionError::Archive);
        }
    }
    let dexes: BTreeSet<_> = names.iter().filter_map(|n| dex_index(n)).collect();
    if dexes.iter().copied().ne(1..=dexes.len()) {
        return Err(InspectionError::Archive);
    }
    entries.sort_by_key(|e| e.offset);
    Ok((entries, central))
}

/// Caller owns stable bytes for the duration of inspection. No content is extracted.
/// The expected contract and trust MUST originate from a verified installer.
pub fn inspect<R: Read + Seek>(
    reader: &mut R,
    trust: &TrustPolicy,
    expected_contract: &str,
) -> Result<ArchiveInspection> {
    if !hash_valid(expected_contract) {
        return Err(InspectionError::Incompatible);
    }
    let length = reader
        .seek(SeekFrom::End(0))
        .map_err(|_| InspectionError::Read)?;
    let (entries, central) = directory(reader, length)?;
    let mut inventory = BTreeMap::new();
    let mut release_bytes = Vec::new();
    let mut nested = Vec::new();
    let mut component_content = 0;
    let mut position = 0;
    let mut buffer = [0; 64 * 1024];
    for entry in entries {
        // Exact contiguous coverage prevents overlap, gaps, prepended bytes and
        // local records hidden from the central directory.
        if entry.offset != position || position + 30 > central {
            return Err(InspectionError::Archive);
        }
        seek(reader, position)?;
        let local = read::<30>(reader)?;
        if local[..4] != *b"PK\x03\x04"
            || u16_at(&local, 4) != entry.version
            || u16_at(&local, 6) != entry.flags
            || u16_at(&local, 8) != 0
            || u16_at(&local, 10) != entry.time
            || u16_at(&local, 12) != entry.date
            || u32_at(&local, 14) != entry.crc
            || u32_at(&local, 18) as u64 != entry.size
            || u32_at(&local, 22) as u64 != entry.size
            || u16_at(&local, 26) as usize != entry.name.len()
            || u16_at(&local, 28) != 0
        {
            return Err(InspectionError::Archive);
        }
        position += 30 + entry.name.len() as u64 + entry.size;
        if position > central {
            return Err(InspectionError::Archive);
        }
        let mut name = vec![0; entry.name.len()];
        reader
            .read_exact(&mut name)
            .map_err(|_| InspectionError::Read)?;
        if name != entry.name.as_bytes() {
            return Err(InspectionError::Archive);
        }
        if entry.name == "resources.apk" || entry.name == "java-resources.jar" {
            nested.push((entry.name.clone(), position - entry.size, entry.size));
        } else if entry.name != "release.json" {
            component_content += entry.size;
        }
        let mut crc = crc32fast::Hasher::new();
        let mut hash = Sha256::new();
        let mut remaining = entry.size;
        while remaining > 0 {
            let amount = remaining.min(buffer.len() as u64) as usize;
            reader
                .read_exact(&mut buffer[..amount])
                .map_err(|_| InspectionError::Read)?;
            hash.update(&buffer[..amount]);
            crc.update(&buffer[..amount]);
            if entry.name == "release.json" {
                release_bytes.extend_from_slice(&buffer[..amount]);
            }
            remaining -= amount as u64;
        }
        if crc.finalize() != entry.crc {
            return Err(InspectionError::Integrity);
        }
        inventory.insert(
            entry.name.clone(),
            InventoryEntry {
                path: entry.name,
                size: entry.size,
                sha256: hex::encode(hash.finalize()),
            },
        );
    }
    if position != central {
        return Err(InspectionError::Archive);
    }
    let authenticated = trust.authenticate_release(&release_bytes)?;
    let release: ReleaseManifest = serde_json::from_value(authenticated.value().clone())
        .map_err(|_| InspectionError::Inventory)?;
    if release.version != 1
        || release.format_version != 1
        || release.runtime_abi != 1
        || release.application_id != trust.application_id()
        || release.shell_contract_id != expected_contract
        || !identifier(&release.release_id)
        || release.payload_version == 0
        || release.min_sdk == 0
        || (release.max_sdk != 0 && release.max_sdk < release.min_sdk)
    {
        return Err(InspectionError::Incompatible);
    }
    inventory.remove("release.json");
    if release.inventory.len() != inventory.len() {
        return Err(InspectionError::Inventory);
    }
    for (signed, actual) in release.inventory.iter().zip(inventory.values()) {
        if signed.path != actual.path
            || signed.size != actual.size
            || signed.sha256 != actual.sha256
        {
            return Err(InspectionError::Inventory);
        }
    }
    if release.ledger_sha256 != inventory["resource-ledger.json"].sha256 {
        return Err(InspectionError::Inventory);
    }
    let native: BTreeSet<_> = inventory
        .keys()
        .filter_map(|name| native_abi(name))
        .collect();
    let declared: BTreeSet<_> = release.abis.iter().map(String::as_str).collect();
    if native != declared || declared.len() != release.abis.len() {
        return Err(InspectionError::Incompatible);
    }
    if component_content > super::components::CONTENT_LIMIT {
        return Err(InspectionError::Limit);
    }
    let mut total_entries = 0;
    let mut components = Vec::new();
    for (path, start, size) in nested {
        components.push(super::components::scan(
            reader,
            start,
            size,
            &path,
            &mut total_entries,
            &mut component_content,
        )?);
    }
    seek(reader, 0)?;
    let mut archive_hash = Sha256::new();
    let mut remaining = length;
    while remaining > 0 {
        let amount = remaining.min(buffer.len() as u64) as usize;
        reader
            .read_exact(&mut buffer[..amount])
            .map_err(|_| InspectionError::Read)?;
        archive_hash.update(&buffer[..amount]);
        remaining -= amount as u64;
    }
    Ok(ArchiveInspection {
        archive_size: length,
        archive_sha256: hex::encode(archive_hash.finalize()),
        manifest_sha256: hex::encode(Sha256::digest(&release_bytes)),
        signing_key_id: authenticated.signing_key_id().into(),
        release,
        components,
    })
}
