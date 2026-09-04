# Self-ADB validation

Last updated: 2026-09-04

## Decision

Self-ADB is a **conditional go for explicitly provisioned, known devices**. It is not a
universal silent-install mechanism and must retain Android's package-installer fallback.

- Port 5555 works after one-time host provisioning and on-device authorization, but the
  port is normally closed by a reboot.
- TLS Wireless debugging retains the pairing identity across a reboot on the tested
  OnePlus device, but Android turns Wireless debugging off. The user must enable it again.
- Android may show an ADB authorization prompt. Installation cannot be called unattended
  until that prompt has been accepted, preferably with **Always allow** selected.
- AOSP API 36 exposed a libadb reconnect that can remain blocked after authorization
  arrives too late for the original transport. The device harness imposes a 90-second
  deadline (configurable through `SELF_ADB_TEST_TIMEOUT_SECONDS`) and treats expiry as a
  failure instead of promising an unattended update.
- No claim is made that self-ADB bypasses an OEM, enterprise, Play Protect, or other
  package-verification policy. The package manager remains authoritative.

## Recorded matrix

| Device / OS | Transport | Scenario | Result | Attribution / notes |
| --- | --- | --- | --- | --- |
| AOSP emulator, Android 16 / API 36 | `127.0.0.1:5555` | Fresh signed release install | Pass | `initiatingPackageName=com.android.shell`; installer and originator were null. No installer UI appeared after authorization. |
| AOSP emulator, Android 16 / API 36 | `127.0.0.1:5555` | Replacement/reconnect | Conditional | Package replacement completed once. A later run whose authorization arrived after the client transport was destroyed wedged in libadb; see limitations below. |
| OnePlus CPH2493 | `127.0.0.1:5555` | Provision and reconnect | Pass | Connection returned an authenticated shell while the port was provisioned. Reboot closes port 5555. |
| OnePlus CPH2493 | TLS Wireless debugging | Pair, reconnect, reboot | Pass for connectivity | Pairing survives reboot. Wireless debugging is disabled by reboot and must be enabled again before discovery/reconnect succeeds. |
| Samsung / Pixel / managed enterprise device | Both | OEM and policy comparison | Not run | No representative hardware or enforcing management profile was available. Keep fallback enabled and validate before adding a model to an unattended fleet. |

The physical OnePlus TLS check validated pairing, discovery, authentication, and shell
access. A full APK stream over TLS was not recorded in this pass. This is intentionally
called out rather than treating shell access as equivalent to a successful package commit.

## Verification and behavior coverage

The automated suite covers the behavior that does not require a particular device policy:

- channel ordering and foreground fallback from unavailable silent channels;
- background work excluding the interactive package installer;
- background failover between silent channels;
- definitive/ambiguous install failure stopping duplicate submissions;
- update-worker success, notification fallback, and retry behavior;
- download cancellation and reuse of a verified APK for foreground retry;
- parsing successful and failed package-manager responses;
- post-stream version checking when the ADB close packet is reported as an I/O error.

The on-device harness additionally asserts that the requested version is installed and,
on Android 11+, that Android reports `com.android.shell` as the initiating package. This
distinguishes the self-ADB path from LelloStore's ordinary package-installer path.

Policy modes that allow, delay, or reject an install must be tested on the target OEM or
management profile. A rejection is a product outcome, not a reason to weaken or bypass the
verifier. LelloStore should report the package-manager response and retain the verified APK
for an explicit foreground retry where possible.

## Running the device harness

Enable port 5555 or Wireless debugging, authorize the **LelloStore** ADB identity on the
device, then run from `android/`:

```shell
ANDROID_HOME=/path/to/Android/Sdk \
  ./scripts/validate-self-adb.sh legacy /path/to/fixture.apk DEVICE_SERIAL
```

Use `tls` instead of `legacy` for a TLS-paired debug build. The fixture must be safely
installable on the selected device. The script builds and installs the debug test APKs,
stages the fixture in app-private storage, performs a background install, checks the
installed version, and prints Android's install-source attribution.

The ordinary connected test suite skips this destructive opt-in test. This prevents CI or
a developer's attached phone from unexpectedly replacing an application.

## Rollout checklist

Before enabling unattended installation for a device model and policy combination:

1. Record fresh install and upgrade results with the exact OS build and verifier policy.
2. Revoke ADB authorization and confirm the app reports an actionable unavailable state.
3. Re-authorize, repeat an upgrade, and confirm the installed version and attribution.
4. Reboot and validate the documented reprovisioning step for the chosen transport.
5. Disable Wi-Fi during transfer and confirm the worker terminates or retries without a
   second package submission.
6. Exercise foreground cancellation and background execution constraints.
7. Keep the interactive package-installer channel enabled until every relevant combination
   has passed and transport hangs are independently bounded.
