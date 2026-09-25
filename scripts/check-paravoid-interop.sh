#!/usr/bin/env bash
set -euo pipefail
store_root=$(cd "$(dirname "$0")/.." && pwd)
paravoid_repo=${1:-"$store_root/../paravoid-android"}
interop_temp=$(mktemp -d /tmp/lellostore-interop.XXXXXX)
trap 'rm -rf "$interop_temp"' EXIT
# Use committed upstream sources, never consume partially edited runtime files.
git -C "$paravoid_repo" archive HEAD paravoid-contract/src/main/java delivery/src delivery/tools/src | tar -x -C "$interop_temp"
for api in paravoid-recovery-api paravoid-update-api; do
  if git -C "$paravoid_repo" cat-file -e "HEAD:$api/src/main/java" 2>/dev/null; then
    git -C "$paravoid_repo" archive HEAD "$api/src/main/java" | tar -x -C "$interop_temp"
  fi
done
mkdir -p "$interop_temp/classes"
find "$interop_temp" -name '*.java' > "$interop_temp/sources"
printf '%s\n' "$store_root/scripts/tests/StoreHeadCheck.java" >> "$interop_temp/sources"
javac --release 11 -d "$interop_temp/classes" @"$interop_temp/sources"
PARAVOID_JAVA_CLASSES="$interop_temp/classes" cargo test --manifest-path "$store_root/backend/Cargo.toml" --offline --test paravoid_distribution personalized_acquisition -- --include-ignored
