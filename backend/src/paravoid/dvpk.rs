//! `bsdiff-deflate-v1` delta archives (Paravoid DVPK.md). This is an independent,
//! bounded decoder used to prove a generated patch before publication. It does
//! not authenticate releases: the reconstructed bytes are compared with an
//! already verified full VPK.
use flate2::{Decompress, FlushDecompress, Status};
use std::{fs::File, io, os::unix::fs::FileExt, path::Path};

pub const ALGORITHM: &str = "bsdiff-deflate-v1";
pub const MAGIC: &[u8; 8] = b"DVPKD001";
pub const MIN_PATCH_BYTES: u64 = 38;
pub const MAX_PATCH_BYTES: u64 = 256 * 1024 * 1024;
pub const MAX_OPERATIONS: u64 = 1_000_000;
pub const MAX_OFFERS: usize = 16;
const HEADER_BYTES: u64 = 32;
const CHUNK: usize = 64 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum DvpkError {
    #[error("Invalid DVPK patch")]
    Invalid,
    #[error("Reconstructed archive differs from the target")]
    Mismatch,
    #[error(transparent)]
    Io(#[from] io::Error),
}

/// The shell only uses a patch saving at least 20% of the full archive.
pub fn saves_enough(patch_size: u64, target_size: u64) -> bool {
    patch_size <= target_size - target_size.div_ceil(5)
}

/// One raw DEFLATE block of the patch file, decompressed on demand.
struct Block<'a> {
    file: &'a File,
    position: u64,
    end: u64,
    inflater: Decompress,
    input: Vec<u8>,
    start: usize,
    filled: usize,
    finished: bool,
}
impl<'a> Block<'a> {
    fn new(file: &'a File, start: u64, end: u64) -> Self {
        Self {
            file,
            position: start,
            end,
            inflater: Decompress::new(false),
            input: vec![0; CHUNK],
            start: 0,
            filled: 0,
            finished: false,
        }
    }

    /// Returns zero only at the end of the DEFLATE stream.
    fn read(&mut self, output: &mut [u8]) -> Result<usize, DvpkError> {
        if output.is_empty() {
            return Ok(0);
        }
        loop {
            if self.finished {
                return Ok(0);
            }
            if self.start == self.filled && self.position < self.end {
                let count = (self.end - self.position).min(self.input.len() as u64) as usize;
                self.file
                    .read_exact_at(&mut self.input[..count], self.position)?;
                self.position += count as u64;
                self.start = 0;
                self.filled = count;
            }
            let (before_in, before_out) = (self.inflater.total_in(), self.inflater.total_out());
            let status = self
                .inflater
                .decompress(
                    &self.input[self.start..self.filled],
                    output,
                    FlushDecompress::None,
                )
                .map_err(|_| DvpkError::Invalid)?;
            let consumed = (self.inflater.total_in() - before_in) as usize;
            let produced = (self.inflater.total_out() - before_out) as usize;
            self.start += consumed;
            if status == Status::StreamEnd {
                self.finished = true;
            }
            if produced > 0 {
                return Ok(produced);
            }
            if !self.finished && consumed == 0 {
                // Truncated stream, or no progress despite available input.
                return Err(DvpkError::Invalid);
            }
        }
    }

    fn read_exact(&mut self, mut output: &mut [u8]) -> Result<(), DvpkError> {
        while !output.is_empty() {
            let count = self.read(output)?;
            if count == 0 {
                return Err(DvpkError::Invalid);
            }
            output = &mut output[count..];
        }
        Ok(())
    }

    /// Require the stream end, with no unused decompressed or compressed bytes.
    fn finish(&mut self) -> Result<(), DvpkError> {
        if self.read(&mut [0])? != 0 || self.start != self.filled || self.position != self.end {
            return Err(DvpkError::Invalid);
        }
        Ok(())
    }
}

fn integer(bytes: &[u8]) -> i64 {
    i64::from_le_bytes(bytes.try_into().unwrap())
}

/// Reconstructs `base + patch` and requires byte-for-byte equality with `target`.
/// Memory use is bounded by fixed chunk buffers, not archive or stream sizes.
pub fn verify(base: &Path, patch: &Path, target: &Path) -> Result<(), DvpkError> {
    let (base, patch, target) = (File::open(base)?, File::open(patch)?, File::open(target)?);
    let patch_size = patch.metadata()?.len();
    let base_size = base.metadata()?.len();
    let target_size = target.metadata()?.len();
    if !(MIN_PATCH_BYTES..=MAX_PATCH_BYTES).contains(&patch_size)
        || !(1..=super::MAX_ARCHIVE_BYTES).contains(&base_size)
    {
        return Err(DvpkError::Invalid);
    }
    let mut header = [0; HEADER_BYTES as usize];
    patch.read_exact_at(&mut header, 0)?;
    let (controls, differences, size) = (
        integer(&header[8..16]),
        integer(&header[16..24]),
        integer(&header[24..32]),
    );
    if &header[..8] != MAGIC
        || controls < 2
        || differences < 2
        || (controls as u64).saturating_add(differences as u64) > patch_size - HEADER_BYTES - 2
        || size < 1
        || size as u64 > super::MAX_ARCHIVE_BYTES
    {
        return Err(DvpkError::Invalid);
    }
    if size as u64 != target_size {
        return Err(DvpkError::Mismatch);
    }
    let diff_start = HEADER_BYTES + controls as u64;
    let extra_start = diff_start + differences as u64;
    let mut control = Block::new(&patch, HEADER_BYTES, diff_start);
    let mut diff = Block::new(&patch, diff_start, extra_start);
    let mut extra = Block::new(&patch, extra_start, patch_size);
    let (mut produced, mut old, mut records) = (0_i64, 0_i64, 0_u64);
    let mut buffer = vec![0; CHUNK];
    let mut base_bytes = vec![0; CHUNK];
    let mut expected = vec![0; CHUNK];
    loop {
        let mut record = [0; 24];
        let first = control.read(&mut record)?;
        if first == 0 {
            break;
        }
        control.read_exact(&mut record[first..])?;
        records += 1;
        let (add, copy, seek) = (
            integer(&record[0..8]),
            integer(&record[8..16]),
            integer(&record[16..24]),
        );
        let remaining = size - produced;
        if records > MAX_OPERATIONS
            || remaining == 0
            || add < 0
            || copy < 0
            || add > remaining
            || copy > remaining - add
            || (add == 0 && copy == 0 && seek == 0)
        {
            return Err(DvpkError::Invalid);
        }
        let mut left = add;
        while left > 0 {
            let count = (left as usize).min(CHUNK);
            diff.read_exact(&mut buffer[..count])?;
            // Base positions outside the archive contribute zero.
            base_bytes[..count].fill(0);
            let first = old.max(0);
            let last = old
                .checked_add(count as i64)
                .ok_or(DvpkError::Invalid)?
                .min(base_size as i64);
            if first < last {
                let offset = (first - old) as usize;
                base.read_exact_at(
                    &mut base_bytes[offset..offset + (last - first) as usize],
                    first as u64,
                )?;
            }
            for (value, base) in buffer[..count].iter_mut().zip(&base_bytes[..count]) {
                *value = value.wrapping_add(*base);
            }
            compare(&target, produced, &buffer[..count], &mut expected)?;
            produced += count as i64;
            old += count as i64;
            left -= count as i64;
        }
        let mut left = copy;
        while left > 0 {
            let count = (left as usize).min(CHUNK);
            extra.read_exact(&mut buffer[..count])?;
            compare(&target, produced, &buffer[..count], &mut expected)?;
            produced += count as i64;
            left -= count as i64;
        }
        old = old.checked_add(seek).ok_or(DvpkError::Invalid)?;
    }
    if produced != size {
        return Err(DvpkError::Invalid);
    }
    control.finish()?;
    diff.finish()?;
    extra.finish()?;
    Ok(())
}

fn compare(
    target: &File,
    position: i64,
    actual: &[u8],
    expected: &mut [u8],
) -> Result<(), DvpkError> {
    let expected = &mut expected[..actual.len()];
    target.read_exact_at(expected, position as u64)?;
    if expected != actual {
        return Err(DvpkError::Mismatch);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::{write::DeflateEncoder, Compression};
    use std::io::Write;

    fn deflate(bytes: &[u8]) -> Vec<u8> {
        let mut encoder = DeflateEncoder::new(Vec::new(), Compression::best());
        encoder.write_all(bytes).unwrap();
        encoder.finish().unwrap()
    }
    fn patch(records: &[(i64, i64, i64)], diff: &[u8], extra: &[u8], size: i64) -> Vec<u8> {
        let control: Vec<u8> = records
            .iter()
            .flat_map(|(a, c, s)| [a.to_le_bytes(), c.to_le_bytes(), s.to_le_bytes()].concat())
            .collect();
        let blocks = [deflate(&control), deflate(diff), deflate(extra)];
        let mut out = MAGIC.to_vec();
        for value in [blocks[0].len() as i64, blocks[1].len() as i64, size] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        blocks.iter().for_each(|b| out.extend_from_slice(b));
        out
    }
    fn check(base: &[u8], patch: &[u8], target: &[u8]) -> Result<(), DvpkError> {
        let dir = tempfile::tempdir().unwrap();
        let paths = ["base", "patch", "target"].map(|n| dir.path().join(n));
        for (path, bytes) in paths.iter().zip([base, patch, target]) {
            std::fs::write(path, bytes).unwrap();
        }
        verify(&paths[0], &paths[1], &paths[2])
    }

    #[test]
    fn reconstructs_differences_literals_negative_seeks_and_outside_base_bytes() {
        let base: Vec<u8> = (0..200_000u32).map(|i| (i * 7 % 251) as u8).collect();
        let mut target = base[100_000..].to_vec();
        target[5] = target[5].wrapping_add(1);
        target.extend_from_slice(b"literal");
        target.extend_from_slice(&base[..1000]);
        target.extend_from_slice(&[9, 9, 9]);
        let mut diff = vec![0; 101_000];
        diff[5] = 1;
        // The last record reads positions 199_998, 199_999 and 200_000 (outside: zero).
        diff.extend_from_slice(&[
            9u8.wrapping_sub(base[199_998]),
            9u8.wrapping_sub(base[199_999]),
            9,
        ]);
        let records = [
            (0, 0, 100_000),
            (100_000, 7, -200_000),
            (1000, 0, 198_998),
            (3, 0, 0),
        ];
        let bytes = patch(&records, &diff, b"literal", target.len() as i64);
        check(&base, &bytes, &target).unwrap();

        // Seeking entirely beyond the base adds differences to zero.
        let target = [5, 6];
        check(
            &[1],
            &patch(&[(0, 0, 10), (2, 0, 0)], &[5, 6], b"", 2),
            &target,
        )
        .unwrap();
        assert!(check(&[1], &patch(&[(2, 0, 0)], &[5, 6], b"", 2), &target).is_err());
    }

    #[test]
    fn rejects_malformed_and_mismatching_patches() {
        let base = vec![1u8; 4096];
        let target = vec![2u8; 4096];
        let good = patch(&[(4096, 0, 0)], &[1u8; 4096], b"", 4096);
        check(&base, &good, &target).unwrap();
        // Different target bytes.
        assert!(matches!(
            check(&base, &good, &[3u8; 4096]),
            Err(DvpkError::Mismatch)
        ));
        // Trailing compressed bytes after the literal stream.
        let mut trailing = good.clone();
        trailing.push(0);
        assert!(check(&base, &trailing, &target).is_err());
        // Truncated literal stream.
        assert!(check(&base, &good[..good.len() - 1], &target).is_err());
        // Unused differences.
        let unused = patch(&[(4096, 0, 0)], &[1u8; 4097], b"", 4096);
        assert!(check(&base, &unused, &target).is_err());
        // Partial control record.
        let mut control = [4096i64, 0, 0]
            .iter()
            .flat_map(|v| v.to_le_bytes())
            .collect::<Vec<_>>();
        control.push(0);
        let blocks = [deflate(&control), deflate(&[1u8; 4096]), deflate(b"")];
        let mut partial = MAGIC.to_vec();
        for v in [blocks[0].len() as i64, blocks[1].len() as i64, 4096] {
            partial.extend_from_slice(&v.to_le_bytes());
        }
        blocks.iter().for_each(|b| partial.extend_from_slice(b));
        assert!(check(&base, &partial, &target).is_err());
        // Empty record, negative lengths, output overflow and a record after the end.
        for records in [
            vec![(0, 0, 0), (4096, 0, 0)],
            vec![(-1, 0, 0)],
            vec![(4097, 0, 0)],
            vec![(4096, 0, 0), (0, 0, 1)],
            vec![(4096, 0, i64::MAX), (0, 0, 1)],
        ] {
            let bytes = patch(&records, &[1u8; 4096], b"", 4096);
            assert!(check(&base, &bytes, &target).is_err(), "{records:?}");
        }
        // Wrong magic and an inconsistent declared target size.
        let mut magic = good.clone();
        magic[0] = b'B';
        assert!(check(&base, &magic, &target).is_err());
        let short = patch(&[(4096, 0, 0)], &[1u8; 4096], b"", 4095);
        assert!(check(&base, &short, &target).is_err());
        // Base cursor overflow.
        let overflow = patch(&[(1, 0, i64::MAX), (1, 0, 0)], &[1u8; 2], b"", 2);
        assert!(check(&base, &overflow, &[2, 2]).is_err());
    }

    #[test]
    fn savings_threshold_matches_the_shell() {
        assert!(saves_enough(80, 100));
        assert!(!saves_enough(81, 100));
        assert!(saves_enough(80, 101) && !saves_enough(81, 101));
    }
}
