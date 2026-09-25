# Paravoid Store delivery operations

The Store supports signed shell registration, verified VPK uploads and atomic
publication for both empty and embedded bootstrap, integrated against Paravoid
`42d40c8`. Production acceptance still includes Store-backed installed-device and
real-app migration tests. Legacy embedded module archives are not complete VPKs.

## Runtime configuration

Configure `PARAVOID_SIGNING_CONFIG` as described in [signing setup](PARAVOID_SIGNING.md).
For keyed acquisitions additionally set:

- `PARAVOID_PERSONALIZER`: absolute path to `scripts/paravoid-personalize.py`.
- `PARAVOID_GRANT_VERIFIER`: compiled `paravoid_grant_check` binary.
- `APKSIGNER_PATH`: Android SDK or distribution apksigner executable.

Python 3 and Java must be available. The Dockerfile installs these dependencies
and supplies tool paths. Private online signing keys are mounted separately; the
image never includes them. Public delivery does not personalize an APK.

The personalization wrapper vendors Paravoid's signature-preserving implementation
and calls the Store's independent grant verifier before insertion and after reading
back the installed carrier. Registration fields must match the original APK-pinned
policy, and the personalized copy must preserve that policy. It preserves v2/v3 signing entries and requires apksigner
verification. Unsupported layouts (including v3.1, source stamps and existing v4
sidecars) fail. The current upstream implementation reads the APK into memory;
provision capacity for its 1 GiB maximum. Run one Store process per database/storage.

A request reserves a durable acquisition identity and private working directory.
Retries with the same user/idempotency key reuse that job, grant and output; another
purpose/package/version or an expired request cannot reuse the key. Working grant
and trust files have owner-only permissions. The database stores a credential hash,
never the bearer credential. Exact delivered copy metadata is returned to clients.

## Routes and authorization

Runtime base URL: `/api/paravoid/`. Shells call the protocol-defined `v1/apps/...`
head and payload paths without OIDC. Public contracts accept anonymous requests.
Keyed contracts require the canonical bearer key from a verified personalized APK.
Every request rechecks revocation, grant scope and the acquiring user's live direct
or group access. Conditional and ranged requests have the same checks. A USB copy
belongs to the authenticated acquiring controller user.

Heads are cached as exact signed bytes by contract/revision/request scope. Expiry
refresh increments the revision; publication, withdrawal and stream changes invalidate
previous head selection. Retired streams return `shell-update-required`. Historic
published payload identities remain downloadable for outstanding signed references.
Grant revocation blocks future delivery, not already accepted offline execution.

## Administration and publisher

The app's Paravoid tab contains payload review, shell streams, issued access and
history. Merely inspected VPKs cannot be published. Notes and publication use the
reviewed app revision, so concurrent changes require renewed review. Original VPK
downloads verify the immutable size/hash in the browser. Request counts show requests,
not payload activation.

With the same publisher connection/authentication configuration as APK commands:

```
python3 scripts/publish-to-lellostore.py distribution PACKAGE
python3 scripts/publish-to-lellostore.py upload-vpk PACKAGE CONTRACT payload.vpk --yes
python3 scripts/publish-to-lellostore.py upload-status UPLOAD_ID
python3 scripts/publish-to-lellostore.py publish-vpk PACKAGE VPK_ID --expected-revision REV --yes
python3 scripts/publish-to-lellostore.py withdraw-vpk PACKAGE VPK_ID --expected-revision REV --yes
```

A queued upload is not a published release. Keep its returned job ID if the client
stops waiting. Validation reports and original inputs remain available for diagnosis.

## Distribution changes

Publish a stable draft to change the app's distribution mode. Publication checks
source and target stored hashes, APK signatures, an unchanged single v2/v3 signer,
and a version code newer than every published installer. These checks run on the
server as part of publishing; administrators do not complete a migration checklist
or submit test evidence. App runtime and data-preservation testing belongs in the
app author's release process.

Automatic signing checks are recorded with the publication in one database
transaction and tied to the reviewed app revision. Existing historical migration
reviews remain available. The legacy review API is retained for older clients,
but current browser and CLI publication do not require it. Existing streams are
unchanged; retire them explicitly when that is the intended rollout.

Upload the signed installer with `distribution_mode=paravoid`. For an empty shell,
upload its VPK against the registered contract and choose it in installer review.
For an embedded shell, the upload registers its included VPK and review fixes the
selection to those signed bytes. Publication atomically publishes both. The initial payload must cover the
installer's SDK/ABI range, the stream must be active, and configured head/grant keys
and endpoint must match the APK. Keyed shells also require personalization tools.
Further installer versions may reuse a published bootstrap for the same contract.

The publisher exposes `upload shell.apk --distribution-mode paravoid`, followed by
`upload-vpk`, and `publish PACKAGE APK_VERSION --expected-revision REV
--bootstrap-vpk VPK_ID`. The legacy `--transition-review` option remains accepted but is not required.
Shell `upload --publish` is rejected because bootstrap selection needs a separate
review after registration. Embedded publication uses the registered included payload.

## Persistence and recovery limits

Back up SQLite, artifact storage and online signing keys consistently. Never restore
older revision/grant history under the same live endpoint without a recovery plan:
installed clients retain replay floors and copied APKs retain credentials. Contract
and VPK identity records must survive retirement. Apps with retained contracts cannot
be deleted through the catalog API; withdraw installers and retire streams instead.
Use the [backup/restore procedure and checkpoint verifier](PARAVOID_RECOVERY.md)
to check an isolated recovery before restoring traffic.

Hourly cleanup removes personalized transfer directories one hour after their
24-hour acquisition expiry, and successful upload inputs after seven days. It retains
job/acquisition identities, grant authorization and all published artifacts. Failed
upload inputs remain available for retry. Generated upload/acquisition files with no
durable record are removed after a seven-day grace period. Multi-instance leases
are not implemented. Monitor disk usage. Existing Prometheus HTTP metrics normalize package/release/grant
identifiers; storage gauges now include VPKs, personalized acquisitions and upload
inputs, so these files contribute to total storage usage. VPK copies are synced before an atomic link
creates their immutable content address, so interrupted copying cannot expose a partial
canonical artifact. Personalized jobs
recover completed output by checking grant identity, developer signing entries and
apksigner verification again.

## Verification performed

Backend tests cover signed head identity/expiry/retirement, monotonic VPK publication,
revoked or removed access before 304/206, and incomplete-verification rejection.
An opt-in integration test creates a genuine v2/v3 signed APK, personalizes it,
checks unchanged developer signing entries/source bytes, exact delivered hashes,
idempotent retries and a distinct same-version repair grant:

```
ANDROID_HOME=/path/to/sdk cargo test --manifest-path backend/Cargo.toml \
  --test paravoid_distribution -- --include-ignored
```

This requires build-tools 36.0.0, platform 36, keytool and OpenSSL. It passed locally.
It does not replace installation/device, runtime activation, normal/shell migration,
API 30/36.1 or physical ARM64 acceptance.

Cross-language readback also passed against committed Paravoid `e879e9e`:

```
ANDROID_HOME=/path/to/sdk scripts/check-paravoid-interop.sh ../paravoid-android
```

The script compiles committed upstream contract/delivery Java sources in a temporary
directory, then checks Store-generated grants (read from the personalized signed APK)
and signed heads with the upstream Java verifiers. It does not consume uncommitted
packaging work and does not claim full VPK/runtime interoperability.

Signed installer registration also has an SDK-aware test. It builds genuine v2/v3
APKs, verifies policy/package/SDK/signer bindings, exercises durable shell upload,
and registers two installer versions against one contract:

```sh
ANDROID_HOME=/path/to/sdk APKSIGNER_PATH=/path/to/sdk/build-tools/36.0.0/apksigner \
  cargo test --manifest-path backend/Cargo.toml --test shell_registration -- --include-ignored
```

The regular suite also checks atomic bootstrap publication, SDK/ABI rejection,
identity rollback and resource-reservation enforcement. The fixture is not an
Android runtime acceptance app.

The Android app details page offers **Manage app updates** only when the installed
package exposes the enabled, exported
`com.lelloman.paravoidandroid.runtime.UpdatesLauncher` and any required
permission is held. It uses an explicit component in that package, refreshes the
capability on resume, and handles replacement/removal gracefully. The alias opens Paravoid's private updates Activity in its recovery process.
If the author disables the alias, the Store action stays hidden.

The signed HTTP gate runs against the production authenticated router and an isolated
mock OIDC issuer. It uploads a real signed shell and real D8/AAPT2 VPK through HTTP,
processes their durable jobs, publishes the bootstrap atomically, acquires and
verifies the delivered installer, and fetches a signed head and exact payload bytes.
Both public and keyed modes run; keyed grant revocation denies cached heads and
ranged payloads. Signed installer transitions in both directions require migration
review and preserve the retained payload stream. Migration evidence in this test is
explicitly fixture data, not evidence of Android app-data preservation.

```sh
bash scripts/check-paravoid-delivery.sh /path/to/sdk
```

This runs the SDK-dependent registration, personalization and authenticated HTTP
tests with fresh throwaway keys. It requires a local listening socket for mock OIDC,
Java, OpenSSL, Python 3, and the Android SDK above. The backend CI job runs this gate
in addition to its regular tests. No deployment or device installation occurs.

Both authenticated and test-only canonical APK routes reject keyed/unverified
shells with `acquisition_required`; they cannot supply a grantless installer to
legacy clients, including via ranged downloads.

Embedded installers must contain `assets/paravoid/payload.vpk`, matching the signed
bootstrap policy. Uploading the APK extracts and verifies this archive, registers
its immutable VPK draft and binds it to that installer in one database transaction.
A repeated exact embedded payload may be shared across installers; altered bytes
cannot reuse an existing release ID or version. Admin review displays the fixed
payload and publishes both together. CLI `publish` needs no `--bootstrap-vpk` for
embedded installers; supplying a different ID is rejected. Empty installers must
not contain a payload carrier and continue to require `--bootstrap-vpk`.

An opt-in admission test accepts unchanged upstream production artifacts:

```sh
ANDROID_HOME=/path/to/sdk APKSIGNER_PATH=/path/to/sdk/build-tools/36.0.0/apksigner \
PARAVOID_UPSTREAM_APK=/absolute/path/to/shell.apk \
PARAVOID_UPSTREAM_VPK=/absolute/path/to/payload.vpk \
  cargo test --manifest-path backend/Cargo.toml --test upstream_paravoid -- --ignored
```

The external VPK is needed only for empty shells. This check creates an isolated
Store database, verifies the APK signature and policy, and validates the real
producer VPK without repacking or changing the artifacts. Production Store policy
requires HTTPS; debug HTTP artifacts are intentionally rejected.

Store-backed installed HTTPS acceptance now passes on disposable API 30 and API
36.1 emulators: empty/keyed bootstrap, a distinct payload update without replacing
the APK, revocation, same-version repair and offline cold launch. The test uses
the production Store router and Paravoid runtime. See
[device acceptance](PARAVOID_DEVICE_ACCEPTANCE.md) for reproduction, artifact hashes
and the remaining Store-client, physical-device and real-app migration limits.


## Paravoid push adapter

`/api/paravoid/v1/events` implements the store-neutral `paravoid.updates.v1`
WebSocket subprotocol. Shells opt in through their APK update policy; Paravoid has
no dependency on LelloStore. A foreground connection uses the same installed delivery
bearer as head/download requests (public contracts need no bearer), independently of
browser OIDC. Send a `subscribe` object with version 1, applicationId,
shellContractId and channel. The server replies with `updates_changed` hints containing
the same scope and a unique eventId. It sends an initial hint on every connection,
then hints when catalog publication changes. Clients must fetch signed discovery;
a hint never authorizes an install. Access is rechecked before hints and periodically.

For background notifications, an integration can subscribe to this stream and forward
the identical event JSON through its notification provider. The downstream shell adapter
passes those bytes to `ParavoidPush.receive(context, bytes)`. Registration, addressing
and provider credentials belong to that integration; neither project mandates a
notification vendor. Shell build policy chooses `prompt` or `automatic`, with activation
on the next cold start. Policy envelope version 2 carries the updater/push configuration;
version 1 remains accepted for existing shells.

Shell policy `updates.restartBehavior` independently selects `manual` (default),
`prompt`, or `automatic` restart after staging. Automatic restart waits for a visible
app Activity; background completion waits for the next foreground entry. Downstream
apps may instead own timing/confirmation using the shell restart command.
