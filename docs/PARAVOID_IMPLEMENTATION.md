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
- New HTTP uploads require `publication=draft` and an explicit `distribution_mode`
  (`normal` or `paravoid`). Browser and publisher expose that author choice; durable
  jobs preserve it. Shell uploads require a developer-signed APK, not an AAB.
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
  policy codec at upstream `b9e56c9` are tracked explicitly. Bounded APK policy extraction and
  apksigner verification bind the policy to the actual APK package, SDK, signer and
  channel. Canonical uploads cannot contain an issued grant.
- Operator-configured online head/grant authorities, pinned-policy-checked signing,
  and admin Distribution UI/public key export. See [signing setup](PARAVOID_SIGNING.md).

### Implemented delivery and management

- Durable VPK upload jobs targeting a registered contract; immutable files,
  signed inventory/component-format verification and resource-reservation checks
  against registered APK policy. Pending contracts yield inspected drafts only;
  verified contracts yield verified VPK drafts.
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
- Multiple APK versions may share one exact contract. Installer mappings preserve
  acquisitions and signing identity without duplicating streams. The pinned channel
  cannot be changed through either release editor. Online endpoint/key availability
  and APK signer continuity are rechecked before shell publication.
- Distribution migration review UI/API: retained-source and target APK signature
  verification, unchanged single-signer continuity, stable/newer version checks,
  explicit data-preservation test evidence, revision/hash-bound approval and audit.
  Empty-shell activation publishes its selected verified bootstrap VPK in the same
  transaction. SDK/ABI coverage, monotonic identities and stream status are checked.
  Initial stable Paravoid publication needs no migration from a nonexistent app.
- Hourly bounded cleanup of expired personalized transfer copies and old successful
  upload inputs, retaining grant/job identities and immutable published artifacts.
- Read-only restore verification of logical database state and retained artifacts,
  with an isolated recovery test rejecting lost revocations, lower replay revisions
  and corrupted APK bytes. See [backup and restore procedure](PARAVOID_RECOVERY.md).
- Device SDK filtering keeps compatible historical installers available when
  the current distribution requires a newer Android release.
- Android conditional Manage app updates action checks the installed, exported
  shell Activity; Android and browser expose explicit repair actions. Android retains distribution
  metadata through its Room cache and repairs the installed published shell.
- Publisher commands: distribution, upload-vpk, upload-status, publish-vpk and
  withdraw-vpk. VPK upload queues a draft and returns its durable job identity.
- Authenticated HTTP acceptance covers real signed shell/VPK uploads, atomic
  bootstrap publication, public/keyed acquisition, exact delivery bytes, revocation,
  and reviewed mode switching in both directions. SDK-dependent checks run in CI.
- Nested APK/JAR validation agrees with upstream `9118a93` on Android zipalign's
  short zero-padding tails; real AAPT2 output is tested without repacking.
- Runtime container includes Python, apksigner and the independent Rust grant
  verifier. See [delivery operations](PARAVOID_DELIVERY.md).

### Integration checkpoint — 2026-09-23

Upstream `42d40c8` supplies complete embedded/empty packaging, installed runtime
loading and recovery controls. Store uploads consume the signed policy at
`assets/paravoid/shell-policy.json` and, for embedded shells, the complete VPK at
`assets/paravoid/payload.vpk`. Extraction is bounded and the embedded archive goes
through the same signature, inventory, component and reservation checks as uploads.
The installer and its fixed bootstrap draft are registered atomically. Exact bytes
may be reused by multiple installers; conflicting payload identities are rejected.
Publication commits the embedded payload and installer together and rechecks stored
integrity. Empty installers still require an explicit bootstrap selection.

The Store controls action targets the exported shell-owned
`com.lelloman.paravoidandroid.runtime.UpdatesLauncher` alias. The underlying updates
Activity stays private. If the author disables the alias, the Store action stays
hidden. Availability is rechecked on resume and before opening.

Signed HTTP acceptance covers public/keyed × embedded/empty, malformed/missing
embedded content, exact acquisition bytes, revocation and both distribution-mode
transitions. The current unchanged upstream non-debuggable release APK and its VPK
also pass Store admission. Rust/Java conformance passes against the current checkout.

### Remaining acceptance

The packaging and controls integration blockers are resolved. Store-backed keyed,
empty-shell HTTPS bootstrap, a second payload with unchanged APK, revocation,
same-version repair and offline launch now pass on API 30 and API 36.1. See
[device acceptance](PARAVOID_DEVICE_ACCEPTANCE.md) for exact scope and reproduction.
The opt-in Android UI sequence also passes on API 30 and API 36.1. It uses the
production HTTP client, download verifier,
installer and Store screens. It found and fixed three client issues: omitted
`install` acquisition purpose, reuse of an old installer completion task for the
same APK URI, and reopening a payload screen instead of the exported update
controls. Same-version repair must replace the installed APK and retain its active
payload; old completion tasks are deliberately retained during this test.

Remaining acceptance requires physical ARM64 and real-app normal → shell → payload
→ normal data preservation, plus deployment configuration and restore drills.
The available upstream Pezzottify fixture uses a separate `.paravoid` package and
debug HTTP. It cannot establish an in-place migration from the published normal
app. That gate needs author-supplied candidates with the same package/signing
identity, HTTPS policy, and an approved test backend/account for authenticated
playback and retained content. Upstream's device evidence is recorded in its
`RELEASE-READINESS.md`; it is not a substitute for exercising LelloStore as the
actual distributor. No production deployment or phone testing is claimed here.

Keep one backend instance: durable jobs currently use a single worker and
personalization mutex. Retained APKs/VPKs and issuer keys need the documented backup
procedure. Failed upload inputs remain available for inspection/retry.

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
