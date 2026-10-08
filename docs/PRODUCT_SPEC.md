# Generic store product specification

Status: implementation in progress. Apache License 2.0 is selected and applied to original project code. The release Android application ID remains `com.lelloman.store`. Product name and public repository destination remain undecided. This specification implements the [roadmap](PRODUCT_ROADMAP.md); the [audit](PRODUCT_DECOUPLING_AUDIT.md) records the starting point.

## Initial product boundary

The same backend image and Android APK serve independent deployments. Instance configuration owns the server name, public URL, identity provider, public OAuth client registrations, authorization mapping, storage, and optional integrations. Application code, protocols, migrations, and tests belong to the public product.

The proposed first release supports one active Android store and external OIDC authentication. Additional authentication methods and anonymous catalogs remain separate product decisions. Discovery explicitly names the authentication method rather than making OIDC an implicit client assumption.

## Public discovery contract

`GET /api/server-config` is unauthenticated and never redirects a client to another store. Responses use `Cache-Control: no-store`. Version 1 contains:

```json
{
  "schema_version": 1,
  "name": "Example Store",
  "auth": {
    "method": "oidc",
    "issuer_url": "https://identity.example.com/realms/store",
    "clients": {
      "android": "store-android",
      "web": "store-web",
      "publisher": "store-publisher"
    },
    "scopes": ["openid", "profile", "email"]
  },
  "capabilities": {
    "push": false,
    "paravoid": false
  }
}
```

Only public metadata is included. An incomplete server configuration returns 503 with code `server_not_configured`; no client silently falls back to personal infrastructure. Unknown schema versions or authentication methods produce a clear compatibility error. Clients ignore unknown optional fields. The selected HTTPS origin is the store identity; issuer URL and client ID additionally bind an authentication session.

Discovery does not require a working identity-provider connection. An already configured server can describe itself during an identity-provider outage, while protected API routes remain unavailable until authentication is operational. This distinguishes missing operator configuration from transient login failure.

## Configuration and backend setup

Runtime environment/file configuration is authoritative for the initial implementation. An interactive local setup command writes a validated deployment configuration and an equivalent unattended workflow accepts explicit inputs. Setup does not expose an unauthenticated administrative claim endpoint. Configuration changes take effect on restart.

Required client metadata uses `STORE_NAME`, `OIDC_ANDROID_CLIENT_ID`, `OIDC_WEB_CLIENT_ID`, `OIDC_PUBLISHER_CLIENT_ID`, and space-separated `OIDC_SCOPES`, alongside existing issuer/audience/admin-role settings. Public clients use authorization code with PKCE (Android/web) or device authorization (publisher), without embedded client secrets. Provider-specific audience configuration belongs to operator setup instructions.

The setup workflow covers public HTTPS origin, OIDC registration and initial administrator role, persistent data/storage, optional push and Paravoid configuration, startup validation, backup/restore, and upgrades. The web application loads runtime discovery before restoring a session or starting login. It uses backend identity for authorization instead of duplicating provider claim rules.

## Android setup and session isolation

Fresh installs start at server setup. Manual entry and QR scanning converge on the same address validation and discovery flow. Setup shows server name and address before confirmation; login follows confirmation. Invalid payloads, unreachable servers, incomplete configuration, and unsupported protocols are distinct errors.

The version 1 QR payload is JSON with `type: "store-setup"`, `version: 1`, and `server_url`. It contains no secrets or credentials. The client uses the selected origin to retrieve current metadata. HTTPS origin URLs must not contain credentials, path prefixes, queries, or fragments.

A server switch verifies the destination before invalidating the old session and blocks new authenticated work during cleanup. The current implementation refuses switching while catalog, download, install, or remote-device work is active, with instructions to wait or cancel downloads. Old HTTP responses and authorization callbacks cannot commit after the switch. The new server receives no old access token. Credentials attach only to the authenticated store origin; discovery and arbitrary external images never receive them. Device-level preferences and installed-app facts remain separate from server-owned catalog/preferences.

Persist server selection explicitly. Existing installations that previously depended on an implicit default need an explicit migration; fresh installs must never contact the personal store by default. A saved session is restored only when its server/issuer/client binding matches current configuration.

## Migration and delivery

Ship backend discovery and deployment configuration before requiring discovery in new clients. Keep existing authenticated routes compatible during this transition. Bind backend catalog identities to the configured issuer or reject an unreviewed issuer change against an existing database.

Make Android dependencies obtainable from a clean checkout, keep recovery signature checks intact, and specify external builder version/signing inputs. Select the product name before public publication; original project code uses Apache License 2.0. The release application ID is fixed at `com.lelloman.store`, with the existing `.debug` suffix for debug builds. Retain `com.lelloman.store:/oauth2redirect`, the `com.lelloman.store.recovery` companion, and existing IPC/permission identities when renaming the product. Existing installations can continue receiving updates under the same application ID, subject to compatible signing and increasing version codes; this still requires upgrade acceptance testing.

The LelloStore deployment eventually pins a public product release and contains only supported configuration and operations. Preserve application history and runtime data until the extraction and migration are verified.

## Acceptance and progress

- [x] Public discovery configuration and fail-closed integration tests.
- [x] Runtime web authentication and instance naming with no deployment-specific frontend build.
- [x] Backend operator setup and runnable deployment configuration.
- [ ] Android setup by server URL and QR, dynamic authentication, safe session switching.
- [x] Publisher discovery and destination-scoped authentication cache.
- [ ] Independent provider validation and identity migration protection.
- [ ] Clean-checkout build and release dependency/signing work.
- [ ] Two-deployment end-to-end verification and existing-client migration.
- [x] Apache License 2.0 selected; canonical license and package metadata added, preserving third-party notices.
- [x] Release Android application ID confirmed as `com.lelloman.store`; build, OAuth callback, and recovery contracts verified against that decision.
- [ ] Selected name, reviewed public repository and release.
- [ ] LelloStore configuration repository and verified deployment migration.

### Verification recorded so far

- Backend Clippy and full all-features tests passed; an additional two-issuer test rejects cross-store tokens with identical subject, audience, and fixture signing key.
- Frontend lint, type checking, 76 tests, and production build passed, including runtime discovery and backend-owned administrator identity.
- All 35 Python publisher/setup tests passed. Compose configuration validates and the pinned Caddy image exists.
- Production image builds. `scripts/check-product-image.py IMAGE` passes against two isolated configurations with independent data and unreachable issuers: public discovery, embedded UI, health, and protected API failure. This is not a real-provider login test.
- All 315 Android app, UI, domain, local data, and remote API unit tests and instrumented-test source compilation passed. Added tests cover QR payloads, discovery rejection, legacy setup migration, operation isolation, token destination, stale refresh, stale 401 responses, and push-broker destination binding.
- Three additional database-backed server-switch tests pass: successful switching removes catalog/app preferences/APK cache while preserving installed-app records and device preferences; discovery failure or active work preserves the existing connection and data. These exercise the actual switch coordinator and Room database, beyond the operation-gate unit tests.
- A source-only export builds a debug APK using explicit version inputs, without Git history, signing files, or a sibling checkout. The optional recovery companion is excluded from unsigned builds; its signature checks remain intact.
- The pinned Paravoid Java interoperability test passes with an explicit upstream source path and Android SDK.

### Remaining acceptance and release work

Exercise full browser/device login against two real OIDC configurations, QR camera scanning, upload/install/update, disabled integrations, and interrupted switching. No Android device is currently connected. Discovery advertises optional capabilities; capability-aware client behavior needs final acceptance. Test the existing production client upgrade and restore a backup before switching the live deployment.

Select the product name and public repository owner/name; the repository destination is explicitly undecided. The project license is Apache 2.0 and the release Android application ID remains `com.lelloman.store`. Third-party and vendored-source redistribution still needs review before publishing. Publish a reviewed source snapshot or reviewed history, create a pinned release, then prepare the existing repository as a deployment consuming that release. Live infrastructure has not been changed.
