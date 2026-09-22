# Paravoid online signing configuration

The Store can now load online head/grant authorities, export their public keys
and produce metadata matching Paravoid's selected v1 profile. **This does not yet
enable shell/VPK publication or personalized APK delivery.** Complete archive
verification, grants, streams and runtime acceptance remain integration work.

The admin **Distribution** page shows the configured endpoint, public-key
fingerprints and active key IDs. Its export contains only `headKeys` and
`grantKeys`, ready to merge into the app author's APK-pinned trust policy. It is
not a complete trust policy: the author supplies `version`, `applicationId`,
separate release public keys and minimum version/revision floors.

## Provisioning

Generate separate RSA-3072 keys with exponent 65537 in an operator-controlled
secret directory. The Store consumes unencrypted PKCS#8 DER files, readable only
by its service account. Example commands for a **new, empty** secret directory:

```sh
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -pkeyopt rsa_keygen_pubexp:65537 -out head.pem
openssl pkcs8 -topk8 -nocrypt -in head.pem -outform DER -out head.pk8
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -pkeyopt rsa_keygen_pubexp:65537 -out grant.pem
openssl pkcs8 -topk8 -nocrypt -in grant.pem -outform DER -out grant.pk8
```

These commands create secrets; do not put their outputs in source control or
artifact storage. PEM intermediates have the same sensitivity as DER files.
Use an existing secret manager/backup policy for all private material.

Create an operator-owned JSON configuration alongside the DER files:

```json
{
  "version": 1,
  "baseUrl": "https://store.example.com/api/paravoid/",
  "headKeys": { "head-2026": "head.pk8" },
  "grantKeys": { "grant-2026": "grant.pk8" },
  "activeHeadKey": "head-2026",
  "activeGrantKey": "grant-2026"
}
```

Set `PARAVOID_SIGNING_CONFIG` to this file's path and restart the Store. Relative
key paths resolve against the configuration directory. The URL must be canonical
HTTPS, end in `/`, and have no credentials, query or fragment. It describes the
future delivery endpoint; the delivery routes are not enabled by this setting.

When the environment variable is absent, signing remains unconfigured. An
explicit invalid configuration fails startup. The server never generates or
replaces keys as a fallback. Keys with group/other permission bits are rejected
on Unix. Role reuse, unsupported RSA profiles and missing active IDs also fail.

`GET /api/admin/paravoid/configuration` requires an administrator and exposes only
public material. There is no private-key upload endpoint or generic signing API.
The server loads keys at startup and does not hot-reload them.

## Rotation and recovery

Public-key maps may contain several keys per role; exactly one key per role is
active for new metadata. New shells can pin the new key before it becomes active. Signing prefers the active
key when the target shell trusts it, otherwise it uses a retained configured key
whose ID and public bytes exactly match that shell policy. Retain old keys while
servicing older shells; removing every trusted signer makes their requests fail.
Removing an online key does not remove that root from already installed shells.
Removing compromised trust requires a new shell APK. Release-signing private keys
stay in the author's release infrastructure and never enter this configuration.

Back up private keys and their IDs with the eventual stream/grant/revision state.
Never restore an older revision database and silently issue lower revisions, or
regenerate keys while keeping the same key ID. Disaster recovery and production
rollout acceptance remain disabled-feature gates documented in
[the implementation tracker](PARAVOID_IMPLEMENTATION.md).

## Verification

`cargo test --manifest-path backend/Cargo.toml --test paravoid_metadata` checks the
exact upstream Java metadata vectors at `d58457f`, strict JSON, canonical writers,
role/scope separation and a fresh-key signing round trip. OpenSSL must be on PATH
for the Unix signing test; it independently verifies the Rust-produced signatures.
Test keys live only in temporary directories. These metadata fixtures are not
complete VPK fixtures and do not establish archive or runtime readiness.
