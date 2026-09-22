# Paravoid distribution implementation

Approved design: 2026-09-22. This document tracks implementation, not production
readiness. The runtime wire contract belongs to `../paravoid-android/V1.md`.

## Product decisions

- One package name, one app, one active mode: normal or Paravoid. There is no
  user-facing distribution selector and no requirement to pair normal APKs with VPKs.
- All new artifacts use upload, validation, review and explicit publication.
- Mode changes require a stable APK newer than all previously published APKs,
  signing continuity and app-specific data migration evidence. Beta-only mode
  changes are not supported.
- APK versions and payload versions are separate increasing sequences. Payload
  identity/version allocation is global across contracts and channels.
- Public and keyed delivery, embedded and empty shells are in scope.
- Old shell streams are explicitly retired, not implicitly abandoned by a new
  installer. Older Android devices may acquire a compatible historical normal APK.
- Issued copies are associated with the acquiring user's current app/group access.
  USB receiver installs use a distinct grant owned by the controller user.
- Credential repair is an explicit same-version or newer shell reinstall, never
  uninstall/data deletion. Revocation blocks future access, not offline execution.
- Shell update controls are available through an app shortcut and a Store action.

## UX contract

Users get one Install/Update/Open action. LelloStore manages installers; Paravoid
manages payloads and knows their running/pending state. Store metadata and request
counts must not be presented as payload activation telemetry.

Admin app pages provide Overview, Releases, Installers, Update access and
Distribution management. VPK details include signed identity, version, size,
validation, compatibility, publication history and original download. Upload
results persist; reviewing a stale app revision cannot publish silently.

The final shell UI covers check/download/verification/pending states, preferences,
storage, empty bootstrap, authentication failure and forward recovery. No generic
rollback, credential display, verification bypass or automatic data deletion.

## Implementation status

### Implemented foundation

- Additive publication migrations; existing releases remain published.
- New HTTP uploads require `publication=draft` and `distribution_mode=normal`.
  Shell uploads are rejected while complete verification/personalization is absent.
- Catalog/download endpoints exclude drafts and withdrawn releases.
- Explicit publish/withdraw with optimistic revisions and persistent audit history.
- Global published APK identity retention, including after catalog deletion.
- Normal draft metadata editing and admin review/publication UI.
- Durable validation queue, restart recovery, failed-upload inspection/retry and
  admin Uploads UI. Browser/publisher poll saved jobs; synchronous draft API remains.
- Acquisition records: owner, purpose, idempotency, delivered size/hash, 24-hour
  expiry and live access rechecks. Normal/public acquisitions use canonical APKs; verified keyed shells use personalized copies.
- Acquisition download validators and conditional/ranged transfer behavior.
- Browser and Android verification use delivered acquisition metadata. Local and
  USB downloads share the verified APK provider.
- Publisher draft upload, inspect, publish, withdraw and upload-and-publish flow.
- Rust strict metadata parsing, role-separated RSA-3072 verification and canonical
  writing; all seven upstream Java metadata vectors agree at `d58457f`.
- Bounded VPK container/signed-inventory inspection, nested APK/JAR scanning,
  DEX/ELF/resource-table checks and resource-ledger reservation verification,
  with an [offline preflight command](PARAVOID_VPK_INSPECTION.md). Rust and committed
  upstream Java agree on real D8/AAPT2 content and malformed vectors. This is host
  format interoperability, not Android execution acceptance or publication approval.
- Strict `complete-apk-v1` shell-policy decoding with canonical contract binding,
  trust-role and installed-boundary validation. Comparisons against upstream's
  developing policy codec are separate from production APK registration.
- Operator-configured online head/grant authorities, pinned-policy-checked signing,
  and admin Distribution UI/public key export. See [signing setup](PARAVOID_SIGNING.md).

### Implemented delivery and management (integration gated)

- Durable VPK upload jobs targeting a registered contract; immutable files,
  signed inventory/component-format verification and explicit reports of pending
  APK-pinned resource reservations and shell compatibility.
- VPK review/notes/publish/withdraw/original-download UI and APIs. Publication
  requires complete verification; inspection alone never passes this gate.
- Signed public/keyed heads, exact per-revision/scope snapshots, expiry refresh,
  SDK/ABI/floor selection and explicit stream retirement/reactivation.
- Retained VPK range/conditional delivery, with live owner/group authorization
  and grant revocation rechecked before each response, including 304 and 206.
- Signature-preserving keyed APK acquisition, durable retry identity, exact copy
  size/hash, and same-version repair. Grant credentials stay out of API lists,
  database rows and logs; private working files carry the signed envelope.
- Admin issued-grant list/revocation and stream/publication audit history.
- Distribution migration review UI/API: retained-source and target APK signature
  verification, unchanged single-signer continuity, stable/newer version checks,
  explicit data-preservation test evidence, revision/hash-bound approval and audit.
  Returning to normal distribution uses that review; enabling Paravoid still waits
  for verified shell registration and bootstrap publication integration.
- Hourly bounded cleanup of expired personalized transfer copies and old successful
  upload inputs, retaining grant/job identities and immutable published artifacts.
- Read-only restore verification of logical database state and retained artifacts,
  with an isolated recovery test rejecting lost revocations, lower replay revisions
  and corrupted APK bytes. See [backup and restore procedure](PARAVOID_RECOVERY.md).
- Device SDK filtering keeps compatible historical installers available when
  the current distribution requires a newer Android release.
- Android and browser explicit repair actions; Android retains distribution
  metadata through its Room cache and repairs the installed published shell.
- Publisher commands: distribution, upload-vpk, upload-status, publish-vpk and
  withdraw-vpk. VPK upload queues a draft and returns its durable job identity.
- Runtime container includes Python, apksigner and the independent Rust grant
  verifier. See [delivery operations](PARAVOID_DELIVERY.md).

### Remaining Store work

1. Signed APK policy extraction/registration and production packaging interoperability
   against upstream's final APK-pinned policy carrier. The stateless decoder and
   component checks are implemented; registration must supply the actual installed
   resource reservations before a VPK can become verified.
   There is intentionally no admin API to mark an unverified contract as verified.
2. Connect the verified transition review to initial Paravoid publication, including
   embedded/empty bootstrap prerequisites and initial VPK publication. Shell
   upload/publication remains gated; existing streams are retired explicitly.
3. Shell-controls deep link once
   upstream defines its installed management Activity contract.
4. Device acceptance and deployment-specific restore drills. The isolated backup
   recovery test and operator procedure are implemented. Failed inputs are
   intentionally retained for inspection/retry; unreferenced generated transfer
   files are reclaimed after seven days. HTTP/storage metrics cover delivery traffic,
   VPKs, personalized copies and queued inputs. Keep one backend
   instance: durable jobs currently use a single worker and personalization mutex.

### Upstream integration boundary

Paravoid is concurrently developing packaging/verification, delivery/controls and
runtime lifecycle under `PARALLEL-IMPLEMENTATION.md`. Its shared foundation first
appeared at `7ace172`; signed metadata and conformance vectors followed at
`d58457f`. The Store consumes those metadata vectors independently in Rust. Do not replace those interfaces or use experimental fixture
formats as production VPKs. Production publication remains disabled until the
verifier, packaging metadata, personalization tool and executable vectors agree.

Paravoid additionally needs the planned APK-pinned distributor acquisition URL
and management Activity/shortcut contract, agreed with its owning track.

## Acceptance gates

The full feature requires Python/Android/Rust protocol agreement, signed release
tests on API 30 and API 36.1, and physical ARM64 acceptance. Test embedded and
empty startup, offline use, exact-contract updates, malformed archives, replay,
revoked/replaced grants, interrupted transfers, process leases, corrupted state,
storage pressure and forward repair.

Pezzottify must preserve package/signing identity, database, authentication and
files through normal → shell → payload update → normal. Exercise playback,
background work, auth callbacks and process death. A larger APK version code
does not prove data migration compatibility. The final implementation must not
be called complete before these gates pass.
