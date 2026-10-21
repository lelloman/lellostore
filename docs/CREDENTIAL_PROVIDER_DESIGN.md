# LelloStore as the device's credential provider (design)

Status: proposal. Phases 1–3 are implemented: backend enrollment and web admin approval,
enrollment in the Android client, and approval from LelloStore on the phone; see
[DEVICE_ENROLLMENT.md](DEVICE_ENROLLMENT.md) and §12. Builds on the credential broker
([CREDENTIAL_BROKER.md](CREDENTIAL_BROKER.md)) and device tokens
([SPEC.md § Device tokens](../SPEC.md#device-tokens)).

## 1. Summary

LelloStore becomes for its own apps what `AccountManager` is for Android accounts: **apps never
store credentials**. An app asks LelloStore for a credential *type*. The first time, LelloStore
shows a one-time approval and records a **grant** keyed by the caller's package, its signing
certificate digest and the type. After that, every request is answered without UI. LelloStore
checks the calling UID, its package and its certificate against the grant, then returns a
short-lived token. The app uses the token and forgets it. LelloStore handles expiry, refresh and
revocation, and a **Connected apps** screen lists grants and revokes them.

A device that has no user login (the TV box) gets its own LelloStore identity through a
store-native **device-code enrollment**. The box shows a code and a QR, an administrator approves
it in LelloStore on the phone (or in the web admin), and the server hands the box its device
credential directly. Nothing is pushed into LelloStore's data directory with root any more.

```
                     ┌──────────── LelloStore server ─────────────┐
                     │ device_enrollments   credential types/policy│
                     │ device_tokens        JWT signer + JWKS      │
                     └──────▲──────────────────────▲───────────────┘
        approve(user_code)  │                      │ mint(type, caller pkg+cert)
        (admin OIDC)        │                      │ (device token or OIDC session)
 ┌──────────────────────────┴──┐            ┌──────┴────────────────────────────┐
 │ Phone: LelloStore (admin)   │            │ Box: LelloStore (device principal)│
 │  scan QR / APPROVE_DEVICE   │            │  enrollment poll → device token   │
 └──────────▲──────────────────┘            │  grants · token cache · provider  │
            │ user_code (not secret)        └──────▲─────────────────────────────┘
 ┌──────────┴──────────────────┐   companion       │ ContentProvider.call (Binder:
 │ Phone: PezzotTV             │◄─────────────────►│ UID → package → certificate)
 └─────────────────────────────┘   LAN/BLE   ┌─────┴─────────────────┐
                                             │ Box: PezzotTV, others │
                                             └───────────────────────┘
```

## 2. Why: what is fragile today

- **Box provisioning writes into another app's private storage with root.**
  `pezzottv/scripts/tvbox/setup-tv-box.sh` (step 4, `STORE_IDENTITY_DIR=/data/user/0/$STORE_PACKAGE/no_backup`)
  and the box's `ProvisionDeviceToken` handler (`pezzottv/docs/COMPANION.md`, "Request handling")
  write `device-token.json` into `/data/user/0/com.lelloman.store/no_backup/` and then
  `am force-stop` LelloStore. LelloStore reads that file once per process
  (`android/localdata/.../auth/DeviceTokenAuthStore.kt`, preferred by `CompositeAuthStore.kt`).
  This depends on root seeing the app's real data directory. Android 11 app-data isolation and
  Magisk's per-app mount namespaces break that assumption: the write can land in a view that
  LelloStore never sees, or fail outright. It also needs the file to be owned by the app's UID
  with mode 600, and SELinux contexts that root shells don't always get right.
- **The secret travels.** The device token is minted on the phone (broker
  `lellostore.device_token`, `android/app/.../credential/DeviceTokenCredentialIssuer.kt`), handed
  to PezzotTV phone, sent over the companion link, and written by root on the box. Every hop is
  somewhere it can leak. With the manual fallback it can also be pasted or scanned.
- **`storeProvisioned` is a guess.** The box answers `Info.storeProvisioned` with
  `test -s …/device-token.json`, so a token the server later rejects is not detected (noted in
  COMPANION.md, "Boxes").
- **Every app that needs an identity implements its own login.** pezzottify has its own OIDC login
  (`pezzottify/android/README.md`, "Sign-in configuration"), and TV builds can't use its broker.
  PezzotTV keeps service accounts in its own Keystore-sealed store (`pezzottv/app/.../di/AccountsModule.kt`).

## 3. Goals and non-goals

Goals

1. Apps on a device get credentials from LelloStore, after a one-time approval per (app, type), with
   no UI afterwards and no long-lived secret held by the app.
2. Grants are bound to the caller's signing identity, not just its package name, and can be
   listed and revoked on the device. Server-side policy can refuse any app.
3. A device without a human login enrolls itself by device code. Root, ADB file pushes and
   secrets in transit go away.
4. The LelloStore server is the issuer of record. Token lifetimes, revocation and audit live there.
5. Small, separately shippable steps, keeping existing boxes working throughout.

Non-goals

- A general password vault or sync of third-party secrets (Mediaset and similar, see §10).
- Replacing the identity provider. Human users still sign in with OIDC. LelloStore mints tokens for
  its *own* principals (users and devices) and never for users it has not authenticated.
- Protecting against root on the device (see §4). The box is rooted by design.
- Hiding which apps use LelloStore from other apps on the device.
- iOS, browsers, or callers outside the device.

## 4. Threat model

Assets: the device credential (`lellostore_dev_…` today), the OIDC refresh token of a phone's
user, the app tokens LelloStore mints, and the grants themselves (they decide who gets what).

| Threat | Mitigation | Residual |
| --- | --- | --- |
| **Malicious app on the device** asks for a credential | Without a grant it only gets `grant_required` plus an approval intent that it must launch itself, in the foreground. The approval names the app, its package, a short certificate fingerprint and whether LelloStore installed it. Server policy (§7.3) can also pin each type to an allowlist of (package, signer). A denied request has a cooldown, so the app can't spam prompts. | A user can be talked into approving a lookalike app if that type's server policy allows any package. That's why the default policy is to allow only packages the store distributes (§7.3). |
| **Impersonation via package name** (sideloaded `com.pezzottv` signed with another key) | Grants match the signing certificate digest as well as the package name. Server policy compares the signer with the one recorded in `publications` / `paravoid_installers` (`signer_sha256`). | None while the grant exists. On a fresh device the server allowlist is what stops the fake app. |
| **UID/package confusion** (shared UID, a package reinstalled under a recycled UID) | Identity comes from `Binder.getCallingUid()` together with `ContentProvider.getCallingPackage()`, which AppOps checks against the UID. A UID that maps to more than one package is refused (same rule as `notification/SigningIdentity.requireCaller`). Grants are deleted on `PACKAGE_FULLY_REMOVED` (`installation/PackageChangeMonitor.kt`). | — |
| **Signature rotation** | APK Signature Scheme v3 rotation: accepted when `PackageManager.hasSigningCertificate(pkg, grantCert, CERT_INPUT_SHA256)` holds, which follows the lineage. The grant then moves to the current certificate. Without a lineage the app counts as a new app: a new approval is shown, with a "signing key changed" warning. Multiple signers: the grant holds the whole sorted set and all of them must match. | A rotation the store didn't publish also fails server policy until the admin publishes the new signer. That's intended. |
| **Rooted device** | None against root itself: root can read LelloStore's data, use its Keystore keys and impersonate any UID. Storing the device credential encrypted with Keystore (later, bound to a Keystore key, §7.5) stops it being copied *off* the device, though root can still use it on the device. | The guarantee on the box is "non-root apps only". Magisk `su` grants are the gate for root. |
| **Lost or stolen device** | An admin revokes the device in **Device tokens** (exists: `DELETE /api/admin/device-tokens/{id}`). Minting stops at once, and tokens already handed out die within their TTL (≤ 15 min). On a phone, signing out of LelloStore or revoking the session at the identity provider has the same effect. A later phase reports grants to the server so one app can be revoked remotely. | Tokens already handed out stay valid until they expire. |
| **Token leakage** (logs, crash reports, a careless app) | App tokens are short-lived, scoped by audience (a `pezzottify.identity` token is useless against LelloStore's API) and carry `azp` = caller package. Neither LelloStore nor the SDK logs or persists them. Redacted `toString()` follows the `IssueResult.Success` precedent. The device credential never leaves LelloStore. | A leaked token can be used by whoever holds it until it expires. |
| **Replay** | The IPC is Binder, so there's nothing to capture and replay. Tokens carry `exp`, `iat` and `jti`. Enrollment `device_code`s are single-use, hashed at rest and expire after 10 min. Approval is single-winner, and polling has a minimum interval (`slow_down`). Approval intents are `FLAG_IMMUTABLE | FLAG_ONE_SHOT` and refer to a pending request that expires. | Bearer tokens can be replayed within their TTL. Proof of possession (DPoP) is an open question. |
| **Device-code phishing** (an attacker relays their own device's code to the admin) | The approval screen shows the device's name, model, Android version, request time and the source IP the server saw, and asks the admin to compare the code with the one on the TV. The PezzotTV path carries the code over the paired, authenticated companion session (§9), so it can't be swapped in transit. | An admin who approves blindly. |
| **Tapjacking / overlays** on the approval screen | Same hardening as `IssueCredentialActivity`: `filterTouchesWhenObscured`, `setHideOverlayWindows(true)`, `excludeFromRecents`, empty `taskAffinity`. | — |

**Why signing-certificate grants are sound.** Android binds a UID to one installed package, the
package to the certificate(s) that signed it, and refuses an update signed with a different key
unless a v3 lineage proves continuity. So (package, certificate) names "the code the developer
signed", and Binder gives LelloStore an unforgeable UID for every call. Android's own
signature-level permissions, Play's app signing and `AccountManager` authenticators rest on the
same anchor.

**Its limits.**

- A grant says *who signed the code*, not *what the code does*. A compromised developer key, or a
  developer who ships something malicious, is trusted.
- Paravoid shells: the shell's package and certificate are the identity. Any payload the shell
  loads runs as the same UID and inherits the grant. Payload integrity is Paravoid's signing
  chain ([PARAVOID_SIGNING.md](PARAVOID_SIGNING.md)), not the grant's.
- Debug and release builds have different certificates, so they need different grants.
- Root, a modified system image or an unlocked bootloader void all of the above.
- Before Android 9 (`minSdk` is 24) there's no `GET_SIGNING_CERTIFICATES` and no lineage, so
  `GET_SIGNATURES` is used, and rotation always means a new approval.

## 5. Concepts

- **Principal**: whoever LelloStore itself is signed in as. Either an OIDC user (`AuthStoreImpl`) or a
  device (`device-token:<id>`, `DeviceTokenAuthStore`). App tokens are always minted for this
  principal. An app can't pick another one.
- **Credential type**: a stable, owner-namespaced id (`pezzottify.identity`). The *server* defines
  each type (audience, lifetime, policy) and advertises it. The Android client knows no
  type-specific code except built-in LelloStore types. This differs from the broker, where each
  type is a `CredentialIssuer` compiled into the app.
- **Grant**: (package, signer set, type, principal binding) → `granted | denied(until)`. Stored only
  on the device.
- **App token**: a JWT minted by the LelloStore server: `iss` = store origin,
  `aud` = the type's audience, `sub` = principal subject, `azp` = caller package,
  `lellostore_signer` = caller certificate digest, `type`, `iat`, `exp`, `jti`. Relying parties
  check it against the store's JWKS.

## 6. Android app

### 6.1 IPC mechanism: `ContentProvider.call` (recommended)

| | `ContentProvider.call` | Bound service + AIDL |
| --- | --- | --- |
| Caller identity | `Binder.getCallingUid()` plus `getCallingPackage()`, which AppOps verifies (and an `AttributionSource` on 31+) | `getCallingUid()` only, so the package has to be derived |
| Caller-side code | `contentResolver.call(authority, method, arg, Bundle)`. No generated stubs, so the SDK has no AIDL artifact | Needs the AIDL interface in the caller, and new methods mean regenerating it |
| Lifecycle | None. The system starts LelloStore's process on demand. Works from WorkManager, receivers and services | `bindService` / `ServiceConnection`, rebinding after death, unbinding. Easy to get wrong from short-lived contexts |
| Versioning | Method strings (`v1.getToken`) and Bundle keys. Unknown keys are ignored and unknown methods give `unsupported_method` | Interface versioning, `protocolVersion()` as in `IRecoveryService.aidl` |
| Long operations | The call blocks a Binder thread while LelloStore refreshes (bounded by a 10 s timeout). The caller must not call it on the main thread, and the SDK is `suspend` on `Dispatchers.IO` | `oneway` callbacks avoid blocking |
| Package visibility (API 30+) | The caller must see LelloStore. That's automatic when LelloStore installed it, otherwise `<queries><package …/></queries>` | Same |

Recommendation: **`ContentProvider.call`**. The API is request/response, a call typically
returns a cached token in microseconds, and the AppOps-verified calling package plus no binding
lifecycle remove the two most common bugs in this kind of IPC. LelloStore's existing AIDL
(`recovery-protocol`, `paravoid-update-ipc`) is meant for long-lived peers that LelloStore itself
binds. A provider is a better fit for many unrelated callers. The blocking is bounded: misses are
coalesced per (principal, type) so that only one request is in flight, and at most 4 concurrent
refreshes run.

Manifest (`android/app/src/main/AndroidManifest.xml`):

```xml
<!-- Credential provider: identity is checked on every call(); there is no query/insert surface. -->
<provider
    android:name=".credential.provider.CredentialProvider"
    android:authorities="${applicationId}.credentials"
    android:exported="true"
    tools:ignore="ExportedContentProvider" />
```

`call()` is not gated by `readPermission`/`writePermission`, and `query/insert/update/delete`
throw. Authority: `com.lelloman.store.credentials` (debug: `com.lelloman.store.debug.credentials`).

### 6.2 Contract (v1)

These strings are public API. Like `CredentialContract`, they never get renamed. Every response
Bundle has `status` (`ok | grant_required | error`), and errors also have `error` and, optionally,
`retry_after_ms`.

| Method | Request extras | `ok` response | Grant needed |
| --- | --- | --- | --- |
| `v1.describe` | — | `api_version` (int, 1), `state` (`signed_out \| user \| device \| enrolling`), `server_host`, `types` (String[] of type ids the server advertises) | No |
| `v1.getToken` | `type` (String, required), `min_validity_s` (long, default 60, max 600), `params` (Bundle of String, optional, type-defined) | `token` (String), `token_type` (`Bearer`), `expires_at_ms` (long, epoch), `type`, `audience` | Yes |
| `v1.invalidateToken` | `type`, `token` | — (a cached token equal to `token` is dropped, and the next `getToken` mints a fresh one) | Yes |
| `v1.forgetGrant` | `type` | — (the caller withdraws its own grant, for example on sign-out) | No |
| `v1.beginEnrollment` | `device_name` (String, 1–64, same rules as `SUBJECT_NAME`) | `user_code`, `verification_payload` (the QR JSON), `expires_at_ms` | No. Only allowed while `state = signed_out` (see §6.6) |

`grant_required` response: `intent` (a `PendingIntent` for LelloStore's approval activity,
`FLAG_IMMUTABLE | FLAG_ONE_SHOT`) and `request_id`. The caller launches it with
`StartIntentSenderForResult`. `RESULT_OK` means granted, and the caller calls `v1.getToken`
again.

Errors (new names, but in the same spirit as `CredentialError`, which they reuse where they
overlap):

| `error` | When |
| --- | --- |
| `not_signed_in` | LelloStore has no principal (signed out, never enrolled, or its device token was rejected) |
| `not_authorized` | The server refused: this principal or this (package, signer) may not have this type (`403`) |
| `unsupported_type` | The server doesn't advertise the type |
| `invalid_request` | Missing type, bad `params`, `min_validity_s` out of range |
| `caller_unverifiable` | The UID maps to several packages, or there's no certificate, or `getCallingPackage()` doesn't match the UID |
| `grant_denied` | The user declined recently. `retry_after_ms` says when a new approval may be shown (default 24 h, and *Allow* in Connected apps clears it) |
| `network` | Server unreachable, or the 10 s timeout passed |
| `rate_limited` | Too many calls from this caller (local token bucket), with `retry_after_ms` |
| `unsupported_method` | An unknown method, for example a v2 call to an older LelloStore |

### 6.3 Caller verification (one place)

`credential/provider/CallerIdentity.kt` merges the two existing helpers.
`notification/SigningIdentity.kt` uses `apkContentsSigners`, while
`recovery/.../SigningCertificates.kt` uses `signingCertificateHistory`, and they differ on rotation.

```
uid  = Binder.getCallingUid();  pkg = callingPackage  (AppOps-verified)
require getPackagesForUid(uid) == [pkg]                      else caller_unverifiable
signers = sorted SHA-256 of apkContentsSigners (multi-signer) | current cert (single signer)
grant   = grants[(pkg, type, principalBinding)]
match   = grant.signers == signers
          || (single signer && hasSigningCertificate(pkg, grant.signer, CERT_INPUT_SHA256))  // v3 lineage
```

A lineage match moves the grant to the new certificate and is written to the audit log.

### 6.4 Grant store, token cache, issuance

- `:domain` `credential/grant/`: `CredentialGrant`, `GrantStore`, `ProvidedToken` (redacted
  `toString`).
- `:localdata`: Room table `credential_grants(id, package_name, signers, type, server_origin,
  principal_subject, state, denied_until, created_at, last_used_at)`, unique on
  `(package_name, type, server_origin, principal_subject)`. It's kept out of backups
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`), so grants never move to another
  device. Pending approval requests (`request_id`, uid, package, signers, type, created_at, 10-min
  TTL) go in a second table so that an approval survives process death.
- Principal binding: grants are tied to the server origin and principal subject they were
  approved under. Signing out, a server switch (`ServerSelection`) or a re-enrollment as another
  principal deletes them. The approval was "PezzotTV may get X *from this identity*".
- Token cache: in memory only, keyed by (principal, type, package). A request is served from the
  cache while `exp - now ≥ min_validity_s`. Otherwise one mint is in flight per key. Nothing is
  written to disk.
- `CredentialMinter` (`:remoteapi`) calls `POST /api/credentials/token` with LelloStore's current
  bearer (`CompositeAuthStore.getAccessToken()`, so both OIDC and the device token work). A `401`
  goes through the existing session-expiry path and answers `not_signed_in`.
- Audit (`logger/AuditLog`): records `credential.grant`, `credential.deny`, `credential.revoke`,
  `credential.mint` (caller package, type, outcome, no token) and `credential.rotation`. The same
  exclusions as today apply.

### 6.5 Approval and Connected apps UI

- **Approval** (`GrantCredentialActivity`, not exported, reachable only through the PendingIntent).
  It loads the pending request by `request_id`, re-checks that the package is still installed with
  the same signers, and renders the existing `IssueCredentialScreen` (`android/ui/.../screen/credential/`),
  with its `CallerHeader`, `ConfirmContent` and `NeedsLoginContent`, through a new state builder.
  Text comes from the server's type description: "Allow **PezzotTV** to get your **pezzottify
  identity**? It can then sign in to pezzottify as *Mi Box (living room)* whenever it needs to,
  without asking again. You can revoke this in LelloStore → Connected apps." Below the package
  name it shows the first 8 bytes of the certificate SHA-256 and "Installed by LelloStore" or
  "Not installed by LelloStore" (`getInstallSourceInfo`). Buttons: **Allow**, **Don't allow**.
  On TV the confirm button gets initial focus only after a 1 s delay, so a held D-pad center can't
  approve by accident. The same hardening as `IssueCredentialActivity` applies.
- **Connected apps** (Settings → Connected apps, a new `:ui` screen): one row per grant with icon,
  label, package, type label, granted date and last used. **Revoke** deletes the grant and drops
  cached tokens. Denied entries show **Allow**. A footer notes that revoking stops new tokens and
  that tokens already issued stay valid for up to their lifetime (shown).

### 6.6 Device-code enrollment UI (TV)

- On a device that isn't signed in, the login screen (`ui/screen/login/LoginScreen.kt`) gets
  **Set up this device without signing in**. On a TV (`FEATURE_LEANBACK`) it is the primary
  action, because browser login there is impractical. The device name is prefilled from
  `Settings.Global.DEVICE_NAME`.
- The screen shows the `user_code` in large type (`WDJB-MJHT`, 8 characters from an unambiguous
  alphabet), a QR of the `verification_payload`, the store host, a countdown, and "Waiting for
  approval…". It polls at the server's `interval`, shows a new code by itself when the old one
  expires, and stops after three expiries without approval.
- The QR payload extends the existing setup QR (`docs/PRODUCT_SPEC.md`, `type: "store-setup"`) with
  a new type and no secret:
  `{"type":"device-enrollment","version":1,"server_url":"https://store.example.com","user_code":"WDJB-MJHT"}`.
- On approval the poll returns exactly the `device-token.json` shape (`DeviceTokenFile.serialize`),
  and LelloStore stores it in a new `DeviceCredentialStore`. That store lives in
  `noBackupFilesDir` and is encrypted with an Android Keystore key, like `AuthStoreImpl`'s
  encrypted preferences. `DeviceTokenAuthStore` reads from it first and from the legacy file
  second (§8). State changes to `device` and the catalog loads, with no process restart.
- `v1.beginEnrollment` lets another app on the device start the same flow and get the code, which
  is how PezzotTV relays it (§9). It is accepted only while LelloStore is `signed_out`, rate-limited
  to 1 per minute per caller, and leads to the same on-screen state if LelloStore is open. The
  `user_code` alone grants nothing. It is useful only to an admin who approves it, and approval
  enrolls whoever holds the `device_code`, which never leaves LelloStore.

### 6.7 Approving a device (phone, admin)

- **Approve a device** in the admin area: scan the enrollment QR with the existing setup-QR
  scanner, or type the code. It shows the details from `GET /api/admin/device-enrollments/{code}`,
  then offers either **New device** (name, groups) or **Replace a device** (pick an existing device
  token, which is rekeyed and keeps its subject, grants and groups). Then **Approve**.
- Exported action `com.lelloman.store.action.APPROVE_DEVICE` (extras `USER_CODE`, optional
  `DEVICE_NAME`), started for result. It goes through the same confirmation-gated activity pattern
  as `ISSUE_CREDENTIAL` and returns `RESULT_OK` or the `CredentialError` codes. PezzotTV phone uses
  it. Nothing secret is returned.

## 7. Backend

### 7.1 What exists and what is missing

- **The publisher's device flow is the identity provider's, not the store's.**
  `scripts/publish-to-lellostore.py` (`device_flow_auth`, around line 258) reads
  `device_authorization_endpoint` from the IdP's discovery document and polls the IdP's token
  endpoint with `urn:ietf:params:oauth:grant-type:device_code`. The backend only *advertises* the
  public client id (`OIDC_PUBLISHER_CLIENT_ID` → `backend/src/config.rs`,
  `api/server_config.rs` `clients.publisher`). It implements no device-code endpoint.
- Using the IdP device flow for the box was considered and rejected. It would give the box a
  *human* OIDC session: the approving admin's, admin role included, or a dedicated IdP user per box.
  It also makes provider-specific features load-bearing (LelloAuth's QR approval,
  `lello-auth/docs/DEVICE_APPROVAL_AUTHORIZATION.md`, needs a hosted LelloAuth session to mint its
  approval token), which conflicts with roadmap step 4, "Generalize authentication"
  (`docs/PRODUCT_ROADMAP.md`). Device tokens are already the right principal: non-admin and
  grant-scoped (`auth/middleware.rs` `authenticate_device_token`, `User::device`).
- Reusable as is: `db/device_tokens::create` (name, groups, same transaction),
  `db/token_secrets` (secret generation, SHA-256 at rest), the `AdminUser` extractor,
  `access_audit_log`, the RFC 8628 response and error vocabulary (the publisher already parses it),
  unauthenticated routes with their own quotas (Paravoid and notifications precedent in
  `api/routes.rs`), and `publications.signer_sha256` for policy.
- Missing: the enrollment table and endpoints, rekeying a device token, credential type
  configuration, a token-minting endpoint, a JWT signing key with JWKS, and issuance audit.

### 7.2 Device enrollment (store-native device-code)

Migration `device_enrollments(id, device_code_hash UNIQUE, user_code UNIQUE, requested_name, model,
android_sdk, client_version, source_ip, created_at, expires_at, last_polled_at, state
pending|approved|denied|redeemed|expired, approved_by, device_token_id, secret_sealed)`.
`secret_sealed` holds the new secret between approval and the box's next poll only. It is
encrypted with a server key, cleared on redemption, and the row expires 10 min after approval.

| Method | Path | Auth | Body → Result |
| --- | --- | --- | --- |
| `POST` | `/api/device-enrollments` | None (per-IP quota, 4 KiB body) | `{name, model?, android_sdk?, client_version?}` → `201 {device_code, user_code, verification_payload, expires_in: 600, interval: 5}` |
| `POST` | `/api/device-enrollments/poll` | None (`device_code` is the credential) | `{device_code}` → `400 {error: authorization_pending \| slow_down \| expired_token \| access_denied}` or `200` with the `device-token.json` object, **once** |
| `GET` | `/api/admin/device-enrollments/{user_code}` | Admin | Pending request details (name, model, SDK, IP, age) or `404` |
| `POST` | `/api/admin/device-enrollments/{user_code}/approve` | Admin | `{name, group_ids?, replace_device_token_id?}` → `200 {device_token}` (no secret) |
| `POST` | `/api/admin/device-enrollments/{user_code}/deny` | Admin | `204` |

- Approval with `replace_device_token_id` **rekeys** that token: it gets a new hash and
  `revoked_at` is cleared, while the id, subject, grants and groups stay the same. This fixes
  "a new token is a new principal" (README, "Zero-login devices") for a box that was reset.
  `POST /api/admin/device-tokens/{id}/rekey` covers the same operation without enrollment.
- Single winner: approve and deny are a conditional `UPDATE … WHERE state='pending'`. A
  `device_code` is accepted while pending or approved-but-not-redeemed, and polling faster than
  `interval` answers `slow_down`.
- Audit: enrollment created (IP, name), approved/denied (actor), redeemed, expired, and rekey.
- The web admin **Device tokens** page (`frontend/src/views/AdminDeviceTokensView.vue`) gets
  **Approve a device** with a code field. That makes phase 1 usable before any Android change.
  A publisher subcommand (`publish-to-lellostore.py approve-device <code>`, reusing its cached
  admin OIDC token) would let `setup-tv-box.sh` approve headlessly.

### 7.3 Credential types and policy

Operator configuration (a `credential_types` table edited in the admin UI, seeded from config):

```
id                pezzottify.identity
label / description  "pezzottify identity" / "Sign in to pezzottify as this device or user"
audience          pezzottify
ttl_s             600            (min 60, max 3600)
principals        users | devices | both        + optional group_ids
clients           [{package: "com.lelloman.pezzottify", signer_sha256: "…"}]   | "store_published"
claims            optional static claims (e.g. pezzottify scopes)
enabled           true
```

`clients: "store_published"` (the default) allows any package this store publishes, as long as the
caller's signer equals the signer recorded for it (`publications` / `paravoid_installers`
`signer_sha256`). An app outside the store, or re-signed, gets `not_authorized` even if the user
approved it on the device.

| Method | Path | Auth | Result |
| --- | --- | --- | --- |
| `GET` | `/api/credential-types` | User or device | Types this principal may obtain: `id`, `label`, `description`, `ttl_s` |
| `POST` | `/api/credentials/token` | User or device (not publish tokens) | `{type, caller: {package, signer_sha256}, params?}` → `{token, token_type, expires_in, audience}`. Errors: `403 client_not_allowed`, `403 principal_not_allowed`, `404 unsupported_type` |
| `GET` | `/api/credentials/jwks` | None | Public keys (Ed25519 or ES256, `kid`-rotated). Also referenced from `/api/server-config` |
| `GET/PUT/DELETE` | `/api/admin/credential-types[/{id}]` | Admin | Manage types |
| `GET` | `/api/admin/credential-issuance` | Admin | Per (principal, type, package, signer): first and last mint, count, last IP |

`/api/server-config` capabilities gain `credentials: true` and `device_enrollment: true`, so
clients can tell what the server supports.

### 7.4 Lifetimes and revocation

| Credential | Lifetime | Revocation |
| --- | --- | --- |
| Device credential (`lellostore_dev_…`) | Long-lived, no expiry by default (optional `expires_at` as today) | Admin revoke (exists) or rekey. Takes effect at once for minting |
| Phone user session | The IdP's (access token plus refresh) | Sign-out, or revocation at the IdP |
| App token | `ttl_s` per type, default 10 min. No refresh token is ever given to an app | Bounded by TTL. Minting checks principal, policy and type `enabled` every time. Local grant revoke stops the next mint |
| Grant | Until revoked, uninstall, sign-out or principal change | Connected apps, `v1.forgetGrant`, and later a server-side request (§7.5) |
| Enrollment code | 10 min, single use | Deny, expiry |

Relying parties are expected to accept only `aud` = their audience and to honour `exp`. That's
why short TTLs make server-side revocation lists unnecessary.

### 7.5 Later hardening (not needed for the first phases)

- **Grant reporting**: LelloStore reports `(package, signer, type)` grants with
  `PUT /api/me/app-grants`, so the admin sees which apps on which box hold what and can revoke one
  remotely. The server then refuses to mint for it, and the client deletes it on its next sync.
- **Proof of possession for the device credential**: at enrollment the box sends a Keystore public
  key (StrongBox or TEE when available). Device requests then carry a signed, timestamped
  challenge or a DPoP proof, so a copied `lellostore_dev_` secret is useless elsewhere.
- Optional DPoP-bound app tokens, if a relying party wants them.

## 8. Migration

- **Existing boxes with `device-token.json`** keep working. `DeviceTokenAuthStore` reads
  `DeviceCredentialStore` first and the legacy file second. When it finds a legacy file (and the
  server accepts it on the next request) it imports the token into the encrypted store, and from
  then on uses the import. The file is left in place for one release, so a downgrade still works,
  and is deleted in the release after. (As built, the import happens on start without waiting
  for the server; see §12.) A box that has no file and isn't signed in shows the
  enrollment screen.
- **`ISSUE_CREDENTIAL` broker**: keep it, and keep its contract strings. It stays for
  **one-shot, interactive minting of a new long-lived credential**, where per-request
  confirmation is the right UX. Its plumbing feeds into the provider: the screen composables, the
  caller header and the hardening are shared, and `CredentialIssuer` stays for built-in one-shot
  types. Once PezzotTV uses enrollment, `lellostore.device_token` is documented as legacy (manual
  fallback and older PezzotTV phones). It is removed only after no caller in the store sends it
  (the local log records caller and type).
- **No server data migration**. Enrolled devices are ordinary `device_tokens` rows. Rekey reuses
  them.
- Session isolation: grants follow the rules in `docs/PRODUCT_SPEC.md`. A server switch wipes
  them together with the session.

## 9. PezzotTV

- **Box setup stops touching LelloStore's data.** `setup-tv-box.sh` drops step 4 (the root write
  into `STORE_IDENTITY_DIR`). It still installs LelloStore and its ADB identity. Optionally, it
  starts enrollment
  (`adb shell content call --uri content://com.lelloman.store.credentials --method v1.beginEnrollment --extra device_name:s:<name>`),
  prints the code and, with `--approve`, approves it through the publisher's admin session.
  `--device-token-file` / `LELLOSTORE_DEVICE_TOKEN` are deprecated and removed after one release.
- **Box `Info.storeProvisioned`** becomes `v1.describe().state == device` instead of
  `test -s …/device-token.json`. It is accurate, because a rejected token turns into
  `signed_out` (`CompositeAuthStore` already falls back on 401). The flag gains a third value
  (`enrolling`), so the phone can show "waiting for approval".
- **Companion protocol**: new request `StartStoreEnrollment(name)`. The box calls
  `v1.beginEnrollment` and answers `StoreEnrollment(user_code, expires_at)`. `ProvisionDeviceToken`
  stays decodable for older phones. A box running the new payload still accepts it for one release
  (written through LelloStore's legacy import path, not root), then answers `NOT_SUPPORTED`.
- **Phone "Authorize with LelloStore" becomes the device-code approval.** Setup step 2 and the box
  menu entry send `StartStoreEnrollment` over the paired session, receive the code, and start
  `com.lelloman.store.action.APPROVE_DEVICE` with `USER_CODE` on the phone. The admin confirms in
  LelloStore, the box's poll completes, and the phone watches `Info` until it says `device`.
  No secret reaches PezzotTV. `phone/store/LelloStoreCredentials` keeps its error table, mapped to
  the new action, and "Enter the token manually" becomes "Enter the code on the TV in LelloStore
  yourself".
- **Package visibility**: the payload calls the provider through the SDK. LelloStore is
  automatically visible to apps it installed, and LelloStore's ADB channel records itself as
  installer of record (`pezzottv/docs/LELLOSTORE.md`). For a shell first installed by
  `adb install`, the next shell release adds `<queries><package android:name="com.lelloman.store"/></queries>`.
  Until then the SDK reports `StoreUnavailable` rather than failing silently.
- **Service accounts (Mediaset)** stay in PezzotTV's `EncryptedFileAccountStore`. See §10.

## 10. Credential types roadmap

| Type | Scope | Notes |
| --- | --- | --- |
| `lellostore.device_token` (broker, one-shot) | Exists | Legacy once enrollment ships |
| `pezzottify.identity` | First real provider type | pezzottify's backend adds LelloStore's JWKS as a trusted issuer for audience `pezzottify`, and maps `sub` to a pezzottify account. Users map to the same IdP subject they already have. Devices need a mapping (open question 3). Pezzottify TV can then drop its own login on boxes. |
| `pezzottv.*` for PezzotTV's own backend, if one ever exists | Same model | Only a server type configuration is needed |
| Third-party service accounts (Mediaset, …) | **Out of scope** | These are username/password logins to third parties, refreshed with each service's private protocol (`MediasetAccountProvider`). Holding them would turn LelloStore into a password vault, with server-side storage of third-party secrets, a much larger blast radius and per-service code in the store. A later, separate idea: an admin-entered **opaque secret** type, stored encrypted server-side and delivered under the same grant and policy, so that a reset box gets its Mediaset login back without retyping. Worth it only if re-entering on a new box hurts. |

## 11. Caller SDK

A new module `android/credential-client` (pure Android, no Hilt, no OkHttp in the core), published
to Fucina Maven like LelloDesign, so pezzottv and pezzottify depend on it the same way. Sketch:

```kotlin
package com.lelloman.store.credential.client

class LelloStoreCredentials(
    context: Context,
    private val storePackage: String = "com.lelloman.store", // ".debug" for debug stores
) {
    /** Store availability and state; never needs a grant. */
    suspend fun status(): StoreStatus

    /** A token valid for at least [minValidity]; served from LelloStore's cache when possible. */
    suspend fun getToken(type: String, minValidity: Duration = 60.seconds): TokenResult

    /** Report that a backend rejected [token]; the next getToken mints a new one. */
    suspend fun invalidate(type: String, token: String)

    /** Withdraw this app's grant (e.g. on the user's "sign out"). */
    suspend fun forgetGrant(type: String)

    /** TV setup: start device enrollment while LelloStore is signed out. */
    suspend fun beginEnrollment(deviceName: String): EnrollmentResult
}

sealed interface StoreStatus {
    data object StoreUnavailable : StoreStatus                  // not installed, not visible, too old
    data class Available(val state: State, val serverHost: String, val types: Set<String>) : StoreStatus
    enum class State { SIGNED_OUT, USER, DEVICE, ENROLLING }
}

sealed interface TokenResult {
    class Token(val value: String, val expiresAt: Instant, val type: String) : TokenResult {
        override fun toString() = "Token(type=$type, expiresAt=$expiresAt, value=<redacted>)"
    }
    /** Launch with [ApproveCredential] while in the foreground, then call getToken again. */
    class ApprovalRequired(val intentSender: IntentSender) : TokenResult
    data class Failure(val error: CredentialError, val retryAfter: Duration?) : TokenResult
}

enum class CredentialError { NOT_SIGNED_IN, NOT_AUTHORIZED, UNSUPPORTED_TYPE, INVALID_REQUEST,
    CALLER_UNVERIFIABLE, GRANT_DENIED, NETWORK, RATE_LIMITED, STORE_UNAVAILABLE, UNKNOWN }

/** ActivityResultContract<IntentSender, Boolean> wrapping StartIntentSenderForResult. */
class ApproveCredential : ActivityResultContract<IntentSender, Boolean>()
```

Typical use, with the optional `credential-client-okhttp` artifact:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(LelloStoreBearerInterceptor(credentials, type = "pezzottify.identity"))
    .authenticator(LelloStoreAuthenticator(credentials, "pezzottify.identity")) // 401 → invalidate, retry once
    .build()
// On TokenResult.ApprovalRequired the interceptor throws ApprovalRequiredException(intentSender);
// the UI catches it and launches ApproveCredential.
```

The SDK runs every call on `Dispatchers.IO`, maps an unknown `error` to `UNKNOWN`, maps a missing
provider (`IllegalArgumentException` / `null` from `call`) to `STORE_UNAVAILABLE`, and never logs
token values. Its tests use a fake provider, in the style of `notification-fixture`.

## 12. Phased rollout

Each phase ships and is useful on its own.

| # | Phase | Ships | Unblocks |
| --- | --- | --- | --- |
| 1 | **Backend enrollment** (done): `device_enrollments`, the five endpoints, device-token rekey, web admin "Approve a device", audit, capability flag | Server deploy | Any client can enroll |
| 2 | **TV enrollment in LelloStore** (done): login-screen entry, code/QR screen, polling, `DeviceCredentialStore` (Keystore), legacy file import | LelloStore release | New boxes need no root write. Approve on the web |
| 3 | **Phone approval** (done): QR/code entry in the admin area, `APPROVE_DEVICE` action, replace-device choice | LelloStore release | Approve from the phone |
| 4 | **Provider skeleton**: `CredentialProvider` with `v1.describe` and `v1.beginEnrollment` only, `CallerIdentity`, and the SDK artifact with `status()` / `beginEnrollment()` | LelloStore release + SDK 0.1 | PezzotTV can read real store state |
| 5 | **PezzotTV switch**: `StartStoreEnrollment`, phone flow through `APPROVE_DEVICE`, `Info` from `status()`, `setup-tv-box.sh` drops the root write, `<queries>` in the next shell | PezzotTV payload (+ shell later) | Removes the fragile path entirely |
| 6 | **Grants and tokens**: backend credential types, `/api/credentials/token`, JWKS, issuance audit; Android grant store, approval activity, Connected apps, `v1.getToken` / `invalidateToken` / `forgetGrant`; SDK 0.2 + OkHttp helper; a fixture app with a test type | Server + LelloStore + SDK | The provider exists for real |
| 7 | **pezzottify.identity**: type configuration, pezzottify backend trusts the store's JWKS, account mapping, pezzottify TV uses the SDK | Server config + pezzottify | First real consumer |
| 8 | **Cleanup and hardening**: delete legacy file reading and the `ProvisionDeviceToken` write, deprecate broker `lellostore.device_token`, grant reporting with remote revoke, Keystore-bound device credential | Several releases | — |

Phases 1–5 fix today's breakage and don't depend on 6–8.

Phase 1 as built ([DEVICE_ENROLLMENT.md](DEVICE_ENROLLMENT.md)) differs from §7.2 in a few
details:

- There is no `secret_sealed` column and no server key. Approval creates or rekeys the token
  with an unusable random hash, and the redeeming poll generates the secret and stores only its
  SHA-256, so no secret is ever at rest. An approval not collected within 10 minutes revokes
  the token it prepared.
- The start response also carries RFC 8628's `verification_uri` and
  `verification_uri_complete` (the web admin's `/admin/device-tokens/approve?code=…`).
  `verification_payload` is the QR's JSON text.
- Approval without `group_ids` joins the system `all` group. `[]` means no groups.
- The 24 h denial cooldown applies to enrollments too: a denial refuses new enrollments from
  the same address for 24 h unless the administrator unticks it (`block_source=false`).
- Brute force: creation is limited to 10 per address per 10 minutes and 100 pending overall,
  and unknown user codes to 10 per administrator per 10 minutes.
- Unknown or already redeemed device codes answer `invalid_grant`.
- The publisher's `approve-device` subcommand (decision 6, headless `setup-tv-box.sh --approve`)
  is not built yet.

Phases 2 and 3 as built ([DEVICE_ENROLLMENT.md § Android client](DEVICE_ENROLLMENT.md#android-client))
differ from §6.6, §6.7 and §8 in these details:

- The TV shows a QR of `verification_uri_complete` (the web admin approval link), not of
  `verification_payload`, so any phone camera can approve; LelloStore's scanner reads the
  link, the JSON payload and a bare code. LelloStore does not claim the https approval
  links (no `assetlinks.json` on the store, run-time store hosts, and it would hijack the
  web admin page); the reasons are in DEVICE_ENROLLMENT.md.
- TV detection uses the `television` UI mode. The device name is editable before the code
  is requested (1–64 characters). LelloStore is now also in the Android TV launcher.
- `DeviceCredentialStore` is a Keystore AES-GCM sealed file, not encrypted preferences. The
  legacy file is imported on start rather than after the server accepted it; until the
  import succeeds the file itself is used, and a recorded fingerprint keeps an already
  imported file from overwriting a later enrollment. The file is kept for one release
  (downgrades) and is to be deleted, and no longer read, in the release after that.
- Added requirement (§14): finishing enrollment turns on automatic updates, periodic checks
  (24 h when they were off) and the update connection, shows them, and asks for
  unrestricted battery use and notifications. Since the push broker only accepts OIDC
  sessions, a device token keeps the catalog WebSocket open under the update connection
  service instead. Settings shows "Signed in as this device" and **Forget this device**.
- Approval is Settings → **Approve a device**, shown only to administrators (`/api/me`).
  New devices keep the server's default groups unless the administrator changes them; a
  replacement preselects the token with the requested name. Deny's 24 h address block is
  off by default (decision: the phone and the box share an address).
- `APPROVE_DEVICE` returns `DEVICE_TOKEN_ID` and `REKEYED` on success and has two errors of
  its own besides the `CredentialError` codes: `denied` and `unknown_code`.
- The server's push broker does not accept device tokens, so an enrolled box has the
  catalog connection but cannot be a UnifiedPush distributor for other apps.

## 13. Open questions

1. **Who approves grants on the box?** The person holding the TV remote (simple, but anyone in the
   room can approve), or should grants for *device* principals be pre-approved by the admin on the
   server (per type and package policy, no UI on the box), with the on-device prompt only for user
   principals on phones?
2. **Server allowlist strictness.** Is `store_published` (only apps this store distributes, with
   their recorded signer) the default for every type? Should explicitly listing other packages be
   allowed at all?
3. **pezzottify identity for a box.** Which pezzottify account does `device-token:<id>` act as? Options:
   a per-device "household device" account in pezzottify, or an admin-set mapping from the device
   principal to a user in LelloStore (the token's `sub` would then be that user, with `act` = the
   device).
4. **Who may approve an enrollment?** Admin only (as proposed), or any signed-in user for "their"
   devices, with the device principal owned by the user and inheriting that user's app access?
5. **Mediaset and similar.** Confirm they are out of scope, or whether the "opaque secret" type (§10)
   is wanted.
6. **Headless setup.** Is `setup-tv-box.sh --approve` through the publisher's admin session wanted,
   or is approving on the phone/web enough?
7. **Token format.** Store-signed JWTs that relying parties verify offline (proposed), or opaque
   tokens with an introspection endpoint (simpler revocation, but every relying party calls the
   store)?
8. **Defaults.** App-token TTL 10 min, denial cooldown 24 h, enrollment code 10 min / poll 5 s. Are
   these OK?
9. **Shell timing.** Is a new PezzotTV shell (for `<queries>`) acceptable in phase 5, or should
   phase 5 rely only on LelloStore being the installer of record?
10. **Grant reporting to the server** (§7.5). Is it wanted? It gives the admin a fleet view, at the
    cost of the server learning which apps each device runs (it largely knows that already from
    acquisitions).

## 14. Decisions (2026-10-21)

Answers to section 13:

1. On the box, whoever holds the remote approves an app's grant; the admin can revoke.
2. Yes: by default every credential type is limited to packages this store publishes, with the
   signer it records.
3. Open, to verify. Today pezzottify signs in with OIDC (AppAuth); on phones it can hand the
   request to a pinned "trusted authorization broker", TV builds always use the browser, and the
   server's `POST /v1/auth/login` registers the device. There is no phone-to-TV sign-in yet. The
   wish: sign pezzottify in on the box from pezzottify on the phone. `pezzottify.identity`
   (phase 7) should cover that, with the box's own pezzottify device identity rather than a copy
   of the phone's session.
4. Only admins approve enrollments.
5. Mediaset-style service accounts stay out of scope (PezzotTV keeps them).
6. Yes: headless `setup-tv-box.sh --approve` through the publisher's admin session.
7. Store-signed JWTs.
8. The default lifetimes are fine.
9. A new PezzotTV shell in phase 5 is fine.
10. Yes, devices report their grants so the admin can revoke remotely (phase 8).

Added requirement: enrolling a box also turns on LelloStore's automatic updates and its push
connection there, so a new box needs no trip through LelloStore's settings.
