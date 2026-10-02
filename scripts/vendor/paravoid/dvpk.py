#!/usr/bin/env python3
"""Reference backend encoder for bsdiff-deflate-v1. Inputs must already be trusted VPKs."""
import argparse
import bz2
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
import zlib

ALGORITHM = 'bsdiff-deflate-v1'
MAGIC = b'DVPKD001'
MAX_ARCHIVE = 1024 * 1024 * 1024
MAX_PATCH = 256 * 1024 * 1024
MAX_OPERATIONS = 1_000_000


def bsdiff_integer(data):
    if len(data) != 8:
        raise ValueError('Truncated BSDIFF integer')
    value = int.from_bytes(data, 'little')
    return -(value & ((1 << 63) - 1)) if value >> 63 else value


def deflate(data):
    encoder = zlib.compressobj(9, zlib.DEFLATED, -15)
    return encoder.compress(data) + encoder.flush()


def from_bsdiff(patch):
    """Convert BSDIFF40 sign-magnitude controls and bzip2 blocks to the DVPK wire format."""
    if len(patch) < 32 or patch[:8] != b'BSDIFF40':
        raise ValueError('Expected BSDIFF40')
    controls, differences, size = (bsdiff_integer(patch[i:i+8]) for i in (8, 16, 24))
    if not 1 <= size <= MAX_ARCHIVE or controls < 0 or differences < 0 or controls + differences > len(patch)-32:
        raise ValueError('Invalid BSDIFF bounds')
    control = bz2.decompress(patch[32:32+controls])
    diff = bz2.decompress(patch[32+controls:32+controls+differences])
    extra = bz2.decompress(patch[32+controls+differences:])
    if len(control) % 24 or len(control) > MAX_OPERATIONS*24 or len(diff)+len(extra) != size:
        raise ValueError('Invalid BSDIFF streams')
    control = b''.join(struct.pack('<q', bsdiff_integer(control[i:i+8])) for i in range(0, len(control), 8))
    blocks = [deflate(block) for block in (control, diff, extra)]
    result = MAGIC + struct.pack('<qqq', len(blocks[0]), len(blocks[1]), size) + b''.join(blocks)
    if len(result) > MAX_PATCH:
        raise ValueError('Delta exceeds wire limit; serve a full VPK')
    return result


def inflate(data, limit):
    decoder = zlib.decompressobj(-15)
    output = decoder.decompress(data, limit+1)
    if len(output) > limit or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError('Invalid DEFLATE block')
    return output


def apply(base, patch):
    """Independent host verifier; the Android implementation streams these operations."""
    if len(patch) < 38 or len(patch) > MAX_PATCH or patch[:8] != MAGIC:
        raise ValueError('Invalid DVPK header')
    controls, differences, size = struct.unpack('<qqq', patch[8:32])
    if not 1 <= len(base) <= MAX_ARCHIVE or not 1 <= size <= MAX_ARCHIVE or controls < 2 or differences < 2 or controls+differences > len(patch)-34:
        raise ValueError('Invalid DVPK bounds')
    control = inflate(patch[32:32+controls], MAX_OPERATIONS*24)
    diff = inflate(patch[32+controls:32+controls+differences], size)
    extra = inflate(patch[32+controls+differences:], size)
    if len(control) % 24:
        raise ValueError('Partial control record')
    output = bytearray()
    old = dp = ep = 0
    for add, copy, seek in struct.iter_unpack('<qqq', control):
        if len(output) >= size or add < 0 or copy < 0 or add+copy > size-len(output) or add == copy == seek == 0:
            raise ValueError('Invalid control record')
        if dp+add > len(diff) or ep+copy > len(extra):
            raise ValueError('Truncated data stream')
        if not -(1 << 63) <= old+add < (1 << 63):
            raise ValueError('Base seek overflow')
        output.extend((diff[dp+i] + (base[old+i] if 0 <= old+i < len(base) else 0)) & 255 for i in range(add))
        output.extend(extra[ep:ep+copy])
        dp += add; ep += copy; old += add+seek
        if not -(1 << 63) <= old < (1 << 63):
            raise ValueError('Base seek overflow')
    if len(output) != size or dp != len(diff) or ep != len(extra):
        raise ValueError('Unconsumed or missing data')
    return bytes(output)


def generate(base, target):
    if not 1 <= len(base) <= MAX_ARCHIVE or not 1 <= len(target) <= MAX_ARCHIVE:
        raise ValueError('VPK size limit')
    try:
        import bsdiff4
    except ImportError as error:
        raise RuntimeError('Reference encoding requires bsdiff4 (tested with 1.2.6)') from error
    patch = from_bsdiff(bsdiff4.diff(base, target))
    if apply(base, patch) != target:
        raise ValueError('Reconstructed target mismatch')
    return patch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base', type=Path)
    parser.add_argument('target', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.base.stat().st_size > MAX_ARCHIVE or args.target.stat().st_size > MAX_ARCHIVE:
        parser.error('VPK size limit')
    base, target = args.base.read_bytes(), args.target.read_bytes()
    patch = generate(base, target)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=args.output.parent, prefix='.dvpk-', delete=False) as output:
            temporary = Path(output.name)
            output.write(patch); output.flush(); os.fsync(output.fileno())
        os.replace(temporary, args.output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print(json.dumps({'delta': {'algorithm': ALGORITHM, 'baseArchiveSha256': hashlib.sha256(base).hexdigest(),
        'baseArchiveSize': len(base), 'patchSha256': hashlib.sha256(patch).hexdigest(), 'patchSize': len(patch)},
        'target': {'archiveSha256': hashlib.sha256(target).hexdigest(), 'archiveSize': len(target)}}, sort_keys=True))


if __name__ == '__main__':
    main()
