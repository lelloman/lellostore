# LelloStore

LelloStore is a private Android application store. It combines an authenticated
Rust API, a Vue administration interface, and a native Android client in one
repository. Every catalog API request requires an OIDC access token; publishing
and catalog mutations additionally require the configured administrator role.

The wire contract and supported endpoints are documented in [SPEC.md](SPEC.md).
The [product roadmap](docs/PRODUCT_ROADMAP.md) describes the planned separation
of the generic product from the LelloStore deployment.
The [product specification](docs/PRODUCT_SPEC.md) defines discovery and client setup;
the [deployment guide](docs/DEPLOYMENT.md) covers independent instances.

## Repository layout

| Path | Purpose |
| --- | --- |
| `backend/` | Axum API, SQLite catalog, APK/AAB processing, and metrics |
| `frontend/` | Vue 3 and Vuetify administration SPA |
| `android/` | Multi-module Kotlin and Jetpack Compose client |
| `scripts/` | Device-flow command-line publisher and Python tests |
| `Dockerfile` | Production image with the embedded frontend and AAB tooling |

## Prerequisites

- Rust stable with `rustfmt` and `clippy`
- Node.js 22 and npm
- Python 3.10 or newer
- JDK 17 and Android SDK platform 36 for Android development
- `aapt2` for native APK metadata extraction
- Java and `bundletool` when native AAB uploads are required

Docker supplies the runtime APK/AAB tools and is the simplest production build.

## Backend

Cargo downloads `lelloman-simple-server = "=0.1.0"` from crates.io, aliased as
`simple-server`. No sibling checkout or private registry credentials are required. See
[the lifecycle migration notes](docs/STEP_02_LIFECYCLE.md) and
[the logging migration notes](docs/STEP_03A_LOGGING.md).
See [shared routing and multipart](docs/STEP_11_ROUTING.md) for the HTTP integration.
Shared push uses UnifiedPush with administrator-approved VAPID keys. LelloStore's
connection requires login; recipient apps do not need integration with the store's identity provider.
See [UnifiedPush setup and migration](docs/SHARED_ANDROID_NOTIFICATIONS.md).

See [owned WebSocket transport](docs/STEP_11_WEBSOCKETS.md) for catalog and Paravoid events.
See [owned HTTP tracing](docs/STEP_11_HTTP_TRACING.md) for request lifecycle logging.
See [owned HTTP test fixtures](docs/STEP_11_TEST_HARNESS.md) for the backend test transport.

Copy `backend/.env.example` to `backend/.env`, replace the OIDC placeholders,
and create the database parent directory before starting the service:

```sh
cd backend
cp .env.example .env
mkdir -p data
cargo run
```

The API listens on `127.0.0.1:8080` and Prometheus metrics on
`127.0.0.1:9091` by default. `GET /health` remains available if OIDC discovery
fails, but protected `/api` routes fail closed with `503 Service Unavailable`.
Public `/api/server-config` remains available when client configuration is complete.

Important settings are:

| Variable | Default | Meaning |
| --- | --- | --- |
| `LISTEN_ADDR` | `127.0.0.1:8080` | API and web listener |
| `METRICS_ADDR` | `127.0.0.1:9091` | Prometheus listener |
| `SHUTDOWN_GRACE_SECS` | `30` | Total budget for HTTP, metrics, catalog WebSockets, and database cleanup |
| `DATABASE_URL` | `sqlite:data/lellostore.db?mode=rwc` | SQLite connection |
| `STORAGE_PATH` | `data/storage` | APK and icon storage |
| `OIDC_ISSUER_URL` | placeholder | Exact token issuer and discovery base URL |
| `STORE_NAME` | `Android App Store` | Public instance name |
| `OIDC_ANDROID_CLIENT_ID` | unset | Public Android OAuth client |
| `OIDC_WEB_CLIENT_ID` | unset | Public web OAuth client |
| `OIDC_PUBLISHER_CLIENT_ID` | unset | Public device-flow OAuth client |
| `OIDC_SCOPES` | `openid profile email` | Requested client scopes |
| `OIDC_AUDIENCE` | `lellostore` | Required access-token audience |
| `OIDC_ADMIN_ROLE` | `admin` | Role required by admin routes |
| `OIDC_ROLE_CLAIM_PATH` | `realm_access.roles` | Dot-separated token role claim |
| `MAX_UPLOAD_SIZE` | `524288000` | Maximum uploaded file size in bytes |
| `AAPT2_PATH` | auto-detected | Optional explicit `aapt2` executable |
| `BUNDLETOOL_PATH` | unset | Optional bundletool JAR for AAB uploads |
| `JAVA_PATH` | unset | Java executable used with bundletool |

SIGINT or SIGTERM starts coordinated shutdown of both listeners, the metrics
updater, and catalog WebSocket sessions. Once they drain, the database pool
closes within the same budget. A bind failure on either port prevents serving;
a shutdown timeout exits unsuccessfully. Use a deployment stop timeout longer
than the configured grace period.

### Access groups

Database migrations create a protected `all` system group. Membership grants
beta-level access to every current and future application, which includes both
stable and beta releases. Administrators can manage membership, but cannot
rename or delete the group or replace its dynamic policy with per-app rules.

## Frontend

Configure the backend's public client settings, then run:

```sh
cd frontend
npm ci
npm run dev
```

Vite serves the SPA on `http://localhost:3000` and proxies `/api` to
`http://localhost:8080` unless `VITE_API_BASE_URL` overrides the target. The UI
is usable by every authenticated user, while management controls are only shown
to administrators and remain protected independently by the backend. The UI loads
OIDC settings and the instance name from `/api/server-config` at runtime; no
deployment-specific frontend build is needed.

## Android

Create `android/local.properties` with your SDK path:

```properties
sdk.dir=/path/to/Android/Sdk
```

Build the debug client with:

```sh
cd android
./gradlew assembleDebug
```

Fresh installs ask for an HTTPS server address before login, entered manually
or scanned from the website's setup QR code. Confirm the discovered store name
to sign in. The server can later be changed in Settings once active operations
finish. Switching clears the old session and catalog cache. The Android client
discovers OIDC settings from the selected server, stores tokens locally,
downloads APKs with bearer authentication, verifies SHA-256, and delegates
installation to Android's package installer. See
[android/ARCHITECTURE.md](android/ARCHITECTURE.md) for module boundaries.

[Pesce e pesce](docs/PESCE_E_PESCE.md) connects two Android devices over USB to
copy LelloStore, install catalog apps on the receiver, and manage legacy ADB
TCP/IP. Its USB tools also work from the login screen without an account.

## Container deployment

The production image builds the frontend, embeds it in the backend, and includes
Java, bundletool, and `aapt` for APK/AAB processing:

```sh
python3 scripts/setup-store.py --verify-provider
docker compose --env-file deployment/store.env -f deployment/compose.yaml up --build -d
```

The example includes HTTPS through Caddy and persistent volumes. Follow the
[deployment guide](docs/DEPLOYMENT.md) for provider registration, backups, and upgrades.

## Publisher

### Publish the LelloStore Android app

With `android/signing.properties` configured, build and upload the signed Store
APK using the same wrapper workflow as Pezzottify and My Home:

```sh
./scripts/publish-android-to-lellostore.sh --dry-run --json
./scripts/publish-android-to-lellostore.sh
```

The release APK includes the recovery companion for installation through Settings;
the wrapper uploads **only LelloStore**, not a separate companion catalogue entry.
All arguments are forwarded to the common publisher, which handles authentication
and asks for confirmation before uploading. `--dry-run` builds and validates locally
without authentication or upload. `LELLOSTORE_PUBLISHER` can override the publisher;
otherwise the wrapper uses this checkout's `scripts/publish-to-lellostore.py`.

The Store APK uses `major.minor.commit-count` as its version name and
`git rev-list --count HEAD` as its version code. Update `storeVersionMajor` and
`storeVersionMinor` manually in `android/gradle.properties`; the commit count is
derived automatically for every build. Debug builds append `-debug` to the name.
Build from a checkout with full Git history (`git fetch --unshallow` for a shallow
clone). Publish from a branch whose commit count exceeds the last published code;
rebuilding the same commit does not create a new version. External builders and
source archives can supply `-PstoreVersionCode=<integer>` and optionally
`-PstoreVersionName=<version>`. Release operators must choose a code greater than
the last distributed build. Keep the app and recovery companion signed with the
same release key; server operators do not need that key.
Without `signing.properties`, source builds omit the optional recovery companion
and its install button is unavailable. Signed builds bundle it by default;
`-PincludeRecoveryCompanion=false` omits it explicitly. Recovery still requires
matching app/companion signatures and a single-APK app installation.

### Common publisher

The dependency-free publisher is the authoritative client for repository build
scripts and agents. It uses the OIDC device authorization flow and accepts
configuration through options or environment variables:

```sh
export LELLOSTORE_URL=https://store.example.com

python scripts/publish-to-lellostore.py upload path/to/app.apk --dry-run --json
python scripts/publish-to-lellostore.py upload path/to/app.apk
python scripts/publish-to-lellostore.py upload path/to/app-beta.apk --beta
```

The publisher discovers its public OAuth registration from the store URL.
For older servers, supply both `--issuer` and `--client-id` (or
`LELLOSTORE_OIDC_ISSUER` and `LELLOSTORE_CLIENT_ID`). Use
`--name` or `--description` to override extracted metadata, `--beta` to mark a
release as beta, `--json` for machine-readable results, and `logout` to clear
the token cached for one store, issuer, client, and scope set. Stable and beta artifacts for a
package must share one monotonically increasing Android `versionCode` sequence.
HTTPS is required unless `--allow-insecure-http` is explicitly used for local
development.

Application repositories should keep ownership of building and locating their
artifact, then invoke this script rather than copying it. A wrapper can resolve
the authoritative checkout through one configurable path:

```sh
publisher=${LELLOSTORE_PUBLISHER:-$HOME/lelloprojects/lellostore/scripts/publish-to-lellostore.py}
"$publisher" upload app/build/outputs/apk/release/app-release.apk --yes --json
```

Uploads now create drafts. The publisher waits for durable server validation;
review and publish the draft from the app's Releases page, or pass `--publish`
when publication is already intended. Publishing always replaces older unarchived
APKs in the same stable/beta channel and VPKs in the same shell-contract stream,
including older drafts and withdrawn releases. Superseded files are deleted;
release metadata, published identities and installed-client authorization remain.
Use **Archive** on an APK or VPK before publishing its replacement to keep its file.
Archiving does not withdraw a release. Unarchiving makes it eligible for the next
replacement. `--replace-latest` remains a compatibility alias for `--publish`;
`replace_latest=false` cannot disable replacement.

Existing releases start unarchived and are cleaned up on the next publication in
their channel/stream. Failed deletion is retried by the hourly cleanup worker,
including after restart. No files are deleted just by applying the migration.

Archive controls are also available through
`PUT /api/admin/apps/{package}/versions/{code}/archive` and
`PUT /api/admin/apps/{package}/vpks/{id}/archive`, with
`{"expected_revision": 3, "archived": true}` (or `false` to unarchive).
A stale revision or an already replaced artifact returns a conflict.

```bash
python scripts/publish-to-lellostore.py upload path/to/app.apk --publish
python scripts/publish-to-lellostore.py inspect com.example.app
python scripts/publish-to-lellostore.py publish com.example.app 42 --expected-revision 3
```

`POST /api/admin/apps` requires multipart `publication=draft` and
`distribution_mode=normal`. Add `?asynchronous=true` for a durable validation job
(202); browser and publisher use this path. Inspect `/api/admin/uploads` or
`/api/admin/uploads/{id}` and retry a failed job with POST to its `/retry` endpoint.
The admin Uploads page exposes the same status and retry actions. Without the query
parameter the endpoint validates synchronously and returns the draft (201).

Publish with `POST /api/admin/apps/{package}/publications`, supplying
`version_code` and the reviewed `expected_revision`. The legacy `replace_latest`
field is accepted but no longer changes retention.
Publication requires a version code higher than every previously published APK,
including withdrawn releases. Stale review revisions return a conflict.
Legacy uploads without explicit draft intent fail with `client_upgrade_required`.
Deploy the backend before the new browser, Android client and publisher.

Acquisitions bind a user to exact APK bytes for 24 hours and recheck live access
on every download. Browser and Android downloads verify the acquisition's size
and checksum. Paravoid shell/VPK publication requires verified artifacts and the
configured signing authorities; see [signing setup](docs/PARAVOID_SIGNING.md),
[device acceptance](docs/PARAVOID_DEVICE_ACCEPTANCE.md) and
[implementation status](docs/PARAVOID_IMPLEMENTATION.md) for remaining release gates.

Only pass `--yes` after the upload has already been authorized; without it the
publisher asks for interactive confirmation immediately before authentication
and upload. Direct invocation with only an artifact path remains supported for
older wrappers.

## Verification

See the [Axum migration notes](docs/SIMPLE_SERVER_MIGRATION.md) and
[lifecycle migration notes](docs/STEP_02_LIFECYCLE.md) for dependency ownership,
shutdown behavior, and migration validation.

Run the same checks enforced by CI:

```sh
cd frontend
npm ci
npm run lint
npm run type-check
npm run test:run
npm run build

cd ../backend
cargo fmt --check
cargo clippy --all-targets --all-features -- -D warnings
cargo test --all-features

cd ../android
./gradlew lint test

cd ..
python -m unittest discover -s scripts/tests
```

The backend all-features checks require `frontend/dist`; the frontend build in
the sequence above creates it. CI runs each component from a clean checkout.

## License

Original project code is licensed under the [Apache License 2.0](LICENSE).
Third-party dependencies and vendored code retain their own licenses and notices.
