#!/usr/bin/env python3
"""Preserve developer v2/v3 signatures while inserting an authenticated v1 grant.

Called by personalize.sh with compiled shared verifier classes. No private signing
keys are required. The candidate carrier ID still needs ecosystem collision review.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile

MAGIC = b"APK Sig Block 42"
GRANT_ID = 0x50564132
PADDING_ID = 0x42726577
V2_ID, V3_ID = 0x7109871A, 0xF05368C0
MAX_BLOCK = 16 * 1024 * 1024
MAX_APK = 1024 * 1024 * 1024
MAX_GRANT = 16 * 1024


def inspect(apk):
    if len(apk) > MAX_APK:
        raise ValueError("APK exceeds tool bound")
    candidates = [offset for offset in range(max(0, len(apk) - 65557), len(apk) - 21)
                  if apk[offset:offset + 4] == b"PK\x05\x06"
                  and offset + 22 + struct.unpack_from("<H", apk, offset + 20)[0] == len(apk)]
    if len(candidates) != 1:
        raise ValueError("Unsupported terminal ZIP record")
    end = candidates[0]
    disk, directory_disk, disk_count, count, size, directory = struct.unpack_from("<HHHHII", apk, end + 4)
    if disk or directory_disk or disk_count != count or count == 65535 or directory + size != end:
        raise ValueError("Unsupported ZIP layout")
    if directory < 32 or apk[directory - 16:directory] != MAGIC:
        raise ValueError("APK signing block missing")
    length = struct.unpack_from("<Q", apk, directory - 24)[0]
    start = directory - length - 8
    if not 24 <= length <= MAX_BLOCK or start < 0 or struct.unpack_from("<Q", apk, start)[0] != length:
        raise ValueError("Invalid signing block size")
    cursor, pairs = start + 8, {}
    while cursor < directory - 24:
        if cursor + 12 > directory - 24:
            raise ValueError("Truncated signing block")
        pair_size, entry_id = struct.unpack_from("<QI", apk, cursor)
        cursor += 8
        if pair_size < 4 or pair_size > directory - 24 - cursor or entry_id in pairs:
            raise ValueError("Invalid or duplicate signing entry")
        if entry_id not in (V2_ID, V3_ID, PADDING_ID, GRANT_ID):
            raise ValueError("Unsupported signing layout (including source stamps/v3.1)")
        pairs[entry_id] = apk[cursor + 4:cursor + pair_size]
        cursor += pair_size
    if not ({V2_ID, V3_ID} & pairs.keys()):
        raise ValueError("v2/v3 signature required")
    return start, directory, end, pairs


def insert(apk, grant):
    if not 0 < len(grant) <= MAX_GRANT:
        raise ValueError("Invalid grant envelope size")
    start, directory, end, pairs = inspect(apk)
    if GRANT_ID in pairs:
        raise ValueError("Personalize the original unprovisioned APK")
    pairs.pop(PADDING_ID, None)
    pairs[GRANT_ID] = grant
    body = b"".join(struct.pack("<QI", len(value) + 4, key) + value for key, value in pairs.items())
    target = directory - start
    while target - (32 + len(body)) < 12:
        target += 4096
    if target - 8 > MAX_BLOCK:
        raise ValueError("Personalized signing block exceeds tool bound")
    padding = target - 32 - len(body) - 12
    body += struct.pack("<QI", 4 + padding, PADDING_ID) + bytes(padding)
    size = 24 + len(body)
    block = struct.pack("<Q", size) + body + struct.pack("<Q", size) + MAGIC
    tail = bytearray(apk[directory:])
    struct.pack_into("<I", tail, end - directory + 16, start + len(block))
    return apk[:start] + block + tail


def developer_signatures(apksigner, apk):
    result = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                            capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise ValueError("Developer APK signature verification failed")
    certs = tuple(sorted(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", result.stdout)))
    schemes = tuple(re.findall(r"Verified using (v[23]) scheme[^\n]*: true", result.stdout))
    if not certs or not schemes:
        raise ValueError("Developer v2/v3 signature evidence missing")
    return certs, schemes


def verify_grant(classes, trust, contract, audience, channel, mode, path):
    result = subprocess.run(["java", "-cp", str(classes), "com.lelloman.paravoidandroid.delivery.tools.GrantCheck",
                             str(trust), contract, audience, channel, mode, str(path)], capture_output=True, timeout=60)
    if result.returncode:
        raise ValueError("Shared grant signature/scope verification failed")


def personalize(source, output, grant, apksigner, verifier):
    """verifier(mode, path) is bound to A's shared verifier by the CLI; test seams are private."""
    source, output, grant = Path(source), Path(output), Path(grant)
    checksum = Path(str(output) + ".sha256")
    if output.exists() or checksum.exists() or Path(str(output) + ".idsig").exists():
        raise ValueError("Output or sidecar already exists")
    if Path(str(source) + ".idsig").exists():
        raise ValueError("v4 idsig regeneration requires a separate signing pipeline; refusing reuse")
    if source.stat().st_size > MAX_APK or not 0 < grant.stat().st_size <= MAX_GRANT:
        raise ValueError("Input exceeds tool bounds")
    original = source.read_bytes()
    inspect(original)
    before = developer_signatures(apksigner, source)
    verifier("envelope", grant)
    encoded = grant.read_bytes()
    result = insert(original, encoded)
    # Keep candidate private; publish only after both developer signatures and inserted grant verify.
    with tempfile.TemporaryDirectory(prefix="paravoid-personalize-", dir=output.parent) as temporary:
        candidate = Path(temporary) / "candidate.apk"
        candidate.write_bytes(result)
        candidate.chmod(0o600)
        if developer_signatures(apksigner, candidate) != before:
            raise ValueError("Developer signatures changed")
        if inspect(result)[3].get(GRANT_ID) != encoded:
            raise ValueError("Inserted grant differs")
        verifier("apk", candidate)
        with candidate.open("rb") as stream:
            os.fsync(stream.fileno())
        # Hard-link publication is atomic and refuses to overwrite an existing output.
        os.link(candidate, output)
    with checksum.open("x") as stream:
        stream.write(hashlib.sha256(result).hexdigest() + "\n")
        stream.flush()
        os.fsync(stream.fileno())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--grant", type=Path, required=True)
    parser.add_argument("--trust", type=Path, required=True)
    parser.add_argument("--contract", required=True)
    parser.add_argument("--audience", required=True)
    parser.add_argument("--channel", default="stable")
    parser.add_argument("--apksigner", type=Path, required=True)
    args = parser.parse_args()
    classes = os.environ.get("PARAVOID_GRANT_TOOL_CLASSES")
    if not classes:
        parser.error("Use delivery/tools/personalize.sh to compile the shared verifier")
    try:
        personalize(args.input, args.output, args.grant, args.apksigner,
                    lambda mode, path: verify_grant(classes, args.trust, args.contract, args.audience, args.channel, mode, path))
    except (ValueError, OSError, subprocess.SubprocessError):
        raise SystemExit("Personalization failed; input APK unchanged. Check policy, grant, signing layout and output paths.")
    print("Personalized APK and fresh SHA-256 written; developer signatures and inserted grant verified.")
