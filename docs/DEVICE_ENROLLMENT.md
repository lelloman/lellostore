# Device enrollment (device code)

Status: implemented on the backend and in the web admin (phase 1 of
[the credential provider design](CREDENTIAL_PROVIDER_DESIGN.md#12-phased-rollout)).
The Android client does not use it yet (phase 2).

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
