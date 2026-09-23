#!/usr/bin/env bash
# Uses only a fresh, dedicated emulator. Does not start or wipe devices.
# PARAVOID_STORE_UI=1 also exercises the debug Store and instrumentation APKs.
# That mode removes only the empty canonical fixture installed by the same run.
set -euo pipefail
if [[ $# != 3 ]]; then
  echo "Usage: bash $0 ANDROID_SDK PARAVOID_COMPLETE_FIXTURE EMULATOR_SERIAL" >&2
  exit 2
fi
[[ $3 =~ ^emulator-[0-9]+$ ]] || { echo 'An emulator serial is required' >&2; exit 2; }
store_root=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME=$(realpath "$1")
export PARAVOID_FIXTURE=$(realpath "$2")
export PARAVOID_DEVICE_SERIAL=$3
export APKSIGNER_PATH="$ANDROID_HOME/build-tools/36.0.0/apksigner"
cargo test --manifest-path "$store_root/backend/Cargo.toml" --locked --offline --test e2e_auth paravoid_device -- --ignored --nocapture
