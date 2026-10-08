# Deploy an independent store

The backend serves the catalog, administration UI, and public client configuration from one HTTPS origin. Authentication and store naming are runtime configuration; the frontend image does not contain deployment-specific OAuth settings.

## Prepare the identity provider

Use an OIDC provider that issues JWT access tokens with the configured audience. Create three public clients without client secrets:

| Client | Flow | Callback |
| --- | --- | --- |
| Android | Authorization code with PKCE S256 | `com.lelloman.store:/oauth2redirect` |
| Web | Authorization code with PKCE S256 | `https://your-store.example/callback` for login and logout |
| Publisher | Device authorization | No browser callback |

Allow the web origin in the provider's CORS configuration. Configure requested scopes and refresh-token behavior with the provider. The default scopes are `openid profile email`; include `offline_access` if your provider requires it for refresh tokens. Ensure the access token audience matches `OIDC_AUDIENCE` and the administrator role appears at `OIDC_ROLE_CLAIM_PATH` in that token. Assign `OIDC_ADMIN_ROLE` to the initial administrator before first login. The web UI obtains authorization from `/api/me`; ID-token role claims are not its authority.

Keycloak-style providers commonly use `realm_access.roles`. Other providers can supply a string or string array through another configured claim path. Account creation and password recovery are handled by the configured provider.

## Generate configuration

From the repository root, run:

```sh
python3 scripts/setup-store.py --verify-provider
```

The command asks for the store name, public HTTPS address, issuer, audience, public client IDs, administrator role mapping, and scopes. It writes `deployment/store.env` with restrictive permissions and refuses to overwrite existing configuration. It never claims a server through an unauthenticated web endpoint.

An unattended equivalent is:

```sh
python3 scripts/setup-store.py --non-interactive \
  --name "Example Store" \
  --server-url https://store.example.org \
  --issuer https://identity.example.org/realms/store \
  --audience store \
  --android-client store-android \
  --web-client store-web \
  --publisher-client store-publisher
```

`--verify-provider` verifies public metadata, issuer matching, HTTPS endpoints, device authorization, and advertised PKCE S256 support. It does not register clients, assign roles, or prove a user's token audience; complete a real login to verify those settings. Omit it while preparing configuration before the provider is reachable.

The included proxy requires a public DNS hostname pointing at the host and ports 80/443 available for HTTPS certificate provisioning. Store URL path prefixes and nonstandard public ports are not supported by this deployment example.

## Start the deployment

```sh
docker compose --env-file deployment/store.env -f deployment/compose.yaml up --build -d
```

The example builds a local image until a public release image is selected. Once available, set `STORE_IMAGE` to its pinned tag or digest and use `up -d --no-build`. The backend has no public host port; Caddy terminates HTTPS and proxies to port 8080 on the internal container network. Catalog/database/artifact data and proxy certificate state use separate named volumes. Metrics remain on backend container loopback.

Verify `/health`, then `/api/server-config`. Discovery returns a non-cacheable 503 until issuer and all three public client registrations are configured. During an identity-provider outage discovery can still work while protected catalog routes remain unavailable. Sign in on the website, verify administrator access, and upload an initial app. Grant users access through the Access page before expecting them to see private catalog entries.

Configuration files/environment variables are authoritative. Changes take effect after recreating the backend container. Do not set old `VITE_OIDC_*` Docker build arguments: the frontend now reads `/api/server-config`. Runtime configuration includes the existing backend settings plus:

| Setting | Purpose |
| --- | --- |
| `STORE_NAME` | Name presented by discovery and the web UI |
| `OIDC_ANDROID_CLIENT_ID` | Public Android OAuth registration |
| `OIDC_WEB_CLIENT_ID` | Public web OAuth registration |
| `OIDC_PUBLISHER_CLIENT_ID` | Public publisher OAuth registration |
| `OIDC_SCOPES` | Space-separated scopes, including `openid` |
| `STORE_HOST` | Public hostname used by the Compose proxy |
| `PUBLIC_BASE_URL` | Public origin recorded by setup for operator tooling |

`PUBLIC_BASE_URL` is not an API redirection setting; clients use the address they selected. `STORE_HOST` and `PUBLIC_BASE_URL` are deployment-tool inputs rather than backend discovery fields.

## Optional integrations

Pass `--push` during setup to configure `NOTIFICATIONS_ENABLED` and `PUSH_PUBLIC_BASE_URL`. Register permitted sender keys through administration; see [shared notifications](SHARED_ANDROID_NOTIFICATIONS.md). Paravoid signing is separately configured through a mounted private configuration and key files; see [Paravoid signing](PARAVOID_SIGNING.md). Ordinary APK distribution does not require Paravoid signing.

## Upgrade and recovery

Before upgrading, stop the backend and back up its complete data volume, deployment configuration, and any separately mounted signing keys. Preserve database and artifact files from the same stopped state. Keep those backups private. [verify-store-backup.py](../scripts/verify-store-backup.py) verifies a restored offline database/artifact copy; exercise the restore on an isolated deployment before relying on it.

Pin the next image, recreate the service, and check discovery, login, catalog access, publication, and download. Database migrations run on startup. A failed upgrade may require restoring the matching pre-upgrade database/artifact snapshot with the previous image; replacing the binary alone is not a general migration rollback.

The first configured startup pins this database to its OIDC issuer. A different issuer is rejected to prevent unrelated accounts with matching subject IDs inheriting access. An issuer migration must explicitly map users, grants, acquisitions, and related identities; changing the URL alone is not supported. For an accidental change, restore the previous configuration and restart.

## Build and integration verification

`python3 scripts/check-product-image.py IMAGE` checks two disposable local containers of a built image. It verifies separate public configurations and fail-closed behavior during identity-provider outages, then removes its containers. Real provider login, publication, and Android installation still need end-to-end acceptance.

Android source builds accept `-PstoreVersionCode=<integer>` and optionally `-PstoreVersionName=<version>`; without explicit inputs the historical commit-count versioning remains. The pinned update IPC source lives in `android/paravoid-update-ipc`, with provenance in `UPSTREAM.md`. Optional upstream Java conformance gates require a checkout passed as their first argument or `PARAVOID_SOURCE_DIR`; they no longer assume a sibling directory. `PARAVOID_REVISION` defaults to `32461c8336325c3381e089193ed77246c5fb90a0` for the general/VPK gates. The DVPK gate retains its separate encoder compatibility pin. Set `ANDROID_HOME` for tests that build real Android artifacts.

## Existing LelloStore migration

Keep the current domain, storage, issuer, client registrations, scopes, role mapping, and signing material. Add the three public client IDs and `STORE_NAME` to backend runtime configuration before deploying a client that uses discovery. The generic Android migration and final public-product extraction remain tracked in [the specification](PRODUCT_SPEC.md). Do not replace a live deployment with the new example's empty volume or default audience.
