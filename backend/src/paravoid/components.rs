//! Bounded ZIP component scanning. Does not interpret Android resource tables,
//! DEX/ELF or resource reservations; those remain distinct compatibility checks.
use super::archive::InspectionError;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::io::{Read, Seek, SeekFrom};

type Result<T> = std::result::Result<T, InspectionError>;
pub(super) const CONTENT_LIMIT: u64 = 2 * 1024 * 1024 * 1024;
#[derive(Debug, Serialize)]
pub struct ComponentEntry {
    pub path: String,
    pub size: u64,
    pub sha256: String,
}
#[derive(Debug, Serialize)]
pub struct ComponentInspection {
    pub path: String,
    pub entries: Vec<ComponentEntry>,
}

pub(super) struct Region<'a, R> {
    reader: &'a mut R,
    start: u64,
    length: u64,
    position: u64,
}
impl<'a, R> Region<'a, R> {
    pub(super) fn new(reader: &'a mut R, start: u64, length: u64) -> Self {
        Self {
            reader,
            start,
            length,
            position: 0,
        }
    }
}
impl<R: Read + Seek> Read for Region<'_, R> {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        let count = (self.length - self.position).min(buffer.len() as u64) as usize;
        self.reader
            .seek(SeekFrom::Start(self.start + self.position))?;
        let count = self.reader.read(&mut buffer[..count])?;
        self.position += count as u64;
        Ok(count)
    }
}
impl<R: Seek> Seek for Region<'_, R> {
    fn seek(&mut self, from: SeekFrom) -> std::io::Result<u64> {
        let target = match from {
            SeekFrom::Start(n) => n as i128,
            SeekFrom::End(n) => self.length as i128 + n as i128,
            SeekFrom::Current(n) => self.position as i128 + n as i128,
        };
        if target < 0 || target > self.length as i128 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "ZIP component bounds",
            ));
        }
        self.position = target as u64;
        Ok(self.position)
    }
}
fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes(b[at..at + 2].try_into().unwrap())
}
fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}
fn extras(bytes: &[u8]) -> Result<()> {
    let mut at = 0;
    while at < bytes.len() {
        if bytes.len() - at < 4 {
            return Err(InspectionError::Archive);
        }
        let tag = u16_at(bytes, at);
        let length = u16_at(bytes, at + 2) as usize;
        if tag == 1 || bytes.len() - at - 4 < length {
            return Err(InspectionError::Archive);
        }
        at += 4 + length;
    }
    Ok(())
}
fn safe_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && !name.starts_with('/')
        && !name.contains('\\')
        && !name.contains(':')
        && !name.chars().any(char::is_control)
        && name
            .trim_end_matches('/')
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

pub(super) fn scan<R: Read + Seek>(
    reader: &mut R,
    start: u64,
    length: u64,
    path: &str,
    total_entries: &mut usize,
    total_content: &mut u64,
) -> Result<ComponentInspection> {
    if length < 22 {
        return Err(InspectionError::Archive);
    }
    let mut region = Region {
        reader,
        start,
        length,
        position: 0,
    };
    // Check counts and the absence of ZIP64 before letting the ZIP reader allocate
    // central-directory state. At most the ZIP comment ceiling is buffered.
    let tail_size = length.min(65557) as usize;
    region
        .seek(SeekFrom::End(-(tail_size as i64)))
        .map_err(|_| InspectionError::Read)?;
    let mut tail = vec![0; tail_size];
    region
        .read_exact(&mut tail)
        .map_err(|_| InspectionError::Read)?;
    let eocd = (0..=tail.len() - 22)
        .rev()
        .find(|&i| {
            tail[i..i + 4] == *b"PK\x05\x06"
                && i + 22 + u16_at(&tail, i + 20) as usize == tail.len()
        })
        .ok_or(InspectionError::Archive)?;
    let end = &tail[eocd..];
    let count = u16_at(end, 10) as usize;
    let central = u32_at(end, 16) as u64;
    if count == 65535
        || u16_at(end, 4) != 0
        || u16_at(end, 6) != 0
        || u16_at(end, 8) as usize != count
        || central + u32_at(end, 12) as u64 != length - tail.len() as u64 + eocd as u64
    {
        return Err(InspectionError::Archive);
    }
    *total_entries += count;
    if *total_entries > 100_000 {
        return Err(InspectionError::Limit);
    }
    // Scan central/local extra fields before the library can normalize away ZIP64.
    region
        .seek(SeekFrom::Start(central))
        .map_err(|_| InspectionError::Read)?;
    let mut offset = central;
    let mut ranges = Vec::with_capacity(count);
    for _ in 0..count {
        let mut header = [0; 46];
        region
            .read_exact(&mut header)
            .map_err(|_| InspectionError::Read)?;
        if header[..4] != *b"PK\x01\x02"
            || u16_at(&header, 34) != 0
            || u32_at(&header, 20) == u32::MAX
            || u32_at(&header, 24) == u32::MAX
            || u32_at(&header, 42) == u32::MAX
        {
            return Err(InspectionError::Archive);
        }
        let flags = u16_at(&header, 8);
        let method = u16_at(&header, 10);
        if u16_at(&header, 6) > 20
            || flags & !0x080e != 0
            || ![0, 8].contains(&method)
            || (method == 0 && flags & 6 != 0)
        {
            return Err(InspectionError::Archive);
        }
        let central_name_offset = offset + 46;
        let name_len = u16_at(&header, 28) as usize;
        if name_len == 0 || name_len > 255 {
            return Err(InspectionError::Archive);
        }
        let extra_len = u16_at(&header, 30) as usize;
        offset += 46 + name_len as u64 + extra_len as u64 + u16_at(&header, 32) as u64;
        if offset > central + u32_at(end, 12) as u64 {
            return Err(InspectionError::Archive);
        }
        region
            .seek(SeekFrom::Current(name_len as i64))
            .map_err(|_| InspectionError::Read)?;
        let mut extra = vec![0; extra_len];
        region
            .read_exact(&mut extra)
            .map_err(|_| InspectionError::Read)?;
        extras(&extra)?;
        let local_offset = u32_at(&header, 42) as u64;
        if local_offset + 30 > central {
            return Err(InspectionError::Archive);
        }
        region
            .seek(SeekFrom::Start(local_offset))
            .map_err(|_| InspectionError::Read)?;
        let mut local = [0; 30];
        region
            .read_exact(&mut local)
            .map_err(|_| InspectionError::Read)?;
        if local[..4] != *b"PK\x03\x04"
            || u16_at(&local, 26) as usize != name_len
            || local[4..14] != header[6..16]
        {
            return Err(InspectionError::Archive);
        }
        if u16_at(&local, 6) & 8 == 0 && local[14..26] != header[16..28] {
            return Err(InspectionError::Archive);
        }
        // Compare raw names, not the library's decoded/normalized representation.
        let mut local_name = vec![0; name_len];
        region
            .read_exact(&mut local_name)
            .map_err(|_| InspectionError::Read)?;
        let local_extra_len = u16_at(&local, 28) as usize;
        if local_offset + 30 + name_len as u64 + local_extra_len as u64 + u32_at(&header, 20) as u64
            > central
        {
            return Err(InspectionError::Archive);
        }
        let data_end = local_offset
            + 30
            + name_len as u64
            + local_extra_len as u64
            + u32_at(&header, 20) as u64;
        let mut local_extra = vec![0; local_extra_len];
        region
            .read_exact(&mut local_extra)
            .map_err(|_| InspectionError::Read)?;
        extras(&local_extra)?;
        let mut extent_end = data_end;
        if flags & 8 != 0 {
            if data_end + 12 > central {
                return Err(InspectionError::Archive);
            }
            region
                .seek(SeekFrom::Start(data_end))
                .map_err(|_| InspectionError::Read)?;
            let mut descriptor = [0; 12];
            region
                .read_exact(&mut descriptor)
                .map_err(|_| InspectionError::Read)?;
            if descriptor == header[16..28] {
                extent_end += 12;
            } else {
                if descriptor[..4] != *b"PK\x07\x08" || data_end + 16 > central {
                    return Err(InspectionError::Archive);
                }
                let mut last = [0; 4];
                region
                    .read_exact(&mut last)
                    .map_err(|_| InspectionError::Read)?;
                if descriptor[4..12] != header[16..24] || last != header[24..28] {
                    return Err(InspectionError::Archive);
                }
                extent_end += 16;
            }
        }
        ranges.push((local_offset, extent_end));

        region
            .seek(SeekFrom::Start(central_name_offset))
            .map_err(|_| InspectionError::Read)?;
        let mut central_name = vec![0; name_len];
        region
            .read_exact(&mut central_name)
            .map_err(|_| InspectionError::Read)?;
        if central_name != local_name || std::str::from_utf8(&central_name).is_err() {
            return Err(InspectionError::Archive);
        }
        region
            .seek(SeekFrom::Start(offset))
            .map_err(|_| InspectionError::Read)?;
    }
    if offset != central + u32_at(end, 12) as u64 {
        return Err(InspectionError::Archive);
    }
    ranges.sort_unstable();
    if ranges.first().is_some_and(|range| range.0 != 0)
        || ranges
            .last()
            .map_or(central != 0, |range| range.1 != central)
        || ranges.windows(2).any(|pair| pair[0].1 != pair[1].0)
    {
        return Err(InspectionError::Archive);
    }
    let mut archive = zip::ZipArchive::new(region).map_err(|_| InspectionError::Archive)?;
    if archive.len() != count || archive.offset() != 0 {
        return Err(InspectionError::Archive);
    }
    let mut entries = Vec::with_capacity(count);
    let mut buffer = [0; 64 * 1024];
    for index in 0..archive.len() {
        let mut file = archive
            .by_index(index)
            .map_err(|_| InspectionError::Archive)?;
        let mode = file.unix_mode().unwrap_or(0) & 0o170000;
        if file.encrypted()
            || file.is_symlink()
            || ![0, 0o100000, 0o040000].contains(&mode)
            || !safe_name(file.name())
            || !matches!(
                file.compression(),
                zip::CompressionMethod::Stored | zip::CompressionMethod::Deflated
            )
        {
            return Err(InspectionError::Archive);
        }
        if path == "java-resources.jar"
            && (file.name().ends_with(".class") || file.name().ends_with(".dex"))
        {
            return Err(InspectionError::Archive);
        }
        if file.size() > CONTENT_LIMIT - *total_content {
            return Err(InspectionError::Limit);
        }
        let mut size = 0;
        let mut hash = Sha256::new();
        loop {
            let read = file
                .read(&mut buffer)
                .map_err(|_| InspectionError::Integrity)?;
            if read == 0 {
                break;
            }
            size += read as u64;
            *total_content += read as u64;
            if *total_content > CONTENT_LIMIT || size > file.size() {
                return Err(InspectionError::Limit);
            }
            hash.update(&buffer[..read]);
        }
        if size != file.size() || (file.is_dir() && size != 0) {
            return Err(InspectionError::Integrity);
        }
        entries.push(ComponentEntry {
            path: file.name().into(),
            size,
            sha256: hex::encode(hash.finalize()),
        });
    }
    entries.sort_by(|a, b| a.path.cmp(&b.path));
    if entries.windows(2).any(|pair| pair[0].path == pair[1].path) {
        return Err(InspectionError::Archive);
    }
    Ok(ComponentInspection {
        path: path.into(),
        entries,
    })
}
