# Signed metadata vectors (not complete VPKs)

Frozen test bytes for internal cross-track conformance, not a public protocol freeze.
`cases.json` lists each input's role and expected stateless verification outcome.
Use exact input bytes; signatures cover decoded body bytes, not reserialization.

Installed/request settings:

- applicationId `example.app.paravoid`
- shellContractId: 64 lowercase `a` characters (test placeholder)
- base URL `https://updates.example.test/`, channel `stable`, apkKey mode
- SDK 30, ordered/process-filtered ABIs `["x86_64"]`, runtime ABI/format/protocol 1
- public keys/floors from `trust.json`

`head-a` and `head-b` describe compatible request scopes with increasing revisions
and payload versions. Their archive hashes/sizes are placeholders, **not downloadable
VPK fixtures**. They test metadata only. Complete signed A/B archives are a separate
Track A deliverable. `grant.json` carries an intentionally all-zero test credential,
not a production secret. No private signing keys are retained or checked in.

Heads are issued at Unix 1800000000 and expire at 1800003600. Stateless verification
acceptance does not mean current-time/replay acceptance: lifecycle tests inject
time and must check those separately. The grant has no time-based expiry, but
its future-issued bound still applies at HTTP/admission time.

Negative cases have valid signing bytes except the deliberate role mismatch:
duplicate body keys, wrong signed request scope, wrong grant audience. A verifier
must reject them even though a permissive JSON parser or signature-only check may
accept them. Errors must not echo the grant body or credential.

`./gradlew :paravoid-contract:generateMetadataVectors` generates a new candidate
set under `build/metadata-vectors` with fresh throwaway keys. It never overwrites
these accepted vectors. Regeneration/promotion is explicit and requires updating
all consumers together. Python/Android independent agreement and security review
remain release gates; the Java tests alone do not satisfy them.
