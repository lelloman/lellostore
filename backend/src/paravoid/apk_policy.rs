//! Bounded policy extraction. The caller must independently verify APK signatures.
use super::{
    shell_policy::{ShellPolicyDocument, APK_PATH, MAX_POLICY_BYTES},
    VerificationError as Error, MAX_ARCHIVE_BYTES,
};
use std::{
    collections::HashSet,
    io::{Read, Seek, SeekFrom},
};
fn u16_at(b: &[u8], n: usize) -> u16 {
    u16::from_le_bytes(b[n..n + 2].try_into().unwrap())
}
fn u32_at(b: &[u8], n: usize) -> u32 {
    u32::from_le_bytes(b[n..n + 4].try_into().unwrap())
}

pub fn read<R: Read + Seek>(reader: &mut R) -> Result<ShellPolicyDocument, Error> {
    let size = reader
        .seek(SeekFrom::End(0))
        .map_err(|_| Error::Malformed)?;
    if !(22..=MAX_ARCHIVE_BYTES).contains(&size) {
        return Err(Error::LimitExceeded);
    }
    let tail_size = size.min(65557) as usize;
    reader
        .seek(SeekFrom::End(-(tail_size as i64)))
        .map_err(|_| Error::Malformed)?;
    let mut tail = vec![0; tail_size];
    reader.read_exact(&mut tail).map_err(|_| Error::Malformed)?;
    let ends: Vec<_> = (0..=tail.len() - 22)
        .filter(|&i| {
            tail[i..i + 4] == *b"PK\x05\x06"
                && i + 22 + u16_at(&tail, i + 20) as usize == tail.len()
        })
        .collect();
    if ends.len() != 1 {
        return Err(Error::Malformed);
    }
    let end = &tail[ends[0]..];
    let count = u16_at(end, 10) as usize;
    let central = u32_at(end, 16) as u64;
    let central_size = u32_at(end, 12) as u64;
    if count == 65535
        || u16_at(end, 4) != 0
        || u16_at(end, 6) != 0
        || u16_at(end, 8) as usize != count
        || central_size > 32 * 1024 * 1024
        || central + central_size != size - tail_size as u64 + ends[0] as u64
    {
        return Err(Error::Malformed);
    }
    // zip::ZipArchive collapses duplicate names. Detect them before using it.
    reader
        .seek(SeekFrom::Start(central))
        .map_err(|_| Error::Malformed)?;
    let mut names = HashSet::new();
    let mut consumed = 0_u64;
    let mut found = false;
    for _ in 0..count {
        let mut header = [0; 46];
        reader
            .read_exact(&mut header)
            .map_err(|_| Error::Malformed)?;
        let name_size = u16_at(&header, 28) as usize;
        let rest = u16_at(&header, 30) as u64 + u16_at(&header, 32) as u64;
        consumed += 46 + name_size as u64 + rest;
        if &header[..4] != b"PK\x01\x02" || consumed > central_size || u16_at(&header, 34) != 0 {
            return Err(Error::Malformed);
        }
        let mut name = vec![0; name_size];
        reader.read_exact(&mut name).map_err(|_| Error::Malformed)?;
        if std::str::from_utf8(&name).is_err() || !names.insert(name.clone()) {
            return Err(Error::Malformed);
        }
        found |= name == APK_PATH.as_bytes();
        reader
            .seek(SeekFrom::Current(rest as i64))
            .map_err(|_| Error::Malformed)?;
    }
    if consumed != central_size || !found {
        return Err(Error::Malformed);
    }
    let mut archive = zip::ZipArchive::new(reader).map_err(|_| Error::Malformed)?;
    let file = archive.by_name(APK_PATH).map_err(|_| Error::Malformed)?;
    let expected = file.size();
    if expected == 0
        || expected > MAX_POLICY_BYTES as u64
        || file.is_dir()
        || file.unix_mode().is_some_and(|m| m & 0o170000 == 0o120000)
    {
        return Err(Error::LimitExceeded);
    }
    let mut bytes = Vec::new();
    file.take(MAX_POLICY_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| Error::Malformed)?;
    if bytes.len() as u64 != expected {
        return Err(Error::Malformed);
    }
    ShellPolicyDocument::parse(&bytes)
}
