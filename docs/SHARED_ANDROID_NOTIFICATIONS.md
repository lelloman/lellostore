# UnifiedPush through LelloStore

LelloStore implements the Android AND_3.1.0 distributor interface and accepts
RFC8291-encrypted Web Push publications authenticated with RFC8292 VAPID.
The internal distributor-to-server connection remains an authenticated WebSocket.
This is an Android distributor, not a browser Push API service or a D-Bus distributor.

## Accounts and sender approval

LelloStore itself must be signed in to LelloAuth with shared notifications enabled.
Both the device credential and a valid OIDC session are required for its connection.
The server closes the connection when the token expires unless it is renewed.
Identity-provider revocation remains bounded by the access token's lifetime.

Recipient apps do not need LelloAuth accounts. They can use unrelated accounts or
no account. Administrators approve the sending backend's VAPID public key in
**Administration → Notifications**. Private signing keys stay on the sender.
Approved keys are required both during subscription creation and publication.

An app registers through the standard connector with the approved VAPID key and
receives a private endpoint. It sends the endpoint and its Web Push encryption
subscription (`p256dh`, `auth`) to its own backend through its normal authenticated
API. The sender uses a standard Web Push library; there is no LelloStore sender
credential, invitation, account-subject targeting, or custom message envelope.
An endpoint authorizes only its own registration and is bound to its VAPID key.

Missing VAPID keys receive `VAPID_REQUIRED`. Unapproved/suspended keys or exhausted
registration quotas receive `ACTION_REQUIRED`; LelloStore shows registration
failure guidance in its connection status. Malformed broadcasts are ignored.
An app that cannot supply VAPID is not supported by this service's approval policy.

## Delivery and lifetime

Sender → HTTPS push endpoint → SQLite ciphertext queue → authenticated WSS →
UnifiedPush MESSAGE broadcast → recipient connector decryption.

Payloads are opaque encrypted bytes, 1–4096 bytes inclusive, with
`Content-Encoding: aes128gcm`. LelloStore does not hold application decryption keys.
Subscription routing and transport metadata remain visible to the service.

- `TTL` is mandatory; retention is capped at 28 days and returned in the response.
- TTL zero attempts delivery only on the currently authenticated connection and
  is never replayed to a later connection.
- `Topic` replaces a pending message for the same subscription atomically.
  It does not provide application-level event ordering or clear posted notifications.
- `Urgency` defaults to normal. Android defers lower urgency messages according
  to power, Wi-Fi, and battery state.
- Normal acceptance returns 201 with a message Location and effective TTL.
- Invalid headers return 400, unsupported encoding 415, oversized bodies 413,
  missing/invalid VAPID 401/403, unavailable endpoints 410, and quotas 429.
- Delivery is acknowledged only after the recipient sends `MESSAGE_ACK`.
  ACKs are bound to token and random message ID. Android persists an ACK outbox;
  reconnect replays receipts safely. A socket write is not an acknowledgment.
- Retries can duplicate deliveries. Applications must handle their own event
  deduplication and notification presentation. There are no presentation receipts.

The server is authoritative for pending messages. Android does not persist
ciphertext for independent offline replay. Registrations and receipt state live
in no-backup storage; device credentials are protected by Android Keystore.

The connection retains the existing foreground service, adaptive heartbeat,
network callbacks, boot recovery, and battery guidance. Explicit logout, account
or server changes, disabling shared push, uninstall, and registration removal
invalidate applicable routes. Offline deletions are persisted and retried.
Startup checks the Doze exemption and shows a separate one-minute notification
with a battery-settings action when it is missing. If system notifications are
blocked, an in-app snackbar provides the action. Settings keeps a warning visible
until the exemption is granted and refreshes it when returning from Android settings.
Force-stopped apps require reopening; no exact Doze latency guarantee is made.

Store catalog updates use an internal control frame on the same connection.
Every reconnect triggers catalog reconciliation and the installed-app update
relay. The foreground catalog endpoint and polling fallback remain available.

## Administration and limits

Approve a server name and valid P-256 public key. Multiple approved keys can
belong to one sender and share its quotas. For rotation, approve the new key,
migrate apps to new subscriptions, then revoke the old key. A revoked key cannot
be reactivated: old endpoints must remain invalid.

Suspension rejects new publications and pauses queued delivery; expiration
continues. Revocation invalidates endpoints and cancels pending messages. The
Android distributor reconciles revocations before replay and informs recipients.
Use **Settings → UnifiedPush registrations** on Android to inspect/remove routes.

Defaults:

- Per sender: 10 messages/second, burst 100, 100,000 pending messages, 256 MiB.
- Global pending ciphertext: 1 GiB.
- Per device: 1,024 pending messages, 4 MiB; 4,096 active registrations.
- Per app/device: 1,024 registrations; 32 deliveries per server window and at most
  four simultaneous Android foreground bindings.
- Terminal message metadata: 30 days. Ciphertext is erased on terminal outcomes.
- Audit: latest 10,000 administrative records.

Metrics expose active connections, queued bytes/age, retained delivery outcomes,
and push API rejection counts by status. Capability path segments are redacted
from metric labels and excluded from request tracing. Never enable request-body
or authorization-header logging. Configure the reverse proxy to suppress or
redact `/api/push/v1/send/*` access logs as well.

## Backend contract and configuration

Set `NOTIFICATIONS_ENABLED=true` and `PUSH_PUBLIC_BASE_URL=https://store.lelloman.com`.
The latter must be an HTTPS origin without credentials, path, query, or fragment;
it determines returned endpoints and VAPID audience validation. Preserve WSS
upgrades and idle timeouts longer than heartbeat plus acknowledgment deadline.

| Interface | Authorization |
| --- | --- |
| `POST /api/push/v1/devices` | OIDC bearer; persisted installation ID/device credential |
| `DELETE /api/push/v1/devices` | Device bearer, permitting cleanup after logout |
| `POST /api/push/v1/subscriptions` | OIDC bearer + X-Device-Credential |
| `DELETE /api/push/v1/subscriptions` | Same; JSON `token` removes an owned registration, including a registration whose response was lost |
| `DELETE /api/push/v1/subscriptions/{id}` | Same, restricted to device ownership |
| `GET /api/push/v1/stream` | Device bearer, then OIDC authenticate frame |
| `POST /api/push/v1/send/{secret}` | Standard VAPID, restricted to the approved subscription key |
| `DELETE /api/push/v1/message/{id}` | Subscription VAPID signature |
| `/api/admin/notifications` | Existing OIDC administrator role |

Subscriptions contain `token`, `package`, `vapid`, and a persist-before-request
256-bit hexadecimal `endpoint_secret`. Replies contain `id` and `endpoint`.
The backend stores only the capability hash. A retry with the same fields is
idempotent; changing the key/capability replaces the old registration.

WSS subprotocol: `lellostore.push.v1`. Client frames are `authenticate` with
`access_token`, `ping` with `nonce`, and `receipt` with `id` and `token`. Server
frames are `ready`, `authenticated`, `pong`, `routes_begin`, chunked `routes`,
`routes_end`, `delivery`, `receipt_ack`, and `catalog_changed`. Delivery payloads
are base64-encoded for the internal JSON transport and decoded without mutation.
The Android interface carries the original ByteArray. No HTTP/2 browser
subscription/receipt service is exposed by this private WSS transport.

## Upgrade and migration

This release deliberately breaks the old custom push integration.
Migration `2026100401_unifiedpush.sql` drops the old notification sender grants,
credentials, enrollment records, subscriptions, and queued messages. Other Store
data and historical migration files remain intact. Android uses fresh broker
state and removes legacy broker state/credentials on first initialization.

Back up SQLite before release. Disable shared notifications during the coordinated
backend and Store APK upgrade; configure the public origin, approve sender keys,
then enable push and re-register recipient apps. The old API and Binder SDK have
been removed. Existing consumers such as Talìa require a separate migration;
there is no automatic conversion of sender credentials or subscriptions. Rolling
back requires restoring the pre-migration database together with the old binaries.

## Interoperability fixture and validation

The `notification-fixture` module uses official connector **3.3.5**, without
LelloStore-specific IPC or signing pins. Its Java implementation avoids upgrading
the main app's Kotlin compiler just to consume the connector's newer metadata.

1. Build Store and the fixture: `cd android && ./gradlew :app:assembleDebug :notification-fixture:assembleDebug`.
2. Sign into Store, enable shared push and grant unrestricted battery use.
3. Install sender tooling: `npm ci --prefix scripts/unifiedpush` from the repo root.
4. Generate test sender keys: `node scripts/unifiedpush/interop.mjs keys /tmp/push-test-keys.json`.
5. Approve the printed public key in the admin panel; enter it in the fixture,
   select LelloStore, and register. Refresh the fixture result to obtain subscription
   JSON, then save that JSON as `/tmp/push-test-subscription.json`.
6. Send: `node scripts/unifiedpush/interop.mjs send /tmp/push-test-keys.json /tmp/push-test-subscription.json 'Hello UnifiedPush'`.
7. Confirm decrypted text in the fixture, its notification, and an acknowledged
   outcome in the admin panel. Revoke the key and confirm further sends fail.

Committed test vectors are synthetic, generated by Node `web-push` 3.6.7.
Backend tests verify their standard headers/VAPID and exact ciphertext. The fixture
JVM test decrypts them using the official connector's decryptor and rejects tampering.
To regenerate both JSON and properties vectors, use `node scripts/unifiedpush/interop.mjs vector`
and update the corresponding test-only properties fields.

Automated checks: backend fmt/clippy/tests, frontend lint/typecheck/tests/build,
Android unit tests/lint, and fixture interoperability tests. Physical qualification
must additionally cover API 24/33/34/36, screen-off/Doze, Wi-Fi/cellular transitions,
process death, reboot, force-stop recovery, token refresh failure, logout, and key
rotation. A JVM cryptographic test does not establish Android background delivery
or battery performance.
