# Epic 7: Frontend Embedding - Implementation Plan

**Goal**: Bundle the Vue frontend into the Rust binary for single-binary deployment.

## Tasks

| # | Task | Status | Description |
|---|------|--------|-------------|
| 1 | Add rust-embed dependency | Done | Add rust-embed crate to embed static files |
| 2 | Create build script | Done | Build frontend before Rust compilation |
| 3 | Static file serving | Done | Serve embedded files at root path with SPA fallback |
| 4 | Configure production build | Done | Optimize frontend build, ensure correct paths |
| 5 | Update CI/build docs | Done | Documented in build.rs - auto-builds in release mode |

## Detailed Task Breakdown

### Task 1: Add rust-embed dependency
- Add `rust-embed` crate to Cargo.toml
- Create embedded assets module
- Configure embed path to `../frontend/dist`

### Task 2: Create build script
- Add build.rs that runs frontend build
- Ensure frontend is built before embedding
- Handle build errors gracefully

### Task 3: Static file serving
- Serve embedded files at `/`
- Serve `index.html` for SPA routes (fallback for 404s on static paths)
- Set correct Content-Type headers based on file extension
- Enable gzip compression with tower-http

### Task 4: Configure production build
- Configure Vite for production build
- Ensure API base URL works with same-origin serving
- Test that all assets load correctly

### Task 5: Update CI/build docs
- Document `cargo build --release` produces complete binary
- Ensure build works from clean checkout

## Acceptance Criteria
- [x] `cargo build --release` produces working binary with embedded frontend
- [x] Binary serves frontend at `/`
- [x] API still accessible at `/api/*`
- [x] SPA routing works (deep links like `/apps/com.example` load correctly)
- [x] Correct Content-Type headers for all file types
