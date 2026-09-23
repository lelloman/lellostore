//! Read only the bounded v1 credential carrier. Developer signature verification
//! is separately mandatory before/after personalization (Android apksigner).
use super::{VerificationError as Error, MAX_ARCHIVE_BYTES, MAX_GRANT_BYTES};
use std::io::{Read, Seek, SeekFrom};
const MAGIC: &[u8] = b"APK Sig Block 42";
pub const GRANT_ID: u32 = 0x50564132;
fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes(b[at..at + 2].try_into().unwrap())
}
fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}
fn u64_at(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}
pub struct Carrier {
    pub grant: Option<Vec<u8>>,
    pub signatures: std::collections::BTreeMap<u32, [u8; 32]>,
    pub personalization_compatible: bool,
}
pub fn read<R: Read + Seek>(reader: &mut R) -> Result<Vec<u8>, Error> {
    inspect(reader)?
        .grant
        .filter(|g| !g.is_empty())
        .ok_or(Error::Malformed)
}
pub fn inspect<R: Read + Seek>(reader: &mut R) -> Result<Carrier, Error> {
    use sha2::{Digest, Sha256};
    let length = reader
        .seek(SeekFrom::End(0))
        .map_err(|_| Error::Malformed)?;
    if length > MAX_ARCHIVE_BYTES {
        return Err(Error::LimitExceeded);
    }
    if length < 22 {
        return Err(Error::Malformed);
    }
    let tail_size = length.min(65557) as usize;
    reader
        .seek(SeekFrom::End(-(tail_size as i64)))
        .map_err(|_| Error::Malformed)?;
    let mut tail = vec![0; tail_size];
    reader.read_exact(&mut tail).map_err(|_| Error::Malformed)?;
    let candidates: Vec<_> = (0..=tail_size - 22)
        .filter(|&i| {
            tail[i..i + 4] == *b"PK\x05\x06" && i + 22 + u16_at(&tail, i + 20) as usize == tail_size
        })
        .collect();
    if candidates.len() != 1 {
        return Err(Error::Malformed);
    }
    let end = &tail[candidates[0]..];
    let central = u32_at(end, 16) as u64;
    if u16_at(end, 4) != 0
        || u16_at(end, 6) != 0
        || u16_at(end, 8) != u16_at(end, 10)
        || u16_at(end, 10) == 65535
        || central + u32_at(end, 12) as u64 != length - tail_size as u64 + candidates[0] as u64
        || central < 32
    {
        return Err(Error::Malformed);
    }
    reader
        .seek(SeekFrom::Start(central - 24))
        .map_err(|_| Error::Malformed)?;
    let mut footer = [0; 24];
    reader
        .read_exact(&mut footer)
        .map_err(|_| Error::Malformed)?;
    let size = u64_at(&footer, 0);
    if &footer[8..] != MAGIC || !(24..=16 * 1024 * 1024).contains(&size) || size + 8 > central {
        return Err(Error::Malformed);
    }
    reader
        .seek(SeekFrom::Start(central - size - 8))
        .map_err(|_| Error::Malformed)?;
    let mut block = vec![0; (size + 8) as usize];
    reader
        .read_exact(&mut block)
        .map_err(|_| Error::Malformed)?;
    if u64_at(&block, 0) != size {
        return Err(Error::Malformed);
    }
    let mut at = 8;
    let stop = block.len() - 24;
    let mut ids = std::collections::HashSet::new();
    let mut grant = None;
    let mut signatures = std::collections::BTreeMap::new();
    while at < stop {
        if stop - at < 12 {
            return Err(Error::Malformed);
        }
        let count = u64_at(&block, at);
        if count < 4 || count > (stop - at - 8) as u64 {
            return Err(Error::Malformed);
        }
        let id = u32_at(&block, at + 8);
        if ids.len() >= 1024 {
            return Err(Error::LimitExceeded);
        }
        if !ids.insert(id) {
            return Err(Error::Malformed);
        }
        if id == 0x7109871a || id == 0xf05368c0 {
            signatures.insert(
                id,
                Sha256::digest(&block[at + 12..at + 8 + count as usize]).into(),
            );
        }
        if id == GRANT_ID {
            if count - 4 > MAX_GRANT_BYTES as u64 {
                return Err(Error::LimitExceeded);
            }
            grant = Some(block[at + 12..at + 8 + count as usize].to_vec());
        }
        at += 8 + count as usize;
    }
    if at != stop || (!ids.contains(&0x7109871a) && !ids.contains(&0xf05368c0)) {
        return Err(Error::Malformed);
    }
    let personalization_compatible = ids
        .iter()
        .all(|id| matches!(*id, 0x7109871a | 0xf05368c0 | 0x42726577 | GRANT_ID));
    Ok(Carrier {
        grant,
        signatures,
        personalization_compatible,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounds_signing_block_entry_count_before_allocating_an_unbounded_index() {
        for count in [1024, 1025] {
            // Carrier-only fixture; APK developer signatures are checked separately.
            let size = (count * 12 + 24) as u64;
            let mut apk = size.to_le_bytes().to_vec();
            for i in 0..count {
                apk.extend_from_slice(&4_u64.to_le_bytes());
                apk.extend_from_slice(&(if i == 0 { 0x7109871a } else { i as u32 }).to_le_bytes());
            }
            apk.extend_from_slice(&size.to_le_bytes());
            apk.extend_from_slice(MAGIC);
            let mut end = [0; 22];
            end[..4].copy_from_slice(b"PK\x05\x06");
            end[16..20].copy_from_slice(&(apk.len() as u32).to_le_bytes());
            apk.extend_from_slice(&end);
            let result = inspect(&mut std::io::Cursor::new(apk));
            if count == 1024 {
                assert!(result.is_ok());
            } else {
                assert!(matches!(result, Err(Error::LimitExceeded)));
            }
        }
    }
}
