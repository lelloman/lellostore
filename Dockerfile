# LelloStore Dockerfile
# Multi-stage build: Frontend -> Backend -> Runtime

# =============================================================================
# Frontend Builder Stage
# =============================================================================
FROM node:22-bookworm-slim AS frontend-builder

# Build-time args for Vite (baked into the JS bundle)
ARG VITE_OIDC_ISSUER_URL
ARG VITE_OIDC_CLIENT_ID
ARG VITE_API_BASE_URL

WORKDIR /app/frontend

# Install dependencies first (for caching)
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

# Copy frontend source and build
COPY frontend/ ./
RUN VITE_OIDC_ISSUER_URL="$VITE_OIDC_ISSUER_URL" \
    VITE_OIDC_CLIENT_ID="$VITE_OIDC_CLIENT_ID" \
    VITE_API_BASE_URL="$VITE_API_BASE_URL" \
    npm run build

# =============================================================================
# Backend Builder Stage
# =============================================================================
FROM rust:1.85-bookworm AS backend-builder

WORKDIR /app

# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Cache dependencies by building with empty source first
COPY backend/Cargo.toml backend/Cargo.lock ./

# Create dummy source files for dependency caching
RUN mkdir -p src/bin && \
    echo "fn main() {}" > src/main.rs && \
    echo "fn main() {}" > src/bin/mock_oidc.rs

# Build dependencies only (this layer is cached)
RUN cargo build --release && rm -rf src

# Copy frontend build output (required for rust-embed)
COPY --from=frontend-builder /app/frontend/dist ../frontend/dist

# Copy actual source code
COPY backend/src src
COPY backend/migrations migrations
COPY backend/tests tests

# Touch main.rs to ensure it's rebuilt
RUN touch src/main.rs

# Build the actual application
RUN cargo build --release

# =============================================================================
# Runtime Stage
# =============================================================================
FROM debian:bookworm-slim

# Install runtime dependencies and aapt2
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    aapt \
    default-jre-headless \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Download bundletool for AAB support
ARG BUNDLETOOL_VERSION=1.17.2
RUN curl -L -o /usr/local/lib/bundletool.jar \
    "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar"

# Create non-root user for security
RUN useradd -r -s /bin/false -d /app lellostore

# Create directories
RUN mkdir -p /app/data && \
    chown -R lellostore:lellostore /app

WORKDIR /app

# Copy the binary from builder
COPY --from=backend-builder /app/target/release/lellostore-backend /usr/local/bin/lellostore

# Switch to non-root user
USER lellostore

# Expose default port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Default command
ENTRYPOINT ["/usr/local/bin/lellostore"]
