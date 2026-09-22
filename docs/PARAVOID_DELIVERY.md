# Paravoid Store delivery operations

These routes and administration surfaces are implemented, but production shell
registration/publication stays disabled until upstream APK policy packaging and
complete executable VPK verification are integrated. Test fixtures insert verified
contracts directly; that is not an operator onboarding procedure.

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
back the installed carrier. It preserves v2/v3 signing entries and requires apksigner
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

## Distribution migration reviews

A stable draft in a different distribution mode requires a separate review before
publication. The API verifies source/target stored hashes and apksigner evidence,
requires an unchanged single v2/v3 signer and a newer APK version, and records the
administrator's migration test evidence. The UI asks for confirmation that the tested
upgrade preserved database/settings, authentication and files. This is recorded
human test evidence, not automated proof of application data compatibility.

The review is tied to the exact draft hash and optimistic app revision. Publication
cannot use an old review after the app changes. The publication history UI retains
the review, signer fingerprint and evidence. Signing certificate rotation is not
supported by this conservative continuity check. Existing streams are unchanged;
retire them explicitly when that is the intended rollout.

Paravoid activation still requires upstream verified shell registration and initial
bootstrap VPK integration. A review does not bypass those gates.

## Persistence and recovery limits

Back up SQLite, artifact storage and online signing keys consistently. Never restore
older revision/grant history under the same live endpoint without a recovery plan:
installed clients retain replay floors and copied APKs retain credentials. Contract
and VPK identity records must survive retirement. Apps with retained contracts cannot
be deleted through the catalog API; withdraw installers and retire streams instead.

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

Cross-language readback also passed against committed Paravoid `baec6b1`:

```
ANDROID_HOME=/path/to/sdk scripts/check-paravoid-interop.sh ../paravoid-android
```

The script compiles committed upstream contract/delivery Java sources in a temporary
directory, then checks Store-generated grants (read from the personalized signed APK)
and signed heads with the upstream Java verifiers. It does not consume uncommitted
packaging work and does not claim full VPK/runtime interoperability.
