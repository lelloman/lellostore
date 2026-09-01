# LelloStore

LelloStore is a private Android application store. It combines an authenticated
Rust API, a Vue administration interface, and a native Android client in one
repository. Every catalog API request requires an OIDC access token; publishing
and catalog mutations additionally require the configured administrator role.

The wire contract and supported endpoints are documented in [SPEC.md](SPEC.md).

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
fails, but all `/api` routes fail closed with `503 Service Unavailable`.

Important settings are:

| Variable | Default | Meaning |
| --- | --- | --- |
| `LISTEN_ADDR` | `127.0.0.1:8080` | API and web listener |
| `METRICS_ADDR` | `127.0.0.1:9091` | Prometheus listener |
| `DATABASE_URL` | `sqlite:data/lellostore.db?mode=rwc` | SQLite connection |
| `STORAGE_PATH` | `data/storage` | APK and icon storage |
| `OIDC_ISSUER_URL` | placeholder | Exact token issuer and discovery base URL |
| `OIDC_AUDIENCE` | `lellostore` | Required access-token audience |
| `OIDC_ADMIN_ROLE` | `admin` | Role required by admin routes |
| `OIDC_ROLE_CLAIM_PATH` | `realm_access.roles` | Dot-separated token role claim |
| `MAX_UPLOAD_SIZE` | `524288000` | Maximum uploaded file size in bytes |
| `AAPT2_PATH` | auto-detected | Optional explicit `aapt2` executable |
| `BUNDLETOOL_PATH` | unset | Optional bundletool JAR for AAB uploads |
| `JAVA_PATH` | unset | Java executable used with bundletool |

## Frontend

Copy `frontend/.env.example` to `frontend/.env.local` and set the OIDC issuer,
client ID, administrator role, and role claim path to the same values used by
the backend. Then run:

```sh
cd frontend
npm ci
npm run dev
```

Vite serves the SPA on `http://localhost:3000` and proxies `/api` to
`http://localhost:8080` unless `VITE_API_BASE_URL` overrides the target. The UI
is usable by every authenticated user, while management controls are only shown
to administrators and remain protected independently by the backend.

## Android

Create `android/local.properties` with your SDK path. You may also override the
HTTPS store URL compiled as the initial value:

```properties
sdk.dir=/path/to/Android/Sdk
default.server.url=https://store.example.com
```

Build the debug client with:

```sh
cd android
./gradlew assembleDebug
```

The server URL can be changed in the app and must use HTTPS. The Android client
discovers OIDC endpoints from its configured issuer, stores tokens locally,
downloads APKs with bearer authentication, verifies SHA-256, and delegates
installation to Android's package installer. See
[android/ARCHITECTURE.md](android/ARCHITECTURE.md) for module boundaries.

## Container deployment

The production image builds the frontend, embeds it in the backend, and includes
Java, bundletool, and `aapt` for APK/AAB processing:

```sh
docker build \
  --build-arg VITE_OIDC_ISSUER_URL=https://auth.example.com/realms/store \
  --build-arg VITE_OIDC_CLIENT_ID=lellostore-frontend \
  -t lellostore .

docker run --rm -p 8080:8080 -p 9091:9091 \
  -e OIDC_ISSUER_URL=https://auth.example.com/realms/store \
  -e OIDC_AUDIENCE=lellostore \
  -v lellostore-data:/app/data \
  lellostore
```

Terminate TLS at a reverse proxy and expose the store over HTTPS. Keep the
metrics port private unless it is intentionally scraped.

## Publisher

The dependency-free publisher uses the OIDC device authorization flow:

```sh
python scripts/publish-to-lellostore.py configure
python scripts/publish-to-lellostore.py path/to/app.apk
```

Configuration rewrites the script's three endpoint constants; review that diff
before committing or keep it as a local change.

## Verification

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
