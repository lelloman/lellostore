# Spike: Self-ADB Installation Architecture for an Alternative Android App Store

## Objective

Assess the technical feasibility of an Android app store that uses Android's Wireless ADB functionality to install APKs on the same device on which the store is running.

The motivation is Google's Android developer-verification model. Google's current documentation states that unregistered/unverified applications may still be installed using ADB. The hypothesis to investigate is whether an on-device store acting as an ADB client can use the same ADB installation path and therefore retain the ADB exemption.

This should initially be treated as an implementation and policy hypothesis, not as an established bypass.

## Proposed Architecture

The system would require a one-time bootstrap installation of the store application through an external ADB host:

```text
PC / Mac / another Android device
              │
              │ adb install store.apk
              ▼
       ┌───────────────┐
       │ Android Phone │
       │               │
       │  Store App    │
       └───────────────┘
```

After installation, the user enables:

**Developer Options → Wireless Debugging**

The store then acts as an ADB client and pairs with the `adbd` instance running on the same device.

The resulting architecture would be:

```text
┌──────────────────────── ANDROID DEVICE ────────────────────────┐
│                                                               │
│  Alternative Store                                           │
│       │                                                       │
│       ├──── HTTPS/etc. ────> App Repository                   │
│       │                        │                              │
│       │                   download APK                        │
│       │                        │                              │
│       │ <──────────────────────┘                              │
│       │                                                       │
│       ▼                                                       │
│   AppX.apk                                                    │
│       │                                                       │
│       ▼                                                       │
│   Embedded ADB Client                                         │
│       │                                                       │
│       │ TLS ADB connection                                    │
│       │                                                       │
│       ▼                                                       │
│   localhost / Wireless Debugging endpoint                     │
│       │                                                       │
│       ▼                                                       │
│      adbd                                                     │
│       │                                                       │
│       │ shell / package-manager install                       │
│       ▼                                                       │
│   Android Package Manager                                     │
│       │                                                       │
│       ▼                                                       │
│   AppX installed                                              │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

The important architectural distinction is that automatic installation would not invoke Android's conventional third-party PackageInstaller flow. It would submit installation operations through the device's authenticated ADB debugging interface. The conventional Package Installer remains an interactive foreground fallback when no silent channel is available.

## Why This May Be Possible

Android 11+ supports Wireless Debugging using authenticated ADB connections.

Wireless ADB does not fundamentally require the ADB client to reside on a desktop computer. An Android application can implement the ADB protocol itself.

There is also precedent for self-device Wireless ADB. Projects such as Shizuku demonstrate that an Android application can use Android 11+'s Wireless Debugging pairing mechanism to bootstrap access to the device's ADB environment without requiring a permanently connected computer.

The proposed store would use a similar underlying capability, but for package installation.

Conceptually:

```text
Store App
    │
    │ ADB protocol
    ▼
   adbd
    │
    │ shell privileges
    ▼
Package Manager
    │
    ▼
Install APK
```

## Relationship to Google Developer Verification

Google's current developer-verification documentation explicitly distinguishes ADB installation from ordinary application distribution.

Google currently states that unregistered apps can continue to be installed or updated using ADB.

The key hypothesis for this spike is therefore:

> Does an APK installation initiated through an authenticated ADB session receive the same treatment regardless of whether the ADB client is a development workstation, another Android device, or an application running on the target device itself?

If the exemption is based on the Package Manager installation path / shell UID / ADB install origin rather than the physical location of the ADB client, a self-ADB installation may receive the same treatment as:

```text
adb install application.apk
```

from a development workstation.

This MUST be experimentally verified.

## Important: Do Not Assume This Is a Permanent Loophole

There are two separate questions.

### 1. Technical feasibility

Can an ordinary Android application:

* implement or embed an ADB client;
* pair with the device's own Wireless Debugging service;
* maintain/re-establish that connection;
* transfer or otherwise expose an APK to the ADB environment;
* invoke the relevant Package Manager installation operation;
* install and update arbitrary APKs successfully?

Existing Wireless ADB tooling suggests that much of this is technically feasible.

### 2. Developer-verification behavior

Does an installation performed this way actually receive Google's ADB exemption?

This is the critical unknown.

Google may currently identify ADB installations through properties such as:

* calling UID (`shell`);
* PackageInstaller session origin;
* installer-of-record;
* install-source metadata;
* Package Manager flags;
* ADB-specific Package Manager APIs;
* Developer Verifier integration;
* some combination of the above.

The spike should determine the actual behavior rather than infer it from documentation.

Google could also change this behavior later if self-ADB becomes a mass-market distribution mechanism.

## Suggested Spike

Build the smallest possible proof of concept.

### Phase 1 — Prove Self-ADB

Create a minimal Android application containing an ADB client.

Have the user enable Wireless Debugging and perform the required pairing.

Verify:

```text
Store App → local Wireless ADB → adbd
```

Demonstrate execution of a harmless shell command and record:

* Android version;
* device manufacturer;
* connection endpoint;
* pairing mechanism;
* behavior across app restart;
* behavior across network changes;
* behavior across device reboot.

### Phase 2 — Install an APK

Attempt installation of a test APK through the resulting ADB session.

The important test is approximately equivalent to:

```text
adb install test.apk
```

but initiated entirely from the Android application.

Verify fresh installation, upgrade, downgrade where permitted, uninstall/reinstall, split APKs/APKS where relevant, and installation failure/error reporting.

### Phase 3 — Determine Install Attribution

Inspect what Android records for the installed package.

In particular determine:

```text
installer package
installing package
initiating package
originating package
installer UID
PackageInstaller session metadata
install reason/source
```

Compare three installations of the exact same APK:

```text
A. Conventional sideload
   File manager → PackageInstaller → APK

B. External ADB
   PC → adb install → APK

C. Self-ADB
   Store → local adbd → APK
```

Ideally, capture `dumpsys package`, PackageInstaller/session information, logcat around installation, and any other relevant Package Manager state for all three.

The central question is whether **B and C are indistinguishable to the verification mechanism**.

### Phase 4 — Test Developer Verification

Use an APK/developer identity that exercises the unregistered-developer path.

Compare:

```text
                    Ordinary       External       Self
                    sideload          ADB          ADB
                       │               │             │
                       ▼               ▼             ▼

Unregistered APK      ???             PASS          ???
```

If:

```text
ordinary sideload → blocked/verification flow

external ADB      → installs

self-ADB          → installs
```

then the core hypothesis is demonstrated.

If self-ADB triggers verification while external ADB does not, determine where Android distinguishes the two paths.

### Phase 5 — UX Feasibility

If the technical experiment succeeds, determine whether the required setup is acceptable for real users.

Prototype a guided flow around:

```text
Install store
     ↓
Enable Developer Options
     ↓
Enable Wireless Debugging
     ↓
Pair Store
     ↓
Store establishes ADB connection
     ↓
Ready
```

Evaluate how much of this can be guided or automated versus requiring users to navigate Android Settings manually.

The setup complexity may ultimately be a larger obstacle than the underlying ADB implementation.

## Security Considerations

Pairing an application with ADB is a significantly stronger authorization than granting permission to install unknown applications.

The architecture therefore needs careful security analysis.

At minimum:

* private ADB keys must be protected;
* APK signatures/hashes should be verified independently;
* repository metadata must be authenticated;
* arbitrary shell execution should not be exposed through the UI or IPC;
* downloaded APKs must be treated as untrusted;
* exported Android components must not allow another application to issue ADB commands through the store;
* the store should expose the minimum ADB functionality required for installation;
* ADB authorization/revocation behavior must be understood.

A compromised store with persistent ADB authorization could potentially have substantially more capability than a conventional app store.

## Compatibility Matrix

Test at minimum:

```text
Android 11
Android 12
Android 13
Android 14
Android 15
Android 16
current Android release / verification rollout build
```

Include Pixel/AOSP as the baseline and at least Samsung plus another major OEM.

OEM modifications to Developer Options, Wireless Debugging, networking, background execution, and Package Manager behavior may materially affect feasibility.

## Questions the Spike Must Answer

The spike should produce concrete answers to these questions:

1. Can an Android app reliably pair with the same device's Wireless ADB endpoint?

2. Can it reconnect without repeating pairing every time?

3. Can the connection survive or recover from reboot and network changes?

4. Can the application install arbitrary APKs through that connection?

5. Can it install split APKs and perform application updates?

6. What UID actually initiates the Package Manager operation?

7. What installer/install-source metadata does Android record?

8. Is a self-ADB installation indistinguishable from an external `adb install` from Package Manager's perspective?

9. Most importantly, **does Google's developer-verification system treat the self-ADB installation as an ADB-exempt installation?**

10. Does this behavior differ between Android versions, Google Play system updates, certified/non-certified devices, or OEM implementations?

11. What user interaction is required to initially establish and subsequently maintain the ADB relationship?

12. What security exposure results from granting the store this level of access?

## Success Criteria

The architecture is technically viable if the team can demonstrate:

```text
Unregistered APK
       │
       ▼
Alternative Store
       │
       ▼
Self Wireless ADB
       │
       ▼
adbd / shell
       │
       ▼
Package Manager
       │
       ▼
Installed successfully
```

with no external computer or second device required after initial setup.

The stronger result would be demonstrating that the exact APK fails or enters Google's developer-verification flow through ordinary sideloading while succeeding through both external ADB and self-ADB.

That A/B/C experiment is the most important deliverable of the spike.

## Reference Material

Google Android Developers — ADB / Wireless Debugging:
https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi

Google Android Developers — Developer Verification FAQ:
https://developer.android.com/developer-verification/guides/faq

Shizuku — Wireless Debugging Setup:
https://shizuku.rikka.app/guide/setup/

## Expected Deliverable

The spike should conclude with one of four findings:

**A — Works and verification-exempt**

Self-ADB installation works and receives the same developer-verification treatment as external ADB.

**B — Works but verification enforced**

Self-ADB installation technically works, but Android distinguishes it for developer-verification purposes.

**C — ADB limitation**

Self-ADB works generally, but package installation is prevented or materially restricted.

**D — Operationally impractical**

The mechanism works but pairing, reconnection, OEM behavior, security implications, or required user interaction make it unsuitable for an app-store experience.

The goal of the spike is not merely to prove that an APK can be installed. The critical result is establishing **exactly where Google's developer-verification enforcement boundary lies and whether a locally originated ADB installation crosses that boundary.**

## Experimental Results — 2 September 2026

### Test device and environment

The initial experiment was performed on:

```text
Device: OnePlus CPH2493
Android: 16
API level: 36
USB device serial: OZQ49XDILZ4PTSHI
Wi-Fi address during test: 192.168.1.22
```

The device has Google's Developer Verifier package installed:

```text
Package: com.google.android.verifier
Version: 1.0.958871038
Version code: 69029
Target SDK: 37
```

However, the following device setting was observed:

```text
verifier_verify_adb_installs=0
```

Therefore, this experiment proves the ADB transport, installation mechanism, and Package Manager attribution. It does **not** yet prove behavior under future developer-verification enforcement for ADB installs.

### Legacy ADB result

The device already had an authenticated legacy ADB listener on TCP port 5555. The prototype connected from inside LelloStore through:

```text
127.0.0.1:5555
```

It also remained reachable from the development machine over the LAN at:

```text
192.168.1.22:5555
```

Android displayed its normal ADB RSA authorization prompt for the key generated by LelloStore. After the user granted authorization, the in-app ADB client executed:

```text
id
getprop ro.product.model
```

and received:

```text
uid=2000(shell)
CPH2493
```

The ADB identity was stored in the application's `noBackupFilesDir`. After force-stopping and reopening the application, it reconnected without another authorization prompt and again obtained `uid=2000(shell)`.

This proves:

* an ordinary Android application can act as an ADB client against the same device;
* loopback legacy ADB provides a real shell-UID session;
* the application can retain its authorized identity across process restarts.

Legacy port 5555 is not an appropriate primary onboarding path for ordinary users. It is commonly enabled using an external ADB host, may not survive reboot, and on this device listened on all network interfaces rather than loopback only. It should be treated as an opportunistic compatibility or diagnostic channel unless its exposure can be constrained safely.

### Wireless Debugging TLS result

Modern Android Wireless Debugging was enabled and the prototype used mDNS discovery to locate the rotating TLS endpoint. The ADB client reported:

```text
connected=true
host=192.168.1.22
```

Android independently logged:

```text
Received WIFI TLS connected key message: ... LelloStore Self-ADB Spike
```

The prototype then opened a shell stream over that TLS connection and received:

```text
uid=2000(shell)
CPH2493
```

This proves that the persisted LelloStore identity can authenticate to Android's modern Wireless Debugging service and execute shell commands. The rotating connect port was successfully discovered; it did not need to be hard-coded.

The production implementation must still test pairing from a completely fresh identity, recovery after reboot and Wi-Fi changes, mDNS behavior across OEMs, and bounded cancellation/timeouts.

### APK installation result

A separate harmless fixture APK was built with:

```text
Package: com.lelloman.store.selfadbtest
Permissions: none
Code: none
```

Using the proven legacy loopback connection, the prototype streamed the APK into this ADB service:

```text
exec:cmd package install -S <apk-size>
```

Android returned:

```text
Success
```

No Package Installer confirmation activity was displayed. The fixture appeared in the installed package list.

Package Manager recorded:

```text
installerPackageName=null
installerPackageUid=-1
initiatingPackageName=com.android.shell
originatingPackageName=null
packageSource=1
```

The critical finding is:

> A self-ADB install initiated from inside LelloStore is recorded as initiated by `com.android.shell`, not by the LelloStore application package.

This is consistent with an external `adb install` path and supports the developer-verification hypothesis. It is not sufficient on its own to prove that future Developer Verifier versions will treat the paths identically.

The same APK-stream installation still needs to be repeated over the TLS connection specifically. TLS shell access and legacy self-ADB installation have each been proven; their combination is implemented and is tracked by the compatibility-validation story.

### Installation-channel abstraction

Installation is no longer hard-coded directly into the download manager. The prototype now models each mechanism as an installation channel with metadata:

```text
id
displayName
requiresUserInteraction
priority
supportsBackgroundInstallation = !requiresUserInteraction
```

The production application registers these channels:

| Priority | Channel | Requires user during install | Background-capable |
|---:|---|:---:|:---:|
| 10 | Legacy ADB | No | Yes |
| 20 | Wireless Debugging TLS | No | Yes |
| 100 | Android Package Installer | Yes | No |

Foreground requests prefer channels that do not require user interaction, then fall back to the conventional Package Installer if the silent channels are unavailable.

Background requests exclude every channel whose metadata declares `requiresUserInteraction=true`. The coordinator must never open the Package Installer or a settings activity from a background installation request.

Failover distinguishes these outcomes:

* **Installed** — installation completed; stop.
* **User action started** — an eligible foreground channel opened its UI; stop.
* **Unavailable** — no install was submitted; trying the next channel is safe.
* **Permission required** — remember the actionable reason and continue only where appropriate.
* **Failed** — stop by default because the install state may be ambiguous.
* **Failed, fallback explicitly allowed** — the channel knows no install was submitted, so the next channel may be tried.

This conservative rule prevents an ambiguous transport failure from submitting the same installation twice through two different channels.

### Build and test result

The installation coordinator has unit coverage for:

* silent-channel preference;
* exclusion of interactive channels in background mode;
* foreground fallback to an interactive channel;
* stopping after a definitive or ambiguous install failure.

The Android unit tests and lint pass, and the signed release variant builds successfully. Both self-ADB channels are integrated into the production installation coordinator. Settings provides separate setup and connection tests for legacy port 5555 and Wireless Debugging TLS; no general-purpose shell UI or exported ADB IPC is exposed.

### Library decision and licensing caveat

The spike uses `libadb-android` 3.1.1 because it supports both legacy ADB and Android 11+ TLS pairing/connect flows with the repository's current Kotlin and compile-SDK versions.

The core library is used under Apache-2.0, but its SPAKE2 dependency is LGPL-3.0. The dependency decision and distribution obligations are recorded in `android/THIRD_PARTY_NOTICES.md`. The private ADB identity is unique per app installation, stored in Android's private no-backup directory, and never exposed through UI or IPC.

## Proposed User Experience

### Distribution assumption: hand-provisioned devices

The likely initial distribution model is not anonymous self-service installation. LelloStore will be installed by hand for known users, and the person installing it can configure the device before handing it over.

Under this model, the installer—not the eventual user—performs the complete one-time setup:

1. Install LelloStore, preferably through external USB ADB.
2. Enable Developer Options.
3. Enable Wireless Debugging.
4. Accept the current Wi-Fi network and select the OEM's **Always allow on this network** option when available.
5. Pair LelloStore's generated ADB identity using the six-digit pairing flow.
6. Verify that LelloStore can reconnect through TLS and obtain `uid=2000(shell)`.
7. Perform a harmless test installation through the TLS channel.
8. If the device exposes **Disable adb authorization timeout**, consider enabling it after explaining the security tradeoff; otherwise an inactive authorization may eventually require provisioning again on some Android/OEM versions.
9. Confirm that LelloStore is not battery-restricted if reliable background updates are desired.

Once this has been completed, the user should not normally need to confirm individual installations or enter another pairing code. Android's documentation states that a paired device remains paired until it is explicitly forgotten or ADB authorizations are revoked, and trusted networks can reconnect automatically.

This is not an unconditional guarantee of zero future interaction. User action may be needed again if:

* Wireless Debugging or Developer Options is turned off;
* the phone joins a new, untrusted Wi-Fi network and Android asks whether to allow Wireless Debugging on it;
* Android or the OEM disables Wireless Debugging after a reboot, security event, policy change, or prolonged inactivity;
* the paired LelloStore device is forgotten or all ADB authorizations are revoked;
* LelloStore is uninstalled, its data is cleared, or its private ADB identity is otherwise lost;
* a system update changes Wireless Debugging behavior;
* both silent channels are unavailable and a foreground install falls back to Android's Package Installer.

For a background installation, the last case does not open an activity. LelloStore retains the verified APK and posts an actionable notification instead.

The practical target for a known user on a stable trusted Wi-Fi network is therefore:

```text
installer provisions once
        ↓
user receives configured phone
        ↓
silent installs and updates require no user confirmation
```

Legacy port 5555 can also be enabled during hand provisioning, but it should not be relied upon for persistence and should not be exposed unnecessarily. Wireless Debugging TLS remains the preferred durable channel.

### Product language

Do not describe the feature as a verifier bypass or promise that it will defeat future Google policy. Present it as an optional installation method:

```text
Standard installation
Android asks you to confirm each app installation.

Automatic installation (advanced)
Install and update apps without a confirmation screen after one-time setup.
Uses Android Wireless Debugging and grants LelloStore powerful installation access.
```

The standard Package Installer path should remain available without any Developer Options setup.

### What LelloStore can and cannot automate

LelloStore cannot enable Developer Options or Wireless Debugging on an ordinary unprivileged device. Android intentionally requires the user to enable these settings.

LelloStore can:

* explain the exact steps;
* deep-link to the relevant Settings screens where supported;
* detect the advertised pairing and connection services with mDNS;
* accept the pairing code;
* generate and securely retain its ADB identity;
* verify the connection with a harmless command;
* reconnect to the rotating TLS port later;
* fall back according to the installation request's foreground/background mode.

### Recommended onboarding flow

Automatic installation should be explicit opt-in and presented as an advanced feature.

#### Step 1 — Explain the tradeoff

Before opening Settings, explain:

```text
Automatic installation uses Android's Wireless Debugging feature.
It lets LelloStore install apps without asking you to confirm every installation.
Only continue if you trust this copy of LelloStore.
```

Offer:

```text
Set up automatic installation
Keep standard installation
```

#### Step 2 — Enable Developer Options

If Developer Options are unavailable, show OEM-aware instructions, usually:

```text
Settings → About phone → tap Build number seven times
```

Provide an **Open About phone** or **Open Settings** button where Android/OEM intents allow it. Do not imply that LelloStore enabled the option itself.

#### Step 3 — Enable Wireless Debugging

Guide the user to:

```text
Settings → System → Developer options → Wireless debugging
```

Provide an **Open Wireless debugging** button using the system Settings intent where supported. Ask the user to turn the switch on.

Wireless Debugging normally requires the device to be connected to Wi-Fi. OEM wording and placement vary.

#### Step 4 — Pair LelloStore

Ask the user to tap:

```text
Pair device with pairing code
```

The pairing code dialog must remain open while LelloStore receives the six-digit code. Switching back to the app can close or expire that dialog on some devices.

The preferred production UX is therefore:

1. LelloStore starts a short-lived foreground setup service and displays a notification.
2. The user remains on Android's pairing-code screen.
3. LelloStore discovers `_adb-tls-pairing._tcp` automatically, including the temporary pairing port.
4. The notification exposes an Android inline-reply action: **Enter pairing code**.
5. The user enters only the six-digit code without leaving Settings.
6. LelloStore pairs, discovers `_adb-tls-connect._tcp`, verifies `uid=2000(shell)`, and updates the notification to **Automatic installation ready**.

Split-screen can be offered as a fallback, but should not be the primary flow. Requiring the user to copy both a port and a code manually should also be avoided when mDNS discovery works.

#### Step 5 — Confirm readiness

Return to LelloStore and show a persistent installation-method status:

```text
Automatic installation: Ready
Wireless Debugging paired
Last checked: just now
```

Provide:

```text
Test connection
Use standard installation instead
Forget pairing
Open Wireless debugging settings
```

Forgetting pairing should remove LelloStore's private ADB identity and explain how to revoke the paired device from Android Settings as well.

### Normal installation behavior

For a foreground install:

```text
silent channel available
        ↓ yes
install without confirmation

        ↓ no silent channel
open Android Package Installer
```

For a background install or update:

```text
silent channel available
        ↓ yes
install and post completion notification

        ↓ no silent channel
retain the verified APK and post an actionable notification
```

The background failure notification could say:

```text
Update downloaded — action needed
Automatic installation is unavailable. Reconnect Wireless Debugging or install manually.
```

It must not unexpectedly launch Android's Package Installer, Developer Options, or any other activity from the background.

### Connection-state model

The settings UI should distinguish:

* **Not set up** — no ADB identity/pairing has been created.
* **Wireless Debugging off** — identity exists, but no TLS service is advertised.
* **Disconnected** — service is advertised but connection failed.
* **Ready** — authenticated shell connection verified.
* **Needs pairing again** — the key was revoked, replaced, or rejected.
* **Unsupported** — device, Android version, network, or OEM behavior prevents setup.

Do not describe all failures as "pairing failed." Each state needs a specific recovery action.

### Recommended channel policy

The prototype's requested failover order is legacy ADB, Wireless TLS ADB, then Package Installer. For production UX, the visible and recommended automatic method should be Wireless Debugging TLS.

Legacy port 5555 may still be attempted opportunistically when already available and authorized, but users should not normally be instructed to expose it on their Wi-Fi network. Consider adding further channel metadata before release, such as transport security, setup requirement, and whether availability is expected to survive reboot.

### Remaining UX and product questions

Before shipping, decide:

1. Is automatic installation opt-in per device or per user account?
2. Should foreground installs silently use ADB as soon as it is ready, or should users choose a preferred method?
3. Should automatic app updates be separately enabled from silent manual installs?
4. How long should a verified APK be retained when no background-capable channel is available?
5. What notification and foreground-service behavior is acceptable during pairing and large installs?
6. How prominently should the ADB security warning be repeated after onboarding?
7. Should LelloStore periodically test readiness, or only reconnect when an installation is requested?
8. How should pairing revocation and device migration be explained?
9. What OEM-specific instructions are required for Samsung, Pixel/AOSP, OnePlus/Oppo, Xiaomi, and others?
10. What is the fallback if Android or Google changes the treatment of self-ADB installs?

## Recovery companion specification

LelloStore should have a separately installed recovery companion whose purpose is to
restore a working LelloStore without repeating the device's manual ADB provisioning.
The companion is a recovery boundary, not an alternative Store UI or a second update
client.

### Recovery guarantees

The design should aim for the following guarantees:

* A download failure or rejected APK installation does not damage the currently
  installed LelloStore. Android package replacement is atomic, so no recovery action
  is required in this case.
* If an update installs successfully but the new LelloStore cannot start or report
  healthy, the companion can restore a known-good recovery build.
* Destructive recovery wipes only LelloStore. The companion and its data must survive.
* Recovery preserves the device-specific ADB identity used by LelloStore, allowing
  the restored application to authenticate without another pairing prompt.
* No ADB private key is common to multiple devices or embedded as a static secret in
  either APK.

These guarantees still depend on Developer Options and Wireless Debugging remaining
enabled and on Android continuing to accept at least one of the companion's paired
ADB identities.

### Independent ADB identities

Provision two unique, locally generated ADB identities:

* **Store identity (L)** belongs to LelloStore and is used for ordinary installation
  and self-update operations.
* **Recovery identity (R)** belongs to the companion and is used only for diagnosis
  and recovery.

Both identities must be paired with the device during hand provisioning. The
companion retains an encrypted recovery copy of identity L. Identity R must remain
independent: retaining L alone is not sufficient because a broken LelloStore cannot
use it to repair itself.

Private keys must be generated per provisioned device. They must not be compiled
into an APK, shared across installations, included in Android Auto Backup, logged, or
sent to the LelloStore server. The companion should protect its key material using
app-private storage and Android Keystore-backed encryption where supported.

### Trust and key handover

LelloStore and the companion should be signed with the same Android application
signing certificate. Key recovery must use a narrow IPC interface protected by a
signature-level permission and must additionally verify the caller's package and
signing certificate.

The companion cannot directly write into LelloStore's application sandbox. After a
recovery install, LelloStore requests identity L from the companion through the
protected recovery interface, stores it in its own `noBackupFilesDir`, verifies that
it can authenticate, and acknowledges successful import. The companion remains the
recovery copy after handover; it must not delete its copy merely because import
succeeded.

The handover protocol should be versioned and should transfer only the minimum data
needed for recovery. Authentication tokens should not be copied by default. If
preserving device enrollment or account state is required, define and secure those
fields explicitly rather than treating the whole LelloStore data directory as a
backup.

### Update health protocol

Before beginning a self-update, LelloStore records an update attempt with the
companion. At minimum this record contains:

* current and target version codes;
* package name and expected signing-certificate digest;
* start time and attempt identifier;
* enough information to identify the last known-good build;
* a deadline by which the updated application must report healthy.

After package replacement, the new LelloStore handles
`ACTION_MY_PACKAGE_REPLACED`, completes its essential startup checks, reconnects
through an automatic installation channel, and sends a signed, attempt-specific
health acknowledgement to the companion. Merely launching a process is not a health
signal.

The companion must tolerate reboots and delayed startup. It should not wipe the Store
as soon as a deadline is missed: first notify the provisioner/user and retry bounded
diagnostics. Automatic destructive recovery should only be enabled after the health
criteria and timeout policy have been validated on supported devices.

### Recovery sequence

Use the least destructive applicable action:

1. Confirm the expected LelloStore package is present and verify its signing
   certificate. Never operate on a package with an unexpected signer.
2. Check whether LelloStore actually reports healthy. A failed or cancelled update
   normally leaves the previous version intact and ends recovery here.
3. If possible, attempt a signed in-place repair using an installable last-known-good
   APK. Do not assume an older `versionCode` can be installed over a newer production
   package.
4. If LelloStore remains unhealthy, use recovery identity R to fully uninstall it.
   This deliberately removes LelloStore's application data.
5. Install the signed recovery APK bundled with the companion.
6. Start or notify the recovered LelloStore through the signature-protected recovery
   protocol.
7. Restore identity L, verify an authenticated ADB shell connection, and rebuild
   ordinary Store state from authoritative server/package data.
8. Report recovery success to the user. If any verification fails, stop and expose a
   clear manual-recovery state instead of looping.

`uninstall -k` is not the dependable recovery path. Although it retains application
data, an older recovery build may be unable to read a database, preferences, or files
written by the broken newer version. That can reproduce the crash indefinitely. A
full wipe makes the recovery build's data model deterministic.

### Bundled recovery build

The companion APK contains a signed, known-good LelloStore recovery APK as an
immutable asset. The recovery build must:

* use the production LelloStore package name and signing certificate;
* be capable of bootstrapping from an empty data directory;
* understand the current key-handover protocol;
* support the minimum server protocol needed to fetch a current release;
* avoid depending on database state from a later LelloStore version;
* validate every downloaded replacement APK's package name, signer, version, and
  digest before installation.

Because the bundled APK ages with the companion, the companion itself must be kept
current. A production downgrade flag cannot be assumed to work; uninstalling the
broken Store before installing the bundled version is what permits an older recovery
version to be restored.

The recovery build is best treated as a small, forward-compatible bootstrap mode. It
only needs to restore identity, connect to the service, install a current healthy
LelloStore, and report useful diagnostics. Keeping this surface small reduces the
chance that the recovery image ages out.

### Data-loss contract

The recovery UI must state clearly that last-resort repair removes LelloStore's local
data. Before approving or enabling recovery, classify Store state into:

* **Authoritative remote state**, which can be downloaded again;
* **Recovery metadata**, which the companion deliberately preserves;
* **Disposable local state**, such as caches and downloaded APKs;
* **Irreplaceable local state**, which must either be exported explicitly or accepted
  as data loss.

The design is safe to automate only when the first three categories cover all
required behavior. The companion's own removal, data clearing, signing-key mismatch,
loss/revocation of identity R, or disabling Wireless Debugging still requires manual
provisioning.

### Loop prevention and observability

Recovery must be a state machine with a bounded attempt count, not an open-ended
watchdog. Persist the recovery reason, affected versions, timestamps, selected
installation channel, command result, health-check result, and final disposition.
Logs must redact ADB keys, pairing codes, authentication tokens, and APK URLs carrying
credentials.

After one unsuccessful destructive recovery, stop retrying automatically. Keep the
companion usable, retain its keys and diagnostics, and present a manual action such
as **Repair LelloStore** or **Contact the provisioner**.
