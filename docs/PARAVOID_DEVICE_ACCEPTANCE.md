# Store-backed Paravoid device acceptance

This opt-in test uses the production LelloStore router, database migrations,
upload services, durable worker, publication checks, online signer and APK
personalizer. A local mock OIDC issuer supplies isolated admin/user identities.
No production account, database, key or endpoint is used.

A loopback TLS proxy terminates the certificate already trusted by Paravoid's
non-debuggable release fixture and forwards its delivery requests to the Store.
It only maps `/v1/...` to `/api/paravoid/v1/...`; the Store generates every head,
grant and payload response. Android retains normal TLS/hostname verification.
The fixture's private test keys are copied into a private temporary signing
configuration; bearer values never enter test output.

## Run

Build the upstream HTTPS acceptance fixture as documented in
`../paravoid-android/delivery/device-tests/HTTPS-RELEASE.md`. It must be an empty,
keyed, non-debuggable release pinned to `https://127.0.0.1:18765/`. Do not regenerate
its TLS certificate without rebuilding its APK.

Start a **fresh disposable emulator** on API 30 or 36.1 with an AVD name beginning
`LelloStoreParavoid`. The runner refuses physical devices, other AVD names, an
existing user-installed apps and an existing reverse mapping on port 18765.
It does not boot, wipe or stop emulators automatically. The default mode does not uninstall apps; the UI mode has the narrow fixture reset described below.

```sh
bash scripts/check-paravoid-device.sh \
  /absolute/path/to/android-sdk \
  /absolute/path/to/paravoid-android/compatibility/complete-v1 \
  emulator-5590
```

The SDK needs build-tools 36.0.0 and platform 36. Java, OpenSSL, Python 3, adb,
Rust dependencies and local listening sockets are required. Test databases,
issued APKs and copied signing keys are temporary. The fixture installation is
retained on the disposable emulator for inspection; stop that emulator after use.
No sibling repository files are edited.

## Store Android UI mode

Build the Store client and its instrumentation companion first:

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline
```

Then run from the repository root against a fresh dedicated emulator:

```sh
PARAVOID_STORE_UI=1 bash scripts/check-paravoid-device.sh \
  /absolute/path/to/android-sdk \
  /absolute/path/to/paravoid-android/compatibility/complete-v1 \
  emulator-5610
```

This mode installs `com.lelloman.store.debug` and its instrumentation companion.
The instrumentation graph supplies an isolated test identity, a temporary local
Store URL and an in-memory catalog database. HTTP DTOs, authenticated HTTP client,
repositories, download verification, installation channels, installed-state refresh
and UI are the production implementations. It initializes WorkManager with the
Hilt worker factory because the test application replaces the production one.
The debug Store talks to the isolated API through a second loopback reverse mapping
on port 18766; the installed Paravoid shell still uses trusted HTTPS on port 18765.
No test login or endpoint override is compiled into the production application.

Host UI automation opens the catalog entry, selects Install, confirms Android's
installer, checks the installed Open action, and opens Manage app updates. After
grant revocation it selects Repair update access, confirms Reinstall and Android's
installation dialog, and checks the controls again. Each instrumentation run
requires an actual package-manager installation timestamp change. The initial
installer completion task remains available in the background: repair must open a
new installation attempt even though the APK URI and version are unchanged. If a
Play Store image offers an optional cloud scan, the harness selects its per-install
local-install link without changing device-wide verification settings. The backend
must record exactly two used grants, with only the original revoked.

The missing-grant check installs an empty canonical fixture first. UI mode then
removes **only that just-installed empty fixture**, before its first Store Install;
it never removes an accepted payload or performs an uninstall during repair.
Both Store test packages and the repaired fixture remain for inspection. Preflight
refuses any existing user-installed apps or either occupied reverse mapping.
Use separate AVDs and ports from other Android sessions; do not share an emulator
while its UI is being automated.

## Assertions

1. Upload the unchanged signed shell and VPK through authenticated Store APIs,
   validate their jobs, and atomically publish the installer/bootstrap pair.
2. An installed unpersonalized empty shell denies updates without HTTP requests.
3. Acquire a personalized APK from Store, verify exact delivered size/hash, install
   it in place, download via trusted HTTPS and activate the payload through the
   real confirmation UI.
4. Re-sign the fixture components under a new payload version/release identity,
   upload/validate/publish it through Store, and activate it from the shell controls.
   The installed APK's path and hash must remain unchanged. Components are identical;
   this demonstrates a distinct payload update, not a behavioral A/B change.
5. Revoke the first Store grant, observe HTTP 403 and the access-error UI, and verify
   the accepted application still runs.
6. Acquire a same-version repair APK with a new Store grant, reinstall in place,
   and successfully check for updates with the active payload retained.
7. Stop delivery and cold-launch the accepted payload offline. Both grants must
   have recorded actual Store delivery requests, with only the first revoked.

## Recorded result — 2026-09-23

All seven assertions passed on dedicated x86_64 API 30 and API 36.1 emulators
against the same unchanged upstream release artifacts. The final extended runs
completed in 47.21 seconds and 60.75 seconds respectively. Both emulators were
stopped afterward. The harness uses no production backend logic changes or
Paravoid runtime patches. Four safety tests cover refusing physical devices,
unowned AVDs, existing user apps/Store test installations and occupied reverse mappings.

### Store Android UI result — 2026-09-23

The complete UI mode passed on API 30 (`LelloStoreParavoidUI30`, emulator-5610,
122.29 seconds) and API 36.1 (`LelloStoreParavoidUI36`, emulator-5612,
193.35 seconds), using the same source artifact hashes below. Both runs confirmed
Android's installer for initial install and same-version repair, opened controls
from Store, activated the second payload without changing the installer, observed
revocation, repaired access while retaining that payload, and cold-launched offline.
The database assertions verified exactly one install acquisition and one repair
acquisition, both with used grants and only the original revoked.

These runs exposed and verified fixes for three production client bugs:

- The default `install` purpose was omitted by JSON serialization, causing HTTP 422.
- Reusing the same APK URI could reopen an old installer completion task without
  installing new bytes. Every attempt now starts a fresh installer task.
- Opening the exported updates alias could foreground a payload Activity above it.
  The Store action now opens the controls with the old activities above it cleared.

Validation also passed: 197 Android app/API/UI unit tests, 25 Python tests,
Rust all-target/all-feature Clippy with warnings denied, and formatting checks.
The emulators were stopped after completion. No production service was changed.

## Scope

This exercises a real signed, installed Android fixture against Store-backed HTTPS
and the production Paravoid controller/verifier/lifecycle. The default mode installs acquired APKs with adb. The opt-in Store UI mode instead
uses the Android client's catalog, install, repair and controls actions. Its test
identity replaces browser/OIDC login; it does not validate production login,
a physical ARM64 device, real Pezzottify account data, or normal → shell → normal
migration. Those remain distinct gates.

Source artifacts used on 2026-09-23:

- Shell SHA-256: `da79439b2b05bf777cadc971ebd0f4b31a4d0c1b2f6cc6ed18dd203394b4256a`
- VPK SHA-256: `3a462353b910d796b46a2564bc5f05a20a82f53f48b7762387fc43d834385880`
