# Pesce e pesce — Android USB device management

Status: implemented, 2026-09-20. The Android client includes the USB transport,
offline tools, catalog batches, and automated coverage. Physical phone-to-phone
USB validation is pending. See [the feature guide](PESCE_E_PESCE.md) for setup,
verification status, and the remaining hardware checks.

## Goal and agreed behavior

One Android phone running LelloStore controls another Android device through a
USB data cable. The receiving device does not need LelloStore or internet access.
The controller needs USB host/OTG support; the receiver needs USB debugging
enabled and must authorize the controller's ADB key. Neither device needs root.

The user selected these product decisions:

- Add a fourth main navigation destination named **Pesce e pesce**. Use the order
  Catalog, Updates, Pesce e pesce, Settings, with a simple vector fish icon.
- Allow USB tools without a LelloStore login or network connection. Require the
  controller's existing account for catalog access and downloads.
- Copy the running LelloStore installation and offer to launch it. Leave login,
  self-ADB authorization, and recovery-companion setup to the receiver.
- Keep app installations on USB. Network ADB tools enable port 5555, test the
  listener, and return it to USB mode; wireless app management is deferred.
- Let users select several catalog apps and install them sequentially, using
  the controller's existing stable/beta preferences and account access.

Support Android 7/API 24 and newer on both ends initially. USB hardware, cable
roles, and vendor restrictions require physical-device validation; do not claim
universal compatibility. Start with one selected receiver and its current Android
user. Exclude split APK installation, local APK picking, arbitrary shell commands,
uninstall/reboot tools, account copying, automatic receiver updates, and wireless
installation from this feature's initial scope.

## User experience and lifecycle

- The main tab opens a device screen containing connection guidance, receiver
  model/Android version, connection status, and the three actions: **Install
  LelloStore**, **Enable ADB TCP/IP**, and **Install apps**. Keep the receiver's
  name visible throughout operations. Supply English and Italian strings.
- Add a **Pesce e pesce · USB tools** entry on the login screen that opens the
  same feature through a standalone route. Keep the existing signed-in main
  navigation; do not introduce a general guest catalog. Login from the remote
  app picker returns to that picker without losing the USB session. Session
  expiry gates catalog work but leaves offline USB actions available.
- Enumerate devices on entry and handle attachment/detachment while the feature
  is open. Users explicitly connect; do not auto-launch LelloStore on cable
  insertion. If multiple ADB devices are present, require selecting one.
- Show distinct states for unsupported USB host hardware, no ADB interface,
  USB permission needed/denied, connecting, awaiting receiver authorization,
  ready, operation in progress, expected ADB restart, and disconnected/error.
  Guidance covers data-capable cables, USB roles, OTG settings, and enabling USB
  debugging on the receiver; the app cannot enable that initial setting itself.
- A dedicated, non-exported foreground service owns the selected connection and
  operation queue after USB permission is granted. It survives navigation,
  rotation, login, and screen locking. Use `connectedDevice`, adding `dataSync`
  while downloading catalog APKs, with the required manifest permissions and a
  notification naming the receiver and offering Stop. Do not restart work
  automatically after process death or reboot.
- Serialize receiver operations. Disable transport changes and device switching
  during an install. Detachment stops the batch, preserving completed results;
  reconnecting requires explicit resume after checking the receiver again.
  Only an expected daemon restart triggers bounded automatic reconnection.
- Show download, verification, transfer, and installation progress separately.
  Keep per-app results visible until dismissed. An individual package rejection
  allows the batch to continue; loss of transport, account access, or an uncertain
  installation result pauses it. Cancellation cannot promise rollback after the
  receiver has committed an install.

## Implementation design

### USB and ADB transport

The app currently uses `libadb-android` 3.1.1 for local TCP/TLS installation.
Its connection implementation constructs a network socket directly, so USB is
not an existing pluggable transport. Introduce an isolated `:remote-adb` Android
library. The implementation relocates RSA-authentication code and protocol
constants from that pinned version, retains upstream notices, and uses a locally
implemented serialized protocol state machine with a packet-aware transport
interface. Reading explicit ADB stream-close packets avoids depending on a queued
stream's EOF behavior. Leave the existing self-ADB and recovery
connections on their current dependency. This avoids sharing mutable connections
or changing their working TLS behavior.

- Use `UsbManager` to find ADB interfaces by class/subclass/protocol
  `0xff/0x42/0x01` and bulk IN/OUT endpoints. Declare USB host support as optional
  (`required=false`) so other LelloStore features remain installable everywhere.
  Use a package-scoped USB permission callback, verify permission before opening,
  and release the claimed interface and connection on every exit path.
- Implement USB framing with correct header/payload boundaries, partial reads
  and writes, API 24–27 transfer-size limits, and required zero-length packets.
  Bound incoming sizes and negotiate protocol capabilities from the peer, rather
  than assuming the receiver matches the controller's Android version.
- Generate one persistent RSA identity for remote-device control in the
  controller's private no-backup storage. Keep it separate from self-ADB and
  recovery identities, and reuse it for the limited TCP listener test. Copying
  LelloStore never transfers this identity or grants the receiver self-ADB access.
- Make blocking I/O cancellable through bounded waits and transport closure.
  Defaults: 60 seconds for authorization, 30 seconds without transfer progress,
  120 seconds for installation commit, and 30 seconds for expected daemon restart.
  Idle connections must not fail merely because they have no pending command.

Reference the [Android USB host APIs](https://developer.android.com/develop/connectivity/usb/host),
[pinned connection implementation](https://raw.githubusercontent.com/MuntashirAkon/libadb-android/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb/AdbConnection.java),
and [ADB USB packet-boundary requirements](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/zero_length_packet.md).
The service declarations follow the [foreground service type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types).

### Application boundaries and interfaces

- Add domain interfaces for `RemoteDeviceSession` (discovery, connection state,
  selected receiver, disconnect) and `RemoteDeviceOperations` (copy LelloStore,
  TCP/IP controls, install batch, cancel/resume). Expose connection and operation
  state as flows, with typed device information, progress, and per-app results.
  Keep Android USB handles and arbitrary ADB commands internal to implementations.
- Follow the existing UI ViewModel/interactor and Hilt patterns. Use a dedicated
  remote installation coordinator, never register it as a fallback in the local
  `InstallationCoordinator`. Remote work must not invoke local package install,
  update `InstalledAppsRepository`, or trigger controller self-update recovery.
- Extract authenticated download and SHA-256 verification from
  `DownloadManagerImpl` into a shared `VerifiedApkProvider`. Preserve local install
  and retained-APK behavior. Give callers operation-owned files so local and remote
  downloads cannot overwrite or delete each other's data. Fail closed when
  expected verification metadata is missing or mismatches.
- Keep remote status out of the local installed-app database. Store a small
  private operation journal for interrupted installs: receiver identity, user,
  expected package/version, package-manager session ID, and commit status. On
  reconnect, reconcile only against the same identified receiver; never replay
  an ambiguous operation automatically. No HTTP API or Room migration is needed.
- Emit connection and operation events through the existing audit logger, using
  operation IDs, package/version, phases, durations, and outcomes. Do not log
  private keys, tokens, or unrestricted shell output.

### Actions and installation semantics

**Install LelloStore**

Snapshot the controller's installed `applicationInfo.sourceDir` APK and its
package/version into an operation-owned file before transfer. Copy the exact
running build, including its debug/release package identity, without re-signing
or modifying it. If `splitSourceDirs` is nonempty, explain that copying this
installation is unsupported. Preserve the receiver's existing app data on an
upgrade. Offer **Open on receiver** after success or when the same/newer version
is already installed; do not automatically launch or configure the app.

**Enable ADB TCP/IP**

Read the receiver's network addresses over USB, then open the ADB service
`tcpip:5555`; this is a daemon service, not a command run inside an Android shell.
Explain network exposure before enabling and do not promise reboot persistence.
Treat the expected daemon disconnect as a restart, then verify the setting after
reconnecting. A disconnect alone is not proof of success.

Display separate results for listener configuration and network reachability.
Offer a foreground-only authenticated TCP test to addresses obtained from that
receiver, with bounded timeouts; do not scan the LAN or install through that
connection. No network address is a valid outcome for an offline receiver. Offer
**Return to USB mode**, using `usb:` over the reconnected USB transport; when the
cable is absent, instruct the user to reconnect it. Do not change Android's TLS
Wireless debugging configuration. The daemon services are documented by their
[AOSP implementation](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/services.cpp).

**Install catalog apps**

Provide a searchable multi-select picker within Pesce e pesce. Resolve versions
through the existing release-policy logic and account access, showing the exact
version/channel and receiver before starting. Freeze the chosen versions for that
batch. Check each against the receiver's SDK; report incompatibility rather than
silently substituting an older release. Download and verify on the controller,
then install one app at a time. The receiver needs no store account or internet.

For both installation actions, use receiver package-manager sessions through ADB
(`install-create`, streamed `install-write`, `install-commit`). Target the receiver's
current Android user explicitly and stop if that user changes during the batch.
Skip already-installed equal/newer versions. Do not force downgrades, uninstall
on signature conflict, or bypass vendor restrictions. Report package-manager
errors with actionable messages, including insufficient storage and incompatible
signatures/ABIs.

Abandon uncommitted sessions on cancellation where possible, and clean up known
unfinished sessions when that receiver reconnects. Require a complete successful
commit response and remote package verification for normal success. If connection
loss obscures the commit result, mark it uncertain and query that receiver after
reconnection before offering retry; the controller's package manager provides no
evidence about remote success.

## Delivery and acceptance tests

Initial hardware: the user will supply a **OnePlus Nord 3 as controller** and a
**USB-C to USB-C cable**. Use a **second Nord 3 as the first receiver**, then a
**OnePlus 6 as the compatibility receiver**. Both are available test candidates;
neither connection has been validated. Cable data support, USB role negotiation,
and the installed Android/OxygenOS versions on each device remain to be verified
on hardware. The Nord 3's
[official manual](https://service.oneplus.com/content/dam/support/user-manuals/en/OnePlus_Nord_3_5G_User_Manual_EN.pdf)
documents an OTG connection setting that turns off after ten minutes without use;
include this in setup guidance when applicable to the installed OS.

Deliver the work in three increments. Physical validation is a release gate,
not a reason to replace this plan with another unbounded design exercise.

1. **USB feasibility:** implement the isolated transport and an internal test
   harness. On two unrooted Android devices, prove USB discovery/permission, RSA
   authorization, receiver information, a fixture APK installation, and repeated
   detach/reconnect. Include large transfers and endpoint-aligned payloads. Record
   device models, OS versions, cable/adapter arrangement, and results. If hardware
   is unavailable, retain an explicit unvalidated status and do not ship the
   feature as verified.
2. **Offline tools:** add the tab/login entry, service lifecycle, copying
   LelloStore, launch action, and TCP/IP controls. Validate with both devices
   offline and with no LelloStore installed on the receiver. Verify that the
   copied app opens but carries no controller account, preferences, or ADB keys.
3. **Catalog batches:** extract the shared verified downloader and add selection,
   release-policy resolution, queueing, progress, cancellation, and reconciliation.
   Verify mixed success/failure batches and independence from local installations.

Automated coverage must include protocol negotiation/authentication, fragmented
I/O and packet boundaries, permission denial, authorization timeout, detachment,
cancellation before/after commit, uncertain outcomes, and cleanup. Use fakes for
the transport and package-manager responses; physical tests cover actual USB.

Add ViewModel/navigation tests for the fourth tab, signed-out entry, returning
from login, and session expiry without losing offline tools. Test stable/beta
selection, receiver SDK checks, SHA mismatch, concurrent local/remote downloads,
equal/newer installed versions, signature conflicts, and preservation of local
installed state and self-update behavior.

Run `./gradlew lint test` and `./gradlew :app:assembleDebug` from `android`, plus
the relevant UI instrumentation tests and existing self-ADB device checks.
Validate service behavior on current target-SDK devices, configuration changes,
screen locking, process termination, and API 24–27 USB transfer compatibility.
Update user documentation and the specification with connection instructions,
tested hardware, limitations, and the distinction between copying the app and
provisioning automatic updates. Publishing is outside this implementation plan.
