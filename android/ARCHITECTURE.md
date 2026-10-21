# Android architecture

This document describes the implemented module boundaries. Source code and
tests are authoritative for details; this file intentionally avoids duplicating
class-level implementation plans.

## Modules

```text
app ───────► ui ─────────► domain
 │           │               ▲
 ├────────► remoteapi ────────┤
 ├────────► localdata ────────┤
 └────────► logger ◄──────────┘
```

- `:app` is the Android application and composition root. It owns activities,
  Hilt bindings, platform services, download/installation behavior, and the
  periodic update worker.
- `:ui` owns Compose screens, navigation, view models, screen state, and the
  interactor interfaces consumed by view models.
- `:domain` owns application models and repository/store abstractions shared by
  all implementation modules.
- `:remoteapi` owns Ktor transport, bearer-token attachment, session-expiry
  handling, DTOs, and DTO-to-domain mapping.
- `:localdata` owns Room persistence, DataStore preferences, encrypted auth
  state, and the transactional catalog cache.
- `:logger` provides logging abstractions and implementations.

Dependencies point inward toward `:domain`; `:ui` never depends on `:app`.
Platform-specific implementations are wired only by `:app`.

## State and data flow

Compose screens render immutable state exposed by view models. User actions are
forwarded to view models, which call narrow interactor interfaces. Implemented
interactors coordinate domain stores, the remote API, and Android platform
services. One-shot navigation and permission requests use screen events rather
than being embedded in persistent state.

Catalog refresh follows this sequence:

1. Fetch and decode the authenticated `/api/apps` response.
2. Replace the Room cache in one transaction.
3. Expose the new snapshot to observers only after the transaction succeeds.
4. Preserve the previous cache when the request or replacement fails.

Application detail refresh returns the just-fetched value directly as well as
updating persistence, so callers do not race a later database observer.

## Authentication

AppAuth performs Authorization Code with PKCE. Authorization and token endpoints
are obtained from OIDC discovery rather than constructed from fixed path
suffixes. Encrypted shared preferences retain tokens and identity fields; a
session-expiry handler clears invalid state consistently.

The store server and OIDC issuer are separate concerns. The initial server URL
comes from `default.server.url` in `local.properties`, defaults to the production
store, and can be changed by the user. Production input accepts HTTPS only.

A device can skip sign-in entirely with a **device token**. It gets one by device-code
enrollment (login screen → **Set up this device without signing in**, the main action on a
TV), or root provisions it as `noBackupFilesDir/device-token.json`
(`{"version":1,"server_url":…,"name":…,"token":"lellostore_dev_…"}`). Either way the token
ends up in `DeviceCredentialStore`: `noBackupFilesDir/device-credential.bin`, AES-256-GCM
sealed with a non-exportable Android Keystore key and written atomically. A legacy file is
imported on start; until the import succeeds the file itself is used, and a recorded
fingerprint keeps an imported file from overwriting a later enrollment (the file stays one
release for downgrades and is deleted in the release after).

`CompositeAuthStore` prefers the device credential over the OIDC `AuthStoreImpl`: the state is
`Authenticated` with the token's name, and the token is sent as a plain bearer credential,
only to the server named in it. It is read once per process on an IO thread, and the auth
state stays `Loading` until then so the splash never races to login. A differing
`server_url` is applied through `ConfigStore` without `ServerSelection`'s wipe; the catalog
cache is replaced on the next refresh and an OIDC session stays bound to its own server.
Enrollment stores the credential and switches the state at once, without a restart. Settings
and the profile sheet show "Signed in as this device" and hide logout and server changes;
Settings → **Forget this device** deletes the credential. A 401 goes through the usual
session-expiry path, which marks the token rejected in memory (the credential is kept),
falls back to the OIDC state and shows a notice on the login screen; the next process start
retries it. The push broker only accepts JWTs (it records `push.unsupported_credential`), so
with a device token the update connection keeps the catalog WebSocket open instead.

## Device enrollment

`EnrollmentViewModel` (`:ui`) runs the device side of
[device enrollment](../docs/DEVICE_ENROLLMENT.md#android-client): it asks for the name, starts
an enrollment through `DeviceEnrollmentApi` (`:remoteapi`, a client without credentials or
redirects), shows the user code and a QR of the approval link, and polls while the screen is
started, following `interval`, `slow_down`, expiry (a new code, three at most) and network
backoff on a monotonic clock. `EnrollmentInteractorImpl` (`:app`) stores the approved
credential through `DeviceTokenAuthStore.provision` and `EnrolledDeviceSetup` turns on
automatic updates, periodic checks and the update connection.

The administrator side is `ApproveDeviceActivity`: Settings → **Approve a device** (only for
administrators, from `GET /api/me`) or the exported `com.lelloman.store.action.APPROVE_DEVICE`
started for result by another app. `ApproveDeviceViewModel` looks the code up (typed, or
scanned with the zxing scanner: link, JSON payload or bare code), shows the request, and
approves it as a new device or as a rekey of an existing token, or denies it, through
`DeviceEnrollmentAdminApi`. The activity has the hardening of `IssueCredentialActivity`.

## Credential requests from other apps

`IssueCredentialActivity` is an exported, confirmation-gated broker: another app starts
it for result with `com.lelloman.store.action.ISSUE_CREDENTIAL`, a credential type and a
subject name, and gets the credential back in the result after the user confirms. The
caller is identified with `getCallingActivity()`. `IssueCredentialViewModel` (`:ui`)
validates the generic extras, waits for the auth state, and calls the type's
`CredentialIssuer` at most once; issuers are a Hilt `@IntoSet` multibinding collected in
`CredentialIssuerRegistry`. The only type is `lellostore.device_token`, which creates a
device token through the administrator API with the signed-in session and returns the
same `device-token.json` the web admin page produces (`DeviceTokenFile.serialize`). The
credential exists only in memory and the result intent. See
[the credential broker contract](../docs/CREDENTIAL_BROKER.md) for the extras, errors,
a caller example and how to add a type.

## Networking and downloads

The remote API resolves the current server URL for every request, so changing a
setting does not leave a client pinned to an old endpoint. It sends the current
access token and reports an expired session through the shared handler.

The application owns active download jobs and a shared operation-state flow by
package name. Catalog, updates, and detail surfaces observe that flow rather
than maintaining local installation flags. A download streams to cache, reports
progress (published at most on every whole percent or 250 ms), verifies its
SHA-256 digest, and hands the file to an installation channel. Each operation
carries its `InstallationMode` and, on failure, a user-readable reason in the
shared state flow; the user-facing reason of a channel failure is the channel
that actually attempted the install, while the full channel list goes to the
audit log. An interactive installer handoff ends in `AWAITING_CONFIRMATION`,
not `COMPLETED`: the APK is kept for Android's installer and the package
snapshot is what later proves installation. Terminal states stay visible for
three seconds; the delayed removal only clears the entry if no newer operation
for the same package owns it. Foreground user operations run in application
scope and are protected by a `dataSync` foreground service that renders only
foreground-mode entries and stops as soon as none is in progress. Navigating
away therefore does not cancel an update, and when such an operation completes
or fails while the app is not visible, `OperationOutcomeNotifier` posts a
per-package result notification. Scheduled automatic updates use WorkManager's
foreground execution support and render only background-mode entries.
Cancelling a download cancels the underlying coroutine and removes partial
state instead of merely changing the UI.

A self-update kills the process that runs it, so its operation never reports an
outcome. `SelfUpdateTracker` writes a marker synchronously just before the APK goes
to the installer and keeps it after a reported installation or an installer handoff.
Every process start (including the one `MY_PACKAGE_REPLACED` causes) consumes the
marker: if the running version reached the target it refreshes the installed snapshot
of LelloStore, clears leftover operation notifications and announces "LelloStore
updated to X" once (a notification, and a snackbar when the UI shows it first).
The detail screen's result card is derived from the operation state and the last
failure; it is hidden while an operation runs, and a request that only joined an
operation already running is not a failure.

Installed-package state is a persistent Room snapshot of Android's authoritative
package-manager state. Silent installation success and package add, replace, or
remove broadcasts refresh that snapshot; its Room flows push the resulting
version change to every observing screen. Android 8+ no longer delivers those
implicit broadcasts to manifest receivers, so `PackageChangeMonitor` registers
the same handling at runtime from the Application; the manifest receiver covers
older versions. Detail resume and the foreground-return update check remain
reconciliation fallbacks for an interrupted or missed broadcast.

## Local logs

Settings → Local logs shows retained event counts, completed operation counts,
mean operation duration, and the latest 100 matching events with readable local timestamps.
Search matches event names, packages, IDs and fields. Clear removes retained segments
after confirmation; new events continue to be recorded. Export writes a JSONL
snapshot through Android's document picker. Statistics describe the retained window,
not lifetime totals; an interactive installer handoff is not proof of installation.

The logger keeps eight rotating 512 KiB segments (4 MiB maximum) in private
`noBackupFilesDir/audit`. Rotation evicts the oldest segment and preserves whole
records in chronological order. An interrupted final write is discarded. A single
background writer uses a bounded 256-event queue; overload or disk failures never
block updates and are counted in a subsequent `audit.dropped` event. Pending queued
records can be lost if Android kills the process; startup gets a new session ID.
Exports flush queued records before taking a consistent snapshot.

Schema 1 events have wall-clock and monotonic timestamps, a process session ID,
and named fields. Each update carries an operation UUID, package, requested version,
mode, and cumulative monotonic duration. Events cover metadata, cache reuse, download
bytes (at most every five seconds), verification, channel attempts/results, ADB lock,
connection, upload and response, installed snapshot refresh, terminal state and UI
cleanup. Package broadcasts independently record the observed installed version,
allowing an installed APK to be distinguished from an installer call still waiting.
Only explicitly structured events are persisted; free-form logs, credentials,
pairing codes, URLs and raw ADB output are excluded.

## Updates

The update checker compares cached catalog version codes with installed package
versions. WorkManager schedules periodic checks according to user preferences,
including the Wi-Fi constraint, and posts a local notification when updates are
available. That notification alerts only once, opens the Updates tab, and is
cancelled by the next check that finds nothing left to act on. Automatic updates
that fail are listed in it rather than retried silently, and a signature
mismatch is never retried. The updates screen observes the same state, can start
the normal authenticated download path, and shows each row's phase and outcome
(failure reason, missing install permission with a shortcut to grant it, or a
pending installer confirmation) inline.

While the authenticated app is foregrounded it keeps an authenticated WebSocket
to `/api/events`; catalog invalidations enqueue a serialized update check. Moving
to the background closes that connection and starts a bounded one-hour sequence
of inexact alarm checks at cumulative minutes 1, 3, 6, 10, 15, 21, 28, 36, 45,
and 55. Returning to the foreground cancels the alarm sequence and performs a
catch-up check. These mechanisms run independently of the long-term periodic
WorkManager interval.

## Installation channels

Verified APK downloads are submitted to an ordered installation coordinator.
Foreground and background requests first try silent channels and only foreground
requests may fall back to Android's interactive Package Installer:

1. Local legacy ADB at `127.0.0.1:5555`, when provisioned.
2. Android Wireless Debugging using mDNS endpoint discovery and authenticated TLS.
   On Android 11+ the channel reports itself unavailable immediately when the
   `adb_wifi_enabled` global setting is off, instead of waiting out discovery.
3. Silent Package Installer updates (Android 12+). A `PackageInstaller` session with
   `USER_ACTION_NOT_REQUIRED` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. Android only skips
   the prompt for updates of LelloStore itself or of packages whose installer of record (or
   update owner) is LelloStore, and only when the APK meets the Android version's target-SDK
   floor. The channel checks these conditions first and reports itself unavailable instead
   of creating a session. If Android still answers `STATUS_PENDING_USER_ACTION`, the session
   is abandoned and the next channel is tried. It ranks after the ADB channels because
   Google Play Protect asks to scan every APK it has not seen before ("App scan
   recommended") when installed this way, while it skips ADB installs unless "Verify apps
   over USB" is enabled. It still needs no ADB, so it keeps updates working when ADB is off.
4. Android Package Installer for foreground requests.

This is also the migration-safe default for existing installs. Settings persists the
complete ordered channel list and each enabled state. The coordinator applies that order
for every request, skips disabled channels, and still excludes interactive channels from
background work. Channels introduced by a later app version are enabled and inserted at
their default position, before the first saved channel that follows them by default; the
settings layer and preference store both prevent disabling every channel.

The ADB channels stream the exact verified APK length to
`cmd package install -r -i <LelloStore package>` and serialize access to their shared
connection. `-i` records LelloStore as installer of record, so the next update of that
package can use the silent Package Installer channel without ADB. A failure after streaming starts
is treated as ambiguous and never falls through to a second installer; when the
connection closes after a self-update, the installed version is checked before
reporting failure.

Settings exposes bounded connection tests and the six-digit Wireless Debugging
pairing flow. The generated ADB identity is stored in the application's private
no-backup directory. No arbitrary shell command or ADB connection is exposed by
UI or IPC. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the dependency
and distribution review.

## Recovery companion

The separately installed `:recovery` application monitors Store self-update attempts
through the versioned `:recovery-protocol` AIDL boundary. Both production APKs share
the release signer; IPC also requires a signature permission and runtime package/cert
verification. The companion owns an independent ADB identity, holds a Keystore-encrypted
copy of the Store identity, and retains a digest/package/signer-verified snapshot of the running Store
APK before each self-update. Store bundles the companion for installation through Settings.
The companion supports independent wireless pairing and legacy 5555. Explicit provisioning
gates self-updates; missed health acknowledgement only raises a needs-attention state. Any full wipe
is user-confirmed and bounded to one attempt. See
[RECOVERY_COMPANION.md](RECOVERY_COMPANION.md) for provisioning, limitations, and the
validation matrix.

## Testing and quality gates

Local JVM tests cover domain models, DTO mapping, repositories, auth/session
state, view models, download cancellation, and update behavior. Instrumented
tests cover user flows requiring Android services. Every module treats Android
lint warnings as errors, with only tool/dependency-version notices excluded.

Run the local gates from this directory:

```sh
./gradlew lint test
```

Instrumented tests require an emulator or device:

```sh
./gradlew connectedDebugAndroidTest
```

The application compiles against SDK 36, targets SDK 36, supports API 24 and
newer, and uses Java 11 bytecode. Development and CI use JDK 17 to run Gradle and
the Android Gradle Plugin.

## Persistent update connection

Settings → Updates → Keep update connection open is off by default (device enrollment turns
it on). When enabled while signed in, a `specialUse` foreground service protects the existing
authenticated catalog WebSocket while the user switches to other apps; with an OIDC session
the push broker takes over that connection, with a device token the catalog WebSocket itself
stays open. The service starts while
LelloStore is visible and displays an ongoing notification with a Stop action that
also clears the preference. Signing out stops it; signing back in while visible
starts it again if the preference remains enabled. Server changes reconnect the socket.
There is only one socket, shared across foreground and background operation.

Catalog events and successful reconnections enqueue update checks, including any
publications missed during a network outage. Existing download and auto-install
preferences still apply. Disabling the service in the background resumes warm polling.
The service is not sticky and does not start at boot: reopen LelloStore to resume
after Android stops it. A foreground service does not bypass Doze, network availability,
or package-installer confirmation requirements.

The same local log records the persistent push connection state, connection IDs,
WebSocket frame types and sizes, retry delays, heartbeat RTT and alarm lateness.
A process-lifetime observer records Android default-network availability, validation,
captive portal, metering, transport and blocking changes, plus screen, Doze and
power-saving state. These are Android observations, not proof that the server is
reachable. HTTP request/response metadata includes request IDs, method, host,
sanitized route, response status, time to headers and declared content length;
request failures record the exception type. Authentication discovery, refresh and
exchange have separate lifecycle events. Existing download events cover body transfer.

Inter-app events cover UnifiedPush requests, registration responses, delivery service
binding, message dispatch and acknowledgments, local update hints, recovery companion
calls, browser authorization and installer handoffs. Push delivery deferrals include
a reason. A queued WebSocket send or broadcast dispatch is not confirmation of receipt;
app acknowledgments are separate events. Logs exclude credentials, headers, query
strings, arbitrary URL path segments, request/response bodies, push payloads and
exception messages. Package names, server hosts and message IDs are retained for
correlation. Export explicitly shares the full retained log, regardless of the UI filter.

Logging is automatic and local: no upload, network probing or additional wake locks.
It can only observe events while the process runs; process death is visible as a new
session on the next startup, not as a guaranteed shutdown event. The byte limit means
retention duration varies with traffic. This instrumentation does not itself change
connection retries, background policy or delivery behavior.
