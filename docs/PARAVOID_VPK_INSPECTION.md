# VPK preflight verification

The Store validates Paravoid v1 containers, signed inventories, component formats
and resource reservations without extracting executable files. Successful
preflight does not prove Android startup or authorize publication.

```sh
cargo run --manifest-path backend/Cargo.toml --bin vpk_verify -- \
  payload.vpk apk-pinned-trust.json <shell-contract-sha256> reservations.json
```

`reservations.json` maps installed resource names to IDs, for example
`{"string/title":"0x7f010001"}`. Alternatively, supply the complete policy exported
from the intended shell build:

```sh
cargo run --manifest-path backend/Cargo.toml --bin vpk_verify -- \
  payload.vpk --shell-policy shell-policy.json
```

The `complete-apk-v1` decoder validates the canonical contract hash, installed
boundary, trust roles, distribution settings and resource reservations. It is
compared with upstream's committed `InstalledPolicyCodec` (`b9e56c9`) and used during signed
shell registration. Final upstream packaging/runtime acceptance remains outstanding. Store production policy rejects debug
HTTP. The preflight command cannot establish that a supplied policy belongs to a
signed APK or a registered Store app. It never contacts a server or changes a
database. Its report always includes `apk_policy_verified: false` and
`publication_allowed: false`.

The older `vpk_inspect` command accepts the archive, trust and contract arguments
and checks only the container and signed inventory. Its report continues to state
`complete_verification: false`.

## Checks

- Exact outer ZIP coverage and local/central header agreement; stored entries,
  safe unique paths, no prefixes/trailers, overlaps, comments, extras, ZIP64,
  encryption or data descriptors.
- Streaming CRC32/SHA-256, RSA-3072 release signature, exact app/contract scope,
  pinned version floor, exhaustive sorted inventory and native ABI agreement.
- Bounded nested APK/JAR scanning, UTF-8 names and contiguous local entries.
  Stored/deflate components may use ordinary ZIP extras and data descriptors;
  hidden bytes, ZIP64, links, duplicates, unsafe paths and Java-resource code
  entries are rejected.
- DEX 035/037/038/039 headers, file/header size, endianness, SHA-1 signature and
  Adler-32 checksum; native ELF class, endianness, shared-object type and ABI.
- Android resource-table header/length and resource-ledger schema, app identity,
  unique IDs/names, consistent type IDs, and preservation of every supplied
  installed reservation, including removed resources.
- Streaming second-pass archive hash verifies the source did not change during
  component checking.

Limits include 1 GiB archive, 1 MiB release envelope, 16 MiB/100,000-entry ledger,
4,096 outer entries, 100,000 combined nested entries, 2 GiB combined uncompressed
component content, 128 MiB per DEX and 256 MiB per native library.

## Upload and publication

Durable VPK uploads use the same component-format checks. Verified APK registration
supplies installed reservations and allows `verified` drafts. Pending contracts
continue to produce `inspected` drafts with an explicit incomplete report and cannot
publish. The management UI shows this limitation. Immutable
identities, monotonic versions, revisions and authorization remain separate
publication requirements.

## Interoperability tests

Paravoid's complete verifier was committed at `6e2df67`; host delivery/lifecycle
integration followed at `8ea84ad`. The Store's comparison script uses a snapshot of
committed upstream Java sources and independently checks valid/malformed archives:

```sh
scripts/check-paravoid-vpk-interop.sh ../paravoid-android
ANDROID_HOME=/path/to/android-sdk scripts/check-paravoid-vpk-interop.sh ../paravoid-android
```

With Android SDK platform/build-tools 36, the second command builds real D8 DEX
and AAPT2 resource content, signs a VPK with a throwaway key, and checks it in both
Rust and Java. As in upstream packaging, unsigned resource ZIP metadata is rebuilt
to remove AAPT2 alignment padding while preserving compiled entry bytes. This is
host format interoperability, not installation or execution acceptance.

An explicit `--working-tree` second argument compares one copied snapshot of
upstream development files. When `InstalledPolicyCodec` is present, it also
compares policy decoding and rejection cases. The script labels that run as an
uncommitted comparison; it never edits the sibling checkout.

```sh
cargo test --manifest-path backend/Cargo.toml --test paravoid_archive \
  --test paravoid_metadata --test paravoid_shell_policy
```

OpenSSL and Java are required for cross-language checks. The real Android
component test is intentionally ignored outside the SDK-aware script. Physical
Android acceptance and final APK policy packaging/runtime integration remain
required before declaring production readiness.
