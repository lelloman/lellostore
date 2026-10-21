# LelloStore specification

## Purpose

LelloStore distributes Android applications to authenticated users. It hosts a
catalog and APK files, gives administrators a web interface and command-line
publisher, and lets Android users browse, install, and update applications.

All catalog and download operations require a valid OIDC bearer token. Upload,
edit, icon, and delete operations also require the configured administrator
role. Authentication failures fail closed; health, embedded web assets, and
public client discovery remain available without a login.

## Components

- The Rust/Axum backend owns the SQLite catalog, filesystem storage, OIDC token
  validation, APK metadata extraction, optional AAB conversion, and Prometheus
  metrics.
- The Vue 3 frontend provides authenticated catalog browsing and administrator
  management. Its instance name and OIDC settings are loaded from runtime discovery.
- The Kotlin/Compose Android client stores a catalog cache and preferences,
  discovers OIDC endpoints, downloads with authorization, verifies SHA-256, and
  hands verified APKs to Android's installer.
- The Python publisher uploads an APK through the OIDC device flow without
  third-party Python packages.

## Authentication and authorization

The backend validates JWT signature, issuer, audience, expiry, and not-before
claims using keys from the issuer's discovery document and JWKS endpoint. JWKS
refreshes are rate-limited. Roles are read from the dot-separated claim path in
`OIDC_ROLE_CLAIM_PATH`; `OIDC_ADMIN_ROLE` selects the administrator value.

The frontend and Android app use Authorization Code with PKCE. The publisher
uses Device Authorization Grant. Clients send access tokens as
`Authorization: Bearer <token>`.

### Publish tokens

Unattended release pipelines authenticate with per-app publish tokens instead of
OIDC. A token is `lellostore_pub_` followed by 32 random bytes in hex, sent as
`Authorization: Bearer <token>`. The store keeps only its SHA-256
(`publish_tokens` table) with name, package, creator, creation, optional expiry,
last use and revocation times. A bearer value with the `lellostore_pub_` prefix
is resolved against that table instead of being validated as a JWT; an unknown,
revoked or expired token is `401`. A valid one becomes a non-administrator
principal scoped to one package.

Administrator-only handlers refuse publish tokens (`403`), as do catalog and
acquisition routes. Only these handlers admit a token, each enforcing its scope:

| Method | Path | Token scope |
| --- | --- | --- |
| `POST` | `/api/admin/apps?asynchronous=true` | Parsed APK package must equal the token's package; otherwise `403` (checked at upload for APKs, and by the validation worker, which fails the job and discards the artifact). Synchronous uploads are refused. |
| `GET` | `/api/admin/uploads/{id}` | Only jobs created by the same token |
| `GET` | `/api/admin/apps/{package}` | Token's package |
| `POST` | `/api/admin/apps/{package}/publications` | Token's package |
| `GET` | `/api/admin/apps/{package}/distribution` | Token's package |
| `POST` | `/api/admin/apps/{package}/contracts/{contract}/vpks` | Token's package |
| `POST` | `/api/admin/apps/{package}/vpks/{id}/publish` | Token's package |

VPK withdrawal, release notes, archiving, stream changes, grant revocation,
APK withdrawal and every other administrative route remain administrator-only.

Upload jobs record the creating token (`upload_jobs.publish_token_id`). The
worker re-checks that the token is still active before validating its job, so
revocation also refuses queued work. Administrators manage tokens with
`POST /api/admin/publish-tokens` (`name`, `package_name`, optional RFC 3339
`expires_at`; returns the secret once), `GET /api/admin/publish-tokens` (no
secrets; includes `last_used_at` and recent uploads) and
`DELETE /api/admin/publish-tokens/{id}` (revoke). Creation and revocation are
written to the access audit log. Refusals are logged with the token id, never the
secret.

### Device tokens

Provisioned devices (for example a TV box that self-updates through the Android
client) can read the catalog without an interactive login using a device token.
A token is `lellostore_dev_` followed by 32 random bytes in hex, sent as
`Authorization: Bearer <token>`. The store keeps only its SHA-256
(`device_tokens` table) with name, creator, creation, optional expiry, last use
and revocation times; a token has no package or scope of its own. A bearer value
with the `lellostore_dev_` prefix is resolved against that table instead of
being validated as a JWT; an unknown, revoked or expired token is `401`.

A valid token authenticates as the ordinary non-administrator principal
`device-token:<id>`. Creating a token registers that subject as a known user, so
administrators grant it apps directly or through group memberships exactly like
any other user, and `GET /api/admin/users` labels it with `device_token_name`
(`null` for other users). The principal can use every catalog, download,
acquisition, `/api/me` and `/api/events` route, sees only what it is granted,
and is refused (`403`) by every administrator route, including publishing
routes and token management.

Administrators manage tokens with `POST /api/admin/device-tokens` (`name`,
optional RFC 3339 `expires_at`, optional `group_ids`; returns the secret once),
`GET /api/admin/device-tokens` (no secrets),
`DELETE /api/admin/device-tokens/{id}` (revoke, idempotent; unknown ids are
`404`), `POST /api/admin/device-tokens/{id}/rekey` (a new secret, returned once,
for the same id, subject, grants and groups; clears `revoked_at`),
`POST /api/admin/device-tokens/{id}/purge` (permanently delete a **revoked**
token, `204`; an active token is `409`, revoke it first) and
`POST /api/admin/device-tokens/purge-revoked` (delete every revoked token,
`{"deleted": n}`). Each token is returned as:

```json
{
  "id": "6f1c…",
  "name": "Living room TV",
  "created_by": "admin-subject",
  "created_at": "2026-10-18 10:00:00",
  "expires_at": null,
  "last_used_at": "2026-10-18 10:05:00",
  "revoked_at": null,
  "subject": "device-token:6f1c…",
  "groups": [{"id": 3, "name": "TV boxes"}]
}
```

The create response adds `secret`. Group memberships requested at creation are
applied in the same transaction as the token: an unknown group fails the request
(`404`) and creates nothing. Creation, revocation and memberships are written to
the access audit log; refusals are logged with the token id, never the secret.
Revocation keeps the principal's known-user row, grants and memberships.
Deleting a revoked token (`device_token.delete` in the audit log) also deletes
that known-user row, its direct grants and its group memberships; acquisition
and audit history are kept.

A device can also obtain a token itself through
[device-code enrollment](docs/DEVICE_ENROLLMENT.md): it starts an enrollment
without credentials, an administrator approves its user code as a new token or as
a rekey of an existing one, and its next poll receives `device-token.json` once.

Push notification registration and Paravoid keyed delivery validate OIDC JWTs
only and do not accept device tokens; a device-token client relies on its
periodic update checks and the authenticated `/api/events` stream.

`GET /api/server-config` advertises a versioned authentication method, issuer,
public client IDs/scopes, instance name, and optional capabilities. Missing
operator configuration returns an uncached 503 with `server_not_configured`.
See [the product specification](docs/PRODUCT_SPEC.md) for its JSON contract,
Android server/QR setup, session isolation, and migration requirements.

## HTTP API

The API uses JSON with snake_case field names. Successful delete operations
return `204 No Content`. APK downloads support one byte range and return either
`200 OK` or `206 Partial Content` as appropriate.

### Health and metrics

| Method | Path | Authentication | Result |
| --- | --- | --- | --- |
| `GET` | `/health` | None | `{"status":"healthy"}` |
| `GET` | `/api/server-config` | None | Public client setup; schema version 1 |
| `POST` | `/api/device-enrollments` | None (quota per address, 4 KiB body) | Start a [device enrollment](docs/DEVICE_ENROLLMENT.md): `201` with `device_code`, `user_code`, `verification_payload`, `expires_in`, `interval` |
| `POST` | `/api/device-enrollments/poll` | None (`device_code` in the body) | `200` with `device-token.json` once, or `400` with an RFC 8628 `error` |
| `GET` | `/metrics` | Network policy | Prometheus text on the separate metrics listener |

### User routes

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/apps` | Catalog with each application's latest version |
| `GET` | `/api/apps/{package_name}` | Application details and all versions |
| `GET` | `/api/apps/{package_name}/icon` | PNG icon |
| `GET` | `/api/apps/{package_name}/versions/{version_code}/apk` | APK download |

Returned `icon_url` values include a `?v=<sha256>` content fingerprint when the
icon file is available. Clients should use the full returned URL as the image
cache key: it stays stable for unchanged bytes and changes when the icon is
replaced. The icon endpoint also continues to accept unversioned URLs.

`GET /api/apps` response:

```json
{
  "apps": [
    {
      "package_name": "com.example.app",
      "name": "Example App",
      "description": "An example application",
      "icon_url": "/api/apps/com.example.app/icon",
      "latest_version": {
        "version_code": 10,
        "version_name": "1.0.0",
        "size": 5242880,
        "min_sdk": 24,
        "uploaded_at": "2026-08-31T10:00:00Z"
      }
    }
  ]
}
```

`GET /api/apps/{package_name}` response:

```json
{
  "package_name": "com.example.app",
  "name": "Example App",
  "description": "An example application",
  "icon_url": "/api/apps/com.example.app/icon",
  "versions": [
    {
      "version_code": 10,
      "version_name": "1.0.0",
      "apk_url": "/api/apps/com.example.app/versions/10/apk",
      "size": 5242880,
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "min_sdk": 24,
      "uploaded_at": "2026-08-31T10:00:00Z"
    }
  ]
}
```

### Administrator routes

| Method | Path | Input | Result |
| --- | --- | --- | --- |
| `POST` | `/api/admin/apps` | Multipart `file`, optional `name` and `description` | Extract and add one APK/AAB version |
| `PUT` | `/api/admin/apps/{package_name}` | JSON `name` and/or `description` | Updated application details |
| `POST` | `/api/admin/apps/{package_name}/icon` | Multipart `file` or `icon` | Replace icon with a normalized 192×192 PNG |
| `DELETE` | `/api/admin/apps/{package_name}` | None | Delete catalog entry, versions, and stored files |
| `DELETE` | `/api/admin/apps/{package_name}/versions/{version_code}` | None | Delete version; delete application if it was the last |
| `GET` | `/api/admin/publish-tokens` | None | Publish tokens without secrets, with `last_used_at` and `recent_uploads` |
| `POST` | `/api/admin/publish-tokens` | JSON `name`, `package_name`, optional `expires_at` | `201` with the token and its one-time `secret` |
| `DELETE` | `/api/admin/publish-tokens/{id}` | None | Revoke; returns the token with `revoked_at` |
| `GET` | `/api/admin/device-tokens` | None | Device tokens without secrets, with `subject`, `groups` and `last_used_at` |
| `POST` | `/api/admin/device-tokens` | JSON `name`, optional `expires_at` and `group_ids` | `201` with the token and its one-time `secret` |
| `DELETE` | `/api/admin/device-tokens/{id}` | None | Revoke (idempotent); returns the token with `revoked_at` |
| `POST` | `/api/admin/device-tokens/{id}/rekey` | None | The same token with a new one-time `secret`; `revoked_at` cleared |
| `POST` | `/api/admin/device-tokens/{id}/purge` | None | Delete a revoked token with its principal (`204`); `409` while active |
| `POST` | `/api/admin/device-tokens/purge-revoked` | None | Delete every revoked token; `{"deleted": n}` |
| `GET` | `/api/admin/device-enrollments/{user_code}` | None | A pending enrollment's details, or `404` |
| `POST` | `/api/admin/device-enrollments/{user_code}/approve` | JSON optional `name`, `group_ids` (default: the `all` group), `replace_device_token_id` | `{"device_token", "rekeyed"}`, no secret |
| `POST` | `/api/admin/device-enrollments/{user_code}/deny` | Optional query `block_source=false` | `204`; by default blocks the address for 24 h |

Uploads accept exactly one `.apk` or `.aab` file. The backend streams the input
to a private temporary location while enforcing `MAX_UPLOAD_SIZE`. It extracts
the package, version, minimum SDK, and icon; computes SHA-256; then publishes the
catalog and final files without allowing concurrent uploads to overwrite an
existing version. AAB uploads require Java and bundletool.

Upload response:

```json
{
  "package_name": "com.example.app",
  "name": "Example App",
  "description": null,
  "icon_url": "/api/apps/com.example.app/icon",
  "version": {
    "version_code": 10,
    "version_name": "1.0.0",
    "apk_url": "/api/apps/com.example.app/versions/10/apk",
    "size": 5242880,
    "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "min_sdk": 24,
    "uploaded_at": "2026-08-31T10:00:00Z"
  }
}
```

Metadata update request:

```json
{
  "name": "New application name",
  "description": "Updated description"
}
```

Error responses contain an `error` identifier and a safe human-readable
`message`. Depending on the failure, routes use `400`, `401`, `403`, `404`,
`409`, `413`, `415`, `429`, `500`, or `503`.

```json
{
  "error": "conflict",
  "message": "Version already exists"
}
```

## Persistence

SQLite is authoritative for the catalog. `apps.package_name` is the primary key
and `app_versions` has a unique `(package_name, version_code)` constraint with a
cascading foreign key. APKs live below
`{storage_path}/apks/{package_name}/{version_code}.apk`; icons live below
`{storage_path}/icons/`.

Database mutations complete before best-effort cleanup of obsolete files, so a
filesystem failure cannot leave catalog rows that point to files intentionally
deleted by the same request.

The Android client stores its catalog and installed-app observations in Room,
preferences in DataStore, and authentication state in encrypted shared
preferences. Catalog refreshes replace the cache transactionally.

## Security and operational requirements

- Terminate TLS before the backend and expose clients only over HTTPS.
- Use an exact trusted OIDC issuer and audience; never use the placeholder
  issuer in production.
- Keep the metrics listener private.
- Persist both the SQLite database and storage directory together.
- Back up catalog and APK data consistently.
- Grant the administrator role only to users allowed to publish or delete apps.
- Android verifies every downloaded APK against the catalog SHA-256 before
  installation.
- The Android client accepts only HTTPS store URLs.

## Deliberate exclusions

LelloStore does not sign or modify uploaded APKs, provide delta updates, support
iOS, expose an unauthenticated catalog, or implement per-user application
visibility. Update notifications are generated by periodic client polling, not
Firebase push messaging.
