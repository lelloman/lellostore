#!/usr/bin/env bash
set -euo pipefail
store_root=$(cd "$(dirname "$0")/.." && pwd)
paravoid_repo=${1:-"$store_root/../paravoid-android"}
interop_temp=$(mktemp -d /tmp/lellostore-vpk-interop.XXXXXX)
trap 'rm -rf "$interop_temp"' EXIT
if [[ ${2:-} == --working-tree ]]; then
  # Explicit development comparison only: copy once, never edit the upstream tree.
  mkdir -p "$interop_temp/src"
  cp -R "$paravoid_repo/paravoid-contract/src/main/java/." "$interop_temp/src/"
  source_root="$interop_temp/src"
  echo 'Testing an uncommitted upstream source snapshot; not a frozen protocol gate.'
else
  git -C "$paravoid_repo" archive HEAD paravoid-contract/src/main/java | tar -x -C "$interop_temp"
  source_root="$interop_temp/paravoid-contract/src/main/java"
fi
find "$source_root" -name '*.java' > "$interop_temp/sources"
printf '%s\n' "$store_root/scripts/tests/StoreVpkCheck.java" >> "$interop_temp/sources"
if [[ -f "$source_root/com/lelloman/paravoidandroid/contract/InstalledPolicyCodec.java" ]]; then
  printf '%s\n' "$store_root/scripts/tests/StorePolicyCheck.java" >> "$interop_temp/sources"
  export PARAVOID_POLICY_JAVA_CLASSES="$interop_temp/classes"
fi
mkdir -p "$interop_temp/classes"
javac --release 11 -d "$interop_temp/classes" @"$interop_temp/sources"
if [[ -n ${PARAVOID_POLICY_JAVA_CLASSES:-} ]]; then
  cargo test --manifest-path "$store_root/backend/Cargo.toml" --offline --test paravoid_shell_policy
fi
if [[ -n ${ANDROID_HOME:-} ]]; then
  bash "$store_root/scripts/tests/build-vpk-components.sh" "$interop_temp/android"
  export PARAVOID_ANDROID_COMPONENTS="$interop_temp/android"
  PARAVOID_VPK_JAVA_CLASSES="$interop_temp/classes" cargo test --manifest-path "$store_root/backend/Cargo.toml" --offline --test paravoid_archive checks_real_android_components -- --ignored
fi
PARAVOID_VPK_JAVA_CLASSES="$interop_temp/classes" cargo test --manifest-path "$store_root/backend/Cargo.toml" --offline --test paravoid_archive checks_component_formats_and_pinned_resource_reservations
