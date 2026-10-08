#!/usr/bin/env bash
set -euo pipefail
store_root=$(cd "$(dirname "$0")/.." && pwd)
paravoid_repo=${1:-${PARAVOID_SOURCE_DIR:-}}
if [[ -z "$paravoid_repo" ]]; then
  echo "Pass a Paravoid source checkout as the first argument or set PARAVOID_SOURCE_DIR." >&2
  exit 2
fi
paravoid_revision=${PARAVOID_REVISION:-32461c8336325c3381e089193ed77246c5fb90a0}
interop_temp=$(mktemp -d /tmp/lellostore-interop.XXXXXX)
trap 'rm -rf "$interop_temp"' EXIT
# Use committed upstream sources, never consume partially edited runtime files.
git -C "$paravoid_repo" archive "$paravoid_revision" paravoid-contract/src/main/java delivery/src delivery/tools/src | tar -x -C "$interop_temp"
for api in paravoid-recovery-api paravoid-update-api; do
  if git -C "$paravoid_repo" cat-file -e "$paravoid_revision:$api/src/main/java" 2>/dev/null; then
    git -C "$paravoid_repo" archive "$paravoid_revision" "$api/src/main/java" | tar -x -C "$interop_temp"
  fi
done
mkdir -p "$interop_temp/classes"
find "$interop_temp" -name '*.java' > "$interop_temp/sources"
printf '%s\n' "$store_root/scripts/tests/StoreHeadCheck.java" >> "$interop_temp/sources"
javac --release 11 -d "$interop_temp/classes" @"$interop_temp/sources"
PARAVOID_JAVA_CLASSES="$interop_temp/classes" cargo test --manifest-path "$store_root/backend/Cargo.toml" --offline --test paravoid_distribution personalized_acquisition -- --include-ignored
