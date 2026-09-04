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

## Networking and downloads

The remote API resolves the current server URL for every request, so changing a
setting does not leave a client pinned to an old endpoint. It sends the current
access token and reports an expired session through the shared handler.

The application owns active download jobs by package name. A download streams to
cache, reports progress, verifies its SHA-256 digest, and launches Android's
package installer through a FileProvider URI. Cancelling a download cancels the
underlying coroutine and removes partial state instead of merely changing the
UI. Installed-package state is refreshed when detail screens resume.

## Updates

The update checker compares cached catalog version codes with installed package
versions. WorkManager schedules periodic checks according to user preferences,
including the Wi-Fi constraint, and posts local notifications when updates are
available. The updates screen observes the same state and can start the normal
authenticated download path.

## Installation channels

Verified APK downloads are submitted to an ordered installation coordinator.
Foreground and background requests first try silent channels and only foreground
requests may fall back to Android's interactive Package Installer:

1. Local legacy ADB at `127.0.0.1:5555`, when provisioned.
2. Android Wireless Debugging using mDNS endpoint discovery and authenticated TLS.
3. Android Package Installer for foreground requests.

This is also the migration-safe default for existing installs. Settings persists the
complete ordered channel list and each enabled state. The coordinator applies that order
for every request, skips disabled channels, and still excludes interactive channels from
background work. Channels introduced by a later app version are appended enabled; the
settings layer and preference store both prevent disabling every channel.

The ADB channels stream the exact verified APK length to `cmd package install -r`
and serialize access to their shared connection. A failure after streaming starts
is treated as ambiguous and never falls through to a second installer; when the
connection closes after a self-update, the installed version is checked before
reporting failure.

Settings exposes bounded connection tests and the six-digit Wireless Debugging
pairing flow. The generated ADB identity is stored in the application's private
no-backup directory. No arbitrary shell command or ADB connection is exposed by
UI or IPC. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the dependency
and distribution review.

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
