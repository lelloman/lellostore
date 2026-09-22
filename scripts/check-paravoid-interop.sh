#!/usr/bin/env bash
set -euo pipefail
store_root=$(cd "$(dirname "$0")/.." && pwd)
paravoid_repo=${1:-"$store_root/../paravoid-android"}
interop_temp=$(mktemp -d /tmp/lellostore-interop.XXXXXX)
trap 'rm -rf "$interop_temp"' EXIT
# Use committed upstream sources, never consume partially edited runtime files.
git -C "$paravoid_repo" archive HEAD paravoid-contract/src/main/java delivery/src delivery/tools/src | tar -x -C "$interop_temp"
mkdir -p "$interop_temp/classes"
find "$interop_temp/paravoid-contract/src/main/java" "$interop_temp/delivery/src" "$interop_temp/delivery/tools/src" -name '*.java' > "$interop_temp/sources"
printf '%s\n' "$store_root/scripts/tests/StoreHeadCheck.java" >> "$interop_temp/sources"
javac --release 11 -d "$interop_temp/classes" @"$interop_temp/sources"
PARAVOID_JAVA_CLASSES="$interop_temp/classes" cargo test --manifest-path "$store_root/backend/Cargo.toml" --offline --test paravoid_distribution personalized_acquisition -- --include-ignored
