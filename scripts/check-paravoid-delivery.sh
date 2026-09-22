#!/usr/bin/env bash
# Signed Store delivery gate; does not install apps or claim Android runtime acceptance.
set -euo pipefail
store_root=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME=${1:-${ANDROID_HOME:?Pass an Android SDK path or set ANDROID_HOME}}
export APKSIGNER_PATH="$ANDROID_HOME/build-tools/36.0.0/apksigner"
for executable in "$APKSIGNER_PATH" "$ANDROID_HOME/build-tools/36.0.0/aapt2" "$ANDROID_HOME/build-tools/36.0.0/d8"; do
  [[ -x "$executable" ]] || { echo "Missing Android tool: $executable" >&2; exit 1; }
done
[[ -f "$ANDROID_HOME/platforms/android-36/android.jar" ]] || { echo 'Android platform 36 is required' >&2; exit 1; }
for command in cargo java javac keytool openssl python3; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
cargo test --manifest-path "$store_root/backend/Cargo.toml" --all-features --locked --offline --test shell_registration -- --ignored
cargo test --manifest-path "$store_root/backend/Cargo.toml" --all-features --locked --offline --test paravoid_distribution -- --ignored
cargo test --manifest-path "$store_root/backend/Cargo.toml" --all-features --locked --offline --test e2e_auth paravoid_http -- --ignored
