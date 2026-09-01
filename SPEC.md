# LelloStore specification

## Purpose

LelloStore distributes Android applications to authenticated users. It hosts a
catalog and APK files, gives administrators a web interface and command-line
publisher, and lets Android users browse, install, and update applications.

All catalog and download operations require a valid OIDC bearer token. Upload,
edit, icon, and delete operations also require the configured administrator
role. Authentication failures fail closed; only health and embedded web assets
remain public.

## Components

- The Rust/Axum backend owns the SQLite catalog, filesystem storage, OIDC token
  validation, APK metadata extraction, optional AAB conversion, and Prometheus
  metrics.
- The Vue 3 frontend provides authenticated catalog browsing and administrator
  management. Its OIDC settings are compiled into the Vite bundle.
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

## HTTP API

The API uses JSON with snake_case field names. Successful delete operations
return `204 No Content`. APK downloads support one byte range and return either
`200 OK` or `206 Partial Content` as appropriate.

### Health and metrics

| Method | Path | Authentication | Result |
| --- | --- | --- | --- |
| `GET` | `/health` | None | `{"status":"healthy"}` |
| `GET` | `/metrics` | Network policy | Prometheus text on the separate metrics listener |

### User routes

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/apps` | Catalog with each application's latest version |
| `GET` | `/api/apps/{package_name}` | Application details and all versions |
| `GET` | `/api/apps/{package_name}/icon` | PNG icon |
| `GET` | `/api/apps/{package_name}/versions/{version_code}/apk` | APK download |

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
`409`, `413`, `415`, `500`, or `503`.

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
