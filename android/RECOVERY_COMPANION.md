# LelloStore recovery provisioning

The companion is a separate package (`com.lelloman.store.recovery`) so its identity,
APK backup and recovery state survive removal of Store. Users install it through
**Settings → LelloStore self-updates and recovery**, not through the catalogue.

## Setup

1. Choose **Install / update companion**. Store extracts its bundled release APK,
   verifies package, version and signer, and opens Android's installer. If
   unknown-source permission is needed, grant it and repeat the action.
2. Return to setup. Keep Android Settings and setup visible in split screen,
   request a fresh Wireless Debugging code, and choose **Pair companion**.
   This authorizes its independent identity; pairing Store alone does not.
   An already authorized legacy 5555 connection is also supported.
3. Choose **Verify recovery and enable**. Store backs up its identity and running
   APK, verifies the companion connection, and only then enables self-updates.
   Setup reports errors and links to the companion's recovery status screen.

Wireless Debugging must be re-enabled after reboot on the tested phone; pairing
is retained. Port 5555 also needs reopening on devices that close it at reboot.
Readiness is rechecked before every Store update. Removing the companion or
clearing its data loses its independent identity, backup and escrow.

## Build and distribution

```sh
./gradlew :app:assembleRelease
```

Store depends on `:recovery:assembleRelease` and bundles that APK. Both releases
use signing.properties. The companion no longer embeds Store, avoiding circular
APK dependencies. Store instead transfers its currently running signed APK
before each update, avoiding a stale recovery build baked into an old companion.

Store version codes must increase. Use `-PstoreVersionCode=N` and optionally
`-PstoreVersionName=X` for subsequent releases. A newer Store release must still
be published in the user's authorized server catalogue; this integration does
not publish artifacts or create catalogue entries automatically. The companion
does not need to appear in the catalogue.

## Trust and backup

The version-2 AIDL service requires a signature permission and checks the caller
package and certificate. Store verifies the companion signer and minimum version
before binding or sending secrets. Escrow uses Android Keystore AES-GCM and atomic
no-backup storage. APK transfer uses a read-only file descriptor and verifies
SHA-256, package, version and signer before accepting it and again before repair.
Only single-APK Store installs are supported; split installs fail setup explicitly.

Only the Store ADB identity is escrowed, not tokens or settings. Self-update
opt-in is excluded from Android backup/device transfer. Debug packages cannot
use production recovery. No Android authorization bypass is used.

## Self-update lifecycle

Store releases are included in update checks. Automatic execution requires both
the ordinary update policy and explicit recovery setup. Every Store installation,
including a foreground request, refreshes its identity and APK backup, tests the
independent connection, and records an attempt UUID with current/target versions,
signer and deadline before invoking an installer. Failure stops with a setup
message. Companion packages remain excluded from ordinary automatic updates.

After package replacement, a receiver asks the companion's ADB connection to start
Store. The rendered Store activity restores its identity and acknowledges health;
Application startup alone does not. An idle-safe alarm monitors the deadline and
is rescheduled at boot. Missing health means Needs attention, never an automatic wipe.

An unresolved attempt cannot be overwritten by a new backup or update. Rejected
installations close an attempt only while the previous version remains installed.
Interrupted repair enters Manual recovery. After recovery, the user must inspect
Store and choose **Resolve attempt** before another update can proceed.

## Explicit repair and data loss

Repair requires explicit confirmation. It verifies the installed signer and saved
APK, connects independently, and first attempts in-place replacement. Only a
definite package-manager rejection permits the confirmed uninstall/reinstall
fallback. An ambiguous result stops for manual inspection. A watchdog disconnects
stalled ADB operations.

Full uninstall deletes Store settings, login, caches and other local-only data.
The companion preserves recovery metadata, the signed APK and encrypted ADB
identity. Restored Store health is checked against the saved version, not the
failed target version. A second timeout becomes Manual recovery. There is at most
one repair per attempt and no automatic retry loop.

## Verification

Unit tests cover version/UUID health binding, recovery to the older saved version,
deadline transitions, interrupted repair, rejected updates, prevention of
overwriting unresolved attempts, and self-update policy gating. Run
`./gradlew lint test`.

Device checks must cover bundled companion installation, independent pairing,
identity/snapshot backup, a higher-version self-update, revoked authorization,
reboot and startup failure. Exercise destructive recovery only on a disposable
test device or after explicit acceptance of Store-local data loss. Build/unit
success alone does not establish that these device scenarios passed.

### Integration validation (2026-09-05)

On a disposable API 36.1 emulator, signed release IPC provisioning passed: identity
escrow, verified APK snapshot, independent legacy-ADB authorization, and rejection
of stale/wrong-version attempt acknowledgements. A signed version-3-to-4 fixture
then replaced Store through its own ADB channel; the replacement receiver asked
the companion to launch Store and the rendered app acknowledged that attempt as
HEALTHY. Self-replacement terminates instrumentation, so host-side package-version
and companion-state checks are required (the runner reports process termination).

Opt-in device tests live in `RecoveryProvisioningDeviceTest`. Build them using
`-PdeviceTestBuildType=release :app:assembleReleaseAndroidTest`; ordinary test runs
skip them. Never run the upgrade fixture test on a personal phone.

Still requiring physical-device review: bundled installer UI, independent companion
Wireless Debugging pairing, revoked authorization, reboot, and deliberately broken
startup/destructive recovery. No artifacts were published during these tests.
