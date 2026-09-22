//! Authenticated component-format and pinned resource-reservation checks.
//! This does not establish that a policy came from an APK, or prove Android startup.
use super::{
    archive::{self, ArchiveInspection, InspectionError},
    parse_json, TrustPolicy,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    io::{Read, Seek, SeekFrom},
};
type Result<T> = std::result::Result<T, InspectionError>;
const LEDGER_LIMIT: usize = 16 * 1024 * 1024;

#[derive(Serialize)]
pub struct CompatibilityInspection {
    pub archive: ArchiveInspection,
    pub resource_reservations: usize,
    pub dex_files: usize,
    pub native_libraries: usize,
}

/// Reservations must come from independently verified, installed shell metadata.
/// A caller-provided reservations file is useful preflight, not APK registration.
pub fn inspect<R: Read + Seek>(
    reader: &mut R,
    trust: &TrustPolicy,
    contract: &str,
    reservations: &BTreeMap<String, String>,
) -> Result<CompatibilityInspection> {
    super::shell_policy::validate_reservations(reservations)?;
    let inspected = archive::inspect(reader, trust, contract)?;
    let mut dex_files = 0;
    let mut native_libraries = 0;
    let mut resources = None;
    let mut outer = zip::ZipArchive::new(&mut *reader).map_err(|_| InspectionError::Archive)?;
    for item in &inspected.release.inventory {
        let mut file = outer
            .by_name(&item.path)
            .map_err(|_| InspectionError::Archive)?;
        if item.path.starts_with("code/") {
            check_dex(&mut file, item.size)?;
            dex_files += 1;
        } else if item.path.starts_with("native/") {
            let abi = item
                .path
                .split('/')
                .nth(1)
                .ok_or(InspectionError::Incompatible)?;
            check_elf(&mut file, abi)?;
            native_libraries += 1;
        } else if item.path == "resource-ledger.json" {
            let mut bytes = Vec::new();
            file.take(LEDGER_LIMIT as u64 + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| InspectionError::Read)?;
            check_ledger(&bytes, trust.application_id(), reservations)?;
        } else if item.path == "resources.apk" {
            resources = Some((file.data_start(), file.size()));
        }
    }
    drop(outer);
    let (start, length) = resources.ok_or(InspectionError::Inventory)?;
    {
        let region = super::components::Region::new(reader, start, length);
        let mut nested = zip::ZipArchive::new(region).map_err(|_| InspectionError::Archive)?;
        let mut table = nested
            .by_name("resources.arsc")
            .map_err(|_| InspectionError::Incompatible)?;
        let mut header = [0; 12];
        table
            .read_exact(&mut header)
            .map_err(|_| InspectionError::Incompatible)?;
        if u32_at(&header, 0) != 0x000c0002 || u32_at(&header, 4) as u64 != table.size() {
            return Err(InspectionError::Incompatible);
        }
    }
    // Detect a source modified while the second pass checked its components.
    reader
        .seek(SeekFrom::Start(0))
        .map_err(|_| InspectionError::Read)?;
    let mut digest = Sha256::new();
    let mut buffer = [0; 65536];
    let mut length = 0;
    loop {
        let count = reader
            .read(&mut buffer)
            .map_err(|_| InspectionError::Read)?;
        if count == 0 {
            break;
        }
        length += count as u64;
        if length > inspected.archive_size {
            return Err(InspectionError::Integrity);
        }
        digest.update(&buffer[..count]);
    }
    if length != inspected.archive_size
        || hex::encode(digest.finalize()) != inspected.archive_sha256
    {
        return Err(InspectionError::Integrity);
    }
    Ok(CompatibilityInspection {
        archive: inspected,
        resource_reservations: reservations.len(),
        dex_files,
        native_libraries,
    })
}
fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}
fn check_dex(reader: &mut impl Read, size: u64) -> Result<()> {
    let mut header = [0; 112];
    reader
        .read_exact(&mut header)
        .map_err(|_| InspectionError::Incompatible)?;
    if ![b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0"]
        .contains(&(&header[..8]).try_into().unwrap())
        || u32_at(&header, 32) as u64 != size
        || u32_at(&header, 36) != 112
        || u32_at(&header, 40) != 0x12345678
    {
        return Err(InspectionError::Incompatible);
    }
    let mut adler = adler2::Adler32::new();
    adler.write_slice(&header[12..]);
    let mut signature = ring::digest::Context::new(&ring::digest::SHA1_FOR_LEGACY_USE_ONLY);
    signature.update(&header[32..]);
    let mut buffer = [0; 65536];
    let mut actual = 112_u64;
    loop {
        let count = reader
            .read(&mut buffer)
            .map_err(|_| InspectionError::Read)?;
        if count == 0 {
            break;
        }
        actual += count as u64;
        if actual > size {
            return Err(InspectionError::Integrity);
        }
        adler.write_slice(&buffer[..count]);
        signature.update(&buffer[..count]);
    }
    if actual != size
        || adler.checksum() != u32_at(&header, 8)
        || signature.finish().as_ref() != &header[12..32]
    {
        return Err(InspectionError::Integrity);
    }
    Ok(())
}
fn check_elf(reader: &mut impl Read, abi: &str) -> Result<()> {
    let mut header = [0; 20];
    reader
        .read_exact(&mut header)
        .map_err(|_| InspectionError::Incompatible)?;
    let (class, machine) = match abi {
        "arm64-v8a" => (2, 183),
        "armeabi-v7a" => (1, 40),
        "x86_64" => (2, 62),
        "x86" => (1, 3),
        _ => return Err(InspectionError::Incompatible),
    };
    if &header[..4] != b"\x7fELF"
        || header[4] != class
        || header[5] != 1
        || header[6] != 1
        || header[16..20] != [3, 0, machine, 0]
    {
        return Err(InspectionError::Incompatible);
    }
    Ok(())
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Ledger {
    version: u64,
    application_id: String,
    entries: Vec<Reservation>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Reservation {
    name: String,
    id: String,
    removed: bool,
}
pub(super) fn resource_name(name: &str) -> bool {
    let Some((kind, name)) = name.split_once('/') else {
        return false;
    };
    kind.as_bytes().first().is_some_and(u8::is_ascii_lowercase)
        && kind
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
        && name
            .as_bytes()
            .first()
            .is_some_and(|b| b.is_ascii_alphabetic() || *b == b'_')
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_.".contains(&b))
}
fn check_ledger(bytes: &[u8], app: &str, reservations: &BTreeMap<String, String>) -> Result<()> {
    let json = parse_json(bytes, LEDGER_LIMIT)?;
    super::json::ordinary_strings(&json)?;
    let ledger: Ledger = serde_json::from_value(json).map_err(|_| InspectionError::Incompatible)?;
    if ledger.version != 1 || ledger.application_id != app || ledger.entries.len() > 100_000 {
        return Err(InspectionError::Incompatible);
    }
    let mut names = BTreeMap::new();
    let mut ids = BTreeSet::new();
    let mut types = BTreeMap::new();
    let mut type_ids = BTreeMap::new();
    for entry in ledger.entries {
        if !resource_name(&entry.name)
            || entry.id.len() != 10
            || !entry.id.starts_with("0x7f")
            || !entry.id[4..]
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || !ids.insert(entry.id.clone())
        {
            return Err(InspectionError::Incompatible);
        }
        let kind = entry.name.split('/').next().unwrap().to_string();
        let type_id = entry.id[4..6].to_string();
        if type_id == "00"
            || types
                .insert(kind.clone(), type_id.clone())
                .is_some_and(|old| old != type_id)
            || type_ids
                .insert(type_id, kind.clone())
                .is_some_and(|old| old != kind)
        {
            return Err(InspectionError::Incompatible);
        }
        // Removed entries remain reserved, preventing ID reuse by later payloads.
        let _removed = entry.removed;
        if names.insert(entry.name, entry.id).is_some() {
            return Err(InspectionError::Incompatible);
        }
    }
    if reservations
        .iter()
        .any(|(name, id)| names.get(name) != Some(id))
    {
        return Err(InspectionError::Incompatible);
    }
    Ok(())
}
