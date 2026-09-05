# LelloStore recovery companion

The recovery companion is a separate APK (`com.lelloman.store.recovery`). It is a
small recovery boundary, not a second store client. Its data survives a destructive
repair of `com.lelloman.store`.

## Build and install

```sh
./gradlew :app:assembleRelease :recovery:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r recovery/build/outputs/apk/release/recovery-release.apk
```

Both release APKs use `signing.properties`. The recovery build depends on the Store
release build and embeds that exact, signed APK as `lellostore-recovery.apk`. It also
generates and bundles a SHA-256 sidecar. Recovery checks the asset digest, package
name, and signing certificate before using it.

Do not distribute `releaseDebugSigned` artifacts as recovery builds. Debug variants
have different application IDs and intentionally cannot use the production recovery
service.

## Provisioning

Install the companion, then start LelloStore once. If LelloStore already has an ADB
identity, startup immediately escrows it through the protected service; otherwise,
using LelloStore's port-5555 channel creates and escrows it. Then open **LelloStore
Recovery** and choose **Provision / test recovery ADB**. Android should show a second
ADB authorization dialog named **LelloStore Recovery**. This creates the independent
per-installation recovery identity in the companion's `noBackupFilesDir`.

The current recovery transport is local legacy ADB at `127.0.0.1:5555`. The port must
be open and the companion identity must be authorized. A reboot closes that port on
the tested OnePlus device, so recovery cannot run until port 5555 is provisioned
again. The companion reports this as a manual recovery condition; it never bypasses
Android's ADB authorization.

Whenever LelloStore uses its legacy ADB channel, it sends its own ADB identity to the
companion through the recovery service. The service:

- requires a signature-level Android permission;
- verifies the caller package and signing certificate again at runtime;
- verifies that the private key matches the certificate;
- encrypts the identity with a non-exportable Android Keystore AES-GCM key;
- stores the ciphertext in no-backup storage;
- never copies login tokens or general LelloStore application data.

After a clean recovery install, LelloStore requests the escrowed identity through the
same protected interface and imports it before creating a new identity.

## Update health protocol

Immediately before a self-update installation, LelloStore records a UUID attempt,
current/target versions, expected signer, start time, and deadline. A replacement
Store queries the pending attempt after essential application initialization and
acknowledges the exact UUID only when its installed version reaches the target.
The companion schedules an idle-safe alarm for the deadline and recreates it after
a reboot, so a failed Store does not need to be running for the attempt to become
**Needs attention**.

A rejected installation closes the attempt when the old version is still installed.
A reboot or missed deadline changes the persisted state to **Needs attention**. It
does not uninstall anything and does not start repair automatically.

## Explicit recovery and data loss

The companion exposes **Repair LelloStore** only for a missed health attempt. The
action requires a confirmation that states the data-loss boundary. Recovery then:

1. verifies that any installed Store is signed like the companion;
2. verifies the embedded APK digest, package, and signer;
3. tries the non-destructive in-place install first;
4. only after that fails, fully uninstalls LelloStore and installs the known-good APK;
5. starts the restored Store and waits for identity import and health acknowledgement.

A full uninstall deletes all LelloStore-local state: login/session state, settings,
caches, and any other local-only data. Server catalogue/account state can be fetched
again. The companion preserves only recovery attempt metadata and the encrypted ADB
identity escrow.

Only one destructive attempt is permitted for an update UUID. A failed attempt ends
in **Manual recovery** and cannot loop. The persisted status includes versions,
timestamps, reason, and destructive-attempt count. ADB keys, tokens, pairing codes,
and authenticated APK URLs are not logged.

## Validation matrix

| Scenario | Expected result |
|---|---|
| Download or install rejected | Old Store remains; attempt is closed without recovery |
| Healthy self-update | Exact attempt is acknowledged and marked healthy |
| Updated Store cannot start | Deadline becomes Needs attention; no automatic wipe |
| Reboot before acknowledgement | Boot receiver reevaluates the persisted deadline |
| Port 5555 closed or ADB authorization revoked | Test/repair stops with a manual connection error |
| Bundled APK modified, wrong package, or wrong signer | Repair stops before uninstall |
| Bundled APK older than broken Store | In-place repair may fail; explicit confirmed full uninstall permits clean install |
| First destructive repair fails | State becomes Manual recovery; further automatic/destructive attempts are blocked |
| Companion removed or its data cleared | Recovery identity and escrow are lost; manual provisioning is required |

Unit tests cover acknowledgement binding, missed deadlines, rejected updates, and the
single-attempt destructive bound. Device validation must never exercise the destructive
button on a device whose LelloStore state has not been backed up or accepted as lost.
