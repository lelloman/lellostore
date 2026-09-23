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
existing fixture installation and an existing reverse mapping on port 18765.
It does not boot, wipe, uninstall or stop emulators automatically.

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
Paravoid runtime patches. Three safety tests cover refusing physical devices,
unowned AVDs, existing installations and occupied reverse mappings.

## Scope

This exercises a real signed, installed Android fixture against Store-backed HTTPS
and the production Paravoid controller/verifier/lifecycle. APK installation is
performed by adb after Store acquisition. It does not exercise the LelloStore
Android client's login/install UI, a physical ARM64 device, real Pezzottify account
data, or normal → shell → normal migration. Those remain distinct gates.

Source artifacts used on 2026-09-23:

- Shell SHA-256: `da79439b2b05bf777cadc971ebd0f4b31a4d0c1b2f6cc6ed18dd203394b4256a`
- VPK SHA-256: `3a462353b910d796b46a2564bc5f05a20a82f53f48b7762387fc43d834385880`
