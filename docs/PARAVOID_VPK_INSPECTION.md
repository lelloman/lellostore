# VPK preflight inspection

The Store now has an extraction-free inspector for Paravoid's selected v1
container and signed release inventory. This is a development diagnostic;
**successful inspection does not authorize publication or establish runtime
compatibility.** The VPK upload/publication APIs remain disabled.

```sh
cargo run --manifest-path backend/Cargo.toml --bin vpk_inspect -- \
  payload.vpk apk-pinned-trust.json <shell-contract-sha256>
```

Supply the trust policy and exact contract hash from the intended shell build.
The tool authenticates against those supplied keys; it does not establish that
they belong to an installed APK or an existing Store app. It never contacts a
server, changes a database or extracts executable content.

The JSON report includes the archive size/hash, exact signed-envelope hash,
signing key ID, signed release identity and inventory, and nested component
entries with sizes/hashes. It always contains `complete_verification: false` and
`publication_allowed: false`. Failures return a nonzero exit code.

## Checks implemented

- Exact outer ZIP coverage and agreement between local/central headers; stored
  entries only; no prefixes/trailers, overlaps, comments, extra fields, ZIP64,
  encryption, data descriptors, links, duplicates or unrecognized paths.
- Required components, contiguous multidex numbering and valid native ABI/path
  names, including `libc++_shared.so`.
- Streaming CRC32 and SHA-256 checks, RSA-3072 release-envelope authentication,
  exact app/contract scope, strict signed schema, sorted exhaustive inventory,
  resource-ledger byte hash and agreement between declared/native ABI sets.
- Bounded nested APK/JAR scanning: stored/deflate only, no encrypted entries,
  ZIP64, links, unsafe/duplicate paths or overlapping local content. Local names
  and headers must agree with central entries. Nested payloads are not recursively
  extracted or interpreted.
- Decompressed content is read through fixed-size buffers with CRC/hash checks;
  actual bytes and declared sizes are bounded. Class/DEX files are forbidden in
  the Java-resource JAR.
- V1 limits: 1 GiB archive, 1 MiB release envelope, 4,096 outer entries, 100,000
  combined nested entries, 2 GiB combined uncompressed component content,
  128 MiB per DEX, 256 MiB per native library, and 255-byte archive paths.

## Remaining publication gates

The resource ledger's schema/extension rules, Android resource table and pinned
content, DEX/native compatibility and registered shell contract must still be
verified. Publication must additionally check signer continuity, immutable
published identities, version history, channel/mode policy and live authorization.
The current report deliberately makes none of those claims.

The sibling Paravoid checkout at `d58457f` provides signed metadata vectors but
not complete signed VPK conformance fixtures or a production archive verifier.
Synthetic Store fixtures contain deliberately non-executable component bytes.
They test rejection boundaries, not Android acceptance. Promote this inspector
only after comparison with upstream complete archives and negative vectors.

Run the tests with OpenSSL available for fresh throwaway release-signing keys:

```sh
cargo test --manifest-path backend/Cargo.toml --test paravoid_archive --test paravoid_metadata
```
