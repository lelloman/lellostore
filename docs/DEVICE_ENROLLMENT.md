# Device enrollment (device code)

Status: implemented on the backend and in the web admin (phase 1 of
[the credential provider design](CREDENTIAL_PROVIDER_DESIGN.md#12-phased-rollout)), and in
the Android client: a device enrolls itself (phase 2) and an administrator approves from
LelloStore on the phone (phase 3). See [Android client](#android-client).

A device that has no human login, such as a TV box, gets its own
[device token](../SPEC.md#device-tokens) without anyone copying a secret to it:

1. The device starts an enrollment and shows the short **user code** (and a QR of the
   verification payload).
2. An administrator enters the code on the web admin's **Approve a device** page
   (`/admin/device-tokens/approve?code=…`) and approves it, as a new device or as a
   replacement for an existing token.
3. The device's next poll, made with its secret **device code**, returns its
   `device-token.json`. Only that one poll gets it.

The vocabulary follows RFC 8628 (OAuth 2.0 Device Authorization Grant). Only
administrators approve.

## Lifetimes and limits

| What | Value |
| --- | --- |
| Pending code lifetime (`expires_in`) | 600 s |
| Time to collect an approval | 600 s after approval; afterwards the prepared token is revoked |
| Poll interval (`interval`) | 5 s; polls less than 4 s apart get `slow_down` while pending |
| Enrollments per source address | 10 per 10 minutes, then `429 slow_down` |
| Pending enrollments overall | 100, then `429 slow_down` |
| Unknown user codes per administrator | 10 per 10 minutes, then `429` on every lookup, approve and deny |
| Denial cooldown | A denial (by default) refuses new enrollments from the same address for 24 h (`403 access_denied`) |
| Row retention | Ended enrollments are deleted a day after they end |

The source address is the TCP peer. When the peer is on a loopback or private network
(the deployment's reverse proxy) the last `X-Forwarded-For` entry is used instead.

The user code is 8 characters from `BCDFGHJKLMNPQRSTVWXZ` (no vowels, no look-alikes),
shown as `WDJB-MJHT`. Lookups accept any case, spaces and dashes. The user code alone
grants nothing: approving it enrolls whoever holds the device code, which never leaves
the device.

No secret is stored. The device code is kept as a SHA-256. Approval creates (or rekeys)
the device token with a random, unusable hash, and the redeeming poll generates the
token secret, stores its SHA-256 and returns it.

## Device endpoints (no authentication)

Bodies are at most 4 KiB. Errors have the RFC 8628 shape
`{"error": "<code>", "error_description": "<text>"}` and `Cache-Control: no-store`.
These routes exist only when authentication is configured.

### `POST /api/device-enrollments`

```json
{"name": "Mi Box (living room)", "model": "MIBOX4", "android_sdk": 28, "client_version": "1.0.0"}
```

`name` is required (1–100 characters). The others are optional: `model` up to 100
characters, `client_version` up to 64, and `android_sdk` 1–1000. Unknown fields are
ignored. `201`:

```json
{
  "device_code": "lellostore_enr_<64 hex>",
  "user_code": "WDJB-MJHT",
  "verification_uri": "https://store.example.com/admin/device-tokens/approve",
  "verification_uri_complete": "https://store.example.com/admin/device-tokens/approve?code=WDJB-MJHT",
  "verification_payload": "{\"type\":\"device-enrollment\",\"version\":1,\"server_url\":\"https://store.example.com\",\"user_code\":\"WDJB-MJHT\"}",
  "expires_in": 600,
  "interval": 5
}
```

`verification_payload` is the exact text to encode in the QR code. It holds no secret.
The origin comes from `X-Forwarded-Host` or `Host`, with the scheme from
`X-Forwarded-Proto` (default `https`).

| Status | `error` | When |
| --- | --- | --- |
| `400` | `invalid_request` | The body isn't JSON, or a field is invalid |
| `403` | `access_denied` | A recent denial blocked this address |
| `413` | — | The body is over 4 KiB |
| `429` | `slow_down` | Per-address or global quota exhausted |

### `POST /api/device-enrollments/poll`

```json
{"device_code": "lellostore_enr_…"}
```

On approval, `200` with exactly the `device-token.json` object, **once**:

```json
{"version": 1, "server_url": "https://store.example.com", "name": "Mi Box (living room)", "token": "lellostore_dev_<64 hex>"}
```

Otherwise `400` with:

| `error` | Meaning | Client action |
| --- | --- | --- |
| `authorization_pending` | Not approved yet | Poll again after `interval` |
| `slow_down` | Polled too soon | Add 5 s to the interval |
| `expired_token` | The code expired, or the approval wasn't collected in time, or its token was revoked before collection | Start a new enrollment |
| `access_denied` | An administrator denied it | Stop. Show the denial |
| `invalid_grant` | Unknown device code, or already redeemed | Stop |
| `invalid_request` | Malformed body | Fix the request |

An approval is delivered even to a poll that comes sooner than the interval.

## Administrator endpoints

All of these require an administrator OIDC session. Users, device tokens and publish
tokens get `403`. `{user_code}` accepts the displayed form, any case.

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| `GET` | `/api/admin/device-enrollments/{user_code}` | — | The pending request (below), or `404` when unknown, expired or no longer pending |
| `POST` | `/api/admin/device-enrollments/{user_code}/approve` | `{name?, group_ids?, replace_device_token_id?}` | `200 {"device_token": <device token>, "rekeyed": bool}` (no secret) |
| `POST` | `/api/admin/device-enrollments/{user_code}/deny[?block_source=false]` | — | `204` |

Pending request:

```json
{
  "user_code": "WDJB-MJHT",
  "requested_name": "Mi Box (living room)",
  "model": "MIBOX4",
  "android_sdk": 28,
  "client_version": "1.0.0",
  "source_ip": "198.51.100.7",
  "created_at": "2026-10-21 10:00:00",
  "expires_at": "2026-10-21 10:10:00",
  "age_s": 75,
  "expires_in": 525
}
```

Approve:

- **New device** (no `replace_device_token_id`). `name` defaults to the requested name.
  When `group_ids` is absent the token joins the system `all` group. `[]` means no
  groups. An unknown group is `404` and approves nothing.
- **Replace a device** (`replace_device_token_id`): the token is **rekeyed**. It gets a
  new secret and `revoked_at` is cleared, while its id, subject, grants and groups stay.
  The previous secret stops working at approval. `name`, when given, renames it.
  `group_ids` can't be combined with it (`400`). An expired token can't be rekeyed
  (`400`), and an unknown token is `404`.

Errors use the usual `{"error", "message"}` body: `404` for an unknown or malformed code
(counted against the administrator's lookup quota), `409` when the enrollment exists but
is no longer pending (already approved, denied or expired), and `429` when the lookup
quota is exhausted.

Deny refuses new enrollments from the request's address for 24 hours unless
`block_source=false` is passed. The web admin has a checkbox for this, on by default.

## Rekeying without enrollment

`POST /api/admin/device-tokens/{id}/rekey` gives a token a new secret, returned once
like on creation (`200`, the token plus `secret`), with the same rules: id, subject,
grants and groups stay, `revoked_at` is cleared, and the old secret stops working.

## Audit

`access_audit_log` actions: `device_enrollment.create` (actor `device-enrollment`,
details with the name, model, SDK, version and source address),
`device_enrollment.approve` and `device_enrollment.deny` (the administrator),
`device_enrollment.redeem`, `device_enrollment.expire` (actor `system`),
`device_enrollment.lookup_failed`, plus `device_token.create`, `device_token.rekey` and
`device_token.revoke` (reason `enrollment_not_redeemed` when an uncollected approval
expires). No device code or token secret is ever logged or audited.

## Capability

`GET /api/server-config` reports `capabilities.device_enrollment: true` when the
endpoints are available, which is whenever authentication is configured.

## Android client

### Setting up a device (TV or phone)

The same APK does this on phones and on Android TV boxes, and every step works with the
D-pad alone. LelloStore is also listed in the Android TV launcher.

1. **Login screen.** When the confirmed store's `/api/server-config` has
   `capabilities.device_enrollment`, the screen offers **Set up this device without
   signing in**. On a TV (UI mode `television`) it is the main action, the preset store
   is checked as soon as the screen opens, and focus starts on the main button; on a
   phone it sits under the sign-in button. Choosing it selects the store (like signing
   in would) and opens the enrollment screen.
2. **Name.** The name the administrator will see, prefilled with the system device name
   (`Settings.Global.DEVICE_NAME`, else the model); 1–64 characters without control or
   bidirectional-override characters. **Get a code** has the initial focus.
3. **Code.** `POST /api/device-enrollments` with the name, `model` (`Build.MODEL`),
   `android_sdk` and `client_version` (the app's version name). The screen shows the
   user code in large type, a QR code of `verification_uri_complete` (any phone camera
   opens the web admin's approval page; LelloStore on an administrator's phone scans it
   too), the store's `verification_uri` to type by hand, the name, and a countdown.
4. **Polling.** `POST /api/device-enrollments/poll` every `interval` seconds (the
   server's value), 5 s more after each `slow_down`. Network errors and unexpected
   answers are retried after 2, 5, 10, 20, 40 and then 60 s, with "Cannot reach …"
   on screen. Polling runs only while the screen is visible (started); the code and its
   expiry are kept meanwhile, and on return the next poll waits out the rest of the
   interval. `expired_token` (or the countdown reaching zero) gets a new code by itself;
   after the third expired code it stops with **Try again**. `access_denied` stops with
   "Request denied"; `invalid_grant` stops with "The code is no longer valid". At the
   start, `403` is "requests from this network are blocked", `429` is "too many
   requests", and a missing route is "not supported by this store".
5. **Approved.** The returned `device-token.json` is stored (below) for the store the
   device enrolled with, even if the file names another origin, and LelloStore is signed
   in as the device at once, exactly as with a provisioned token. Storing it is not
   cancelled when the screen goes away, because the credential is delivered only once.

Enrollment requests use their own HTTP client: no credentials, no session-expiry
handling, no request logging and no redirects. The local log records only outcomes.

**What enrollment turns on.** So that a new box needs no trip into Settings, finishing
enrollment turns on **automatic updates** (the global default), periodic update checks
(every 24 hours if they were off; a chosen interval is kept) and the **update
connection** (Settings → Keep update connection open). The completion screen lists
them and then:

- asks once for unrestricted battery use, which keeps the connection open in Doze,
  when Android offers that prompt (many TV builds don't; a box on mains power does not
  enter Doze), with an **Allow** button while it is missing;
- shows the notification permission (Android 13+), which LelloStore asks for when it
  signs in, with **Allow** while it is missing.

A device token cannot use the push broker (it accepts only OIDC sessions), so with a
device token the update connection service keeps the catalog WebSocket (`/api/events`)
open instead; catalog changes then trigger an update check right away. UnifiedPush
distribution to other apps still needs an OIDC session.

**Signed in as this device.** Settings → Account shows "Signed in as this device" with
the name, the store (fixed), and **Forget this device**, which deletes the stored
credential (and a legacy file, below) and returns to the login screen. The device token
stays valid on the server; revoke it in the web admin if the device is gone.

### Device credential store

`DeviceCredentialStore` (`android/localdata/.../auth/`) keeps the `device-token.json`
content in `noBackupFilesDir/device-credential.bin`, sealed with AES-256-GCM under a
non-exportable Android Keystore key (`lellostore.device_credential.v1`, randomized
encryption, 12-byte nonce stored in front, a format header bound in as associated
data). It is written atomically and never leaves the device in a backup. A file that
cannot be opened (a lost key, tampering) is ignored. Root can still use the key on the
device; see the [threat model](CREDENTIAL_PROVIDER_DESIGN.md#4-threat-model).

`DeviceTokenAuthStore` reads the store once per process, before the auth state leaves
`Loading`. A `401` marks the credential rejected in memory, as before, and the login
screen shows a notice; the next start retries it, and enrolling again replaces it.

**Legacy file import.** A root-provisioned `noBackupFilesDir/device-token.json` is
imported into the store on start. Until an import succeeds the file itself is used, so
nothing breaks when the Keystore is unavailable. The SHA-256 of the imported content is
recorded (`device-credential-legacy-import`, no secret), so the same file is not
imported again over a later enrollment or after **Forget this device**; a different
file (root provisioned the device again) replaces the stored credential, and an
unreadable stored credential falls back to the file. The file itself is left in place
for one release so that a downgrade keeps working; **the release after next deletes it
after a successful import and stops reading it** (credential provider phase 8).

### Approving a device (administrators)

When LelloStore is signed in with an administrator OIDC session (`GET /api/me`
`is_admin`), Settings → Account has **Approve a device**. Other accounts and device
tokens don't see it, and the screen refuses them.

1. **Code.** Type the code (any case, spaces and dashes) or **Scan QR code**. The
   scanner accepts the `verification_uri_complete` link, the JSON
   `verification_payload` and a bare code; a code of another store is refused with its
   host.
2. **Review.** `GET /api/admin/device-enrollments/{user_code}` shows the code (to
   compare with the TV), the requested name, model, Android API level, LelloStore
   version, source address, how long ago it was requested and when it expires, with a
   warning against approving codes sent by someone else. Lookup errors are explained:
   unknown (`404`), no longer pending (`409`), too many unknown codes (`429`).
3. **Approve** as a **New device** (name, default the requested one; groups, with the
   server's `all` group preselected: untouched groups are not sent, so the server's
   default applies) or **Replace a device**: pick an existing device token (the one with
   the requested name is preselected, revoked ones are listed too), which is rekeyed and
   keeps its subject, groups and grants; a changed name renames it. Or **Deny**, with
   "Also block new requests from this address for 24 hours" **off** by default because
   a phone and a box usually share the home network's address.

The activity (`ApproveDeviceActivity`) has the same hardening as the credential broker:
touches are dropped while another window obscures it, other apps' overlays are hidden
(Android 12+), and it has no task affinity and no recents entry.

#### `APPROVE_DEVICE` for other apps

Another app (PezzotTV on the phone) can start the same flow **for result**:

- Action `com.lelloman.store.action.APPROVE_DEVICE`, category `DEFAULT`, package
  `com.lelloman.store` (debug: `com.lelloman.store.debug`).
- Extras in (both optional): `com.lelloman.store.extra.USER_CODE` (the code the device
  shows; when given, it is looked up at once) and `com.lelloman.store.extra.DEVICE_NAME`
  (the suggested name, 1–100 characters).
- `RESULT_OK` with `com.lelloman.store.extra.DEVICE_TOKEN_ID` (String) and
  `com.lelloman.store.extra.REKEYED` (Boolean).
- `RESULT_CANCELED` with `com.lelloman.store.extra.ERROR`: `cancelled`,
  `not_logged_in`, `not_authorized` (not an administrator), `invalid_request` (bad
  extras, or the server refused the name, groups or token), `caller_unknown` (not
  started for result), `network`, `denied` (the administrator denied it) or
  `unknown_code` (no pending request with that code).

The caller is identified with `getCallingActivity()` and shown above the request.
Nothing secret goes in or out: the device receives its credential from the store.

#### Links from outside

LelloStore does **not** register an intent filter for the approval links
(`https://<store>/admin/device-tokens/approve?code=…`):

- Android 12+ opens an https link in an app only when it is a verified App Link, which
  needs `/.well-known/assetlinks.json` on the store host with LelloStore's signing
  certificate. The server does not serve one, and stores are chosen at run time while a
  manifest can only name hosts fixed at build time (the default store, the broker's
  `auth.lelloman.com` App Links of [SIGN_IN_BROKER.md](SIGN_IN_BROKER.md)). An
  unverified filter would only add an "Open with" choice on some phones.
- A verified link would send the web admin's own approval page to LelloStore on every
  phone that has it installed, including non-administrators and debug builds, and
  "open in the browser" would come straight back to the app.

So a phone camera opens the web admin page, and LelloStore's own scanner reads the same
QR code.

