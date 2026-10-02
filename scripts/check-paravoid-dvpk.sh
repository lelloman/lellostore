#!/usr/bin/env bash
# DVPK interoperability gate: Store generation with the vendored reference
# encoder, reconstruction by the upstream Java shell decoder, and signed head
# readback by both the DVPK-capable and the previous (full-only) verifier.
set -euo pipefail
store_root=$(cd "$(dirname "$0")/.." && pwd)
paravoid_repo=${1:-"$store_root/../paravoid-android"}
# Paravoid commit "Add DVPK shell delivery and APK-only distributor specification".
dvpk_commit=33340c6
interop_temp=$(mktemp -d /tmp/lellostore-dvpk-interop.XXXXXX)
trap 'rm -rf "$interop_temp"' EXIT
git -C "$paravoid_repo" merge-base --is-ancestor "$dvpk_commit" HEAD
# The vendored encoder must be the pinned upstream file, unchanged.
git -C "$paravoid_repo" show "$dvpk_commit:delivery/tools/dvpk.py" | cmp - "$store_root/scripts/vendor/paravoid/dvpk.py"
compile() {
  local revision=$1 destination=$2
  mkdir -p "$interop_temp/$destination/src" "$interop_temp/$destination/classes"
  git -C "$paravoid_repo" archive "$revision" paravoid-contract/src/main/java | tar -x -C "$interop_temp/$destination/src"
  find "$interop_temp/$destination/src" -name '*.java' > "$interop_temp/$destination/sources"
  shift 2
  printf '%s\n' "$@" >> "$interop_temp/$destination/sources"
  javac --release 11 -d "$interop_temp/$destination/classes" @"$interop_temp/$destination/sources"
}
compile "$dvpk_commit" current "$store_root/scripts/tests/StoreDvpkCheck.java" "$store_root/scripts/tests/StoreHeadCheck.java"
compile "$dvpk_commit^" legacy "$store_root/scripts/tests/StoreHeadCheck.java"
python=${PARAVOID_DVPK_PYTHON:-}
if [[ -z $python ]]; then
  python3 -m venv "$interop_temp/venv"
  "$interop_temp/venv/bin/pip" install --quiet bsdiff4==1.2.6
  python="$interop_temp/venv/bin/python"
fi
"$python" -c 'import bsdiff4' 
# The upstream encoder's own self-test, then the Store suite.
PYTHONPATH="$paravoid_repo/delivery/tools" "$python" "$paravoid_repo/delivery/tools/test_dvpk.py" || {
  echo 'Upstream reference encoder self-test failed' >&2; exit 1; }
PARAVOID_DVPK_PYTHON="$python" \
PARAVOID_DVPK_JAVA_CLASSES="$interop_temp/current/classes" \
PARAVOID_DVPK_LEGACY_JAVA_CLASSES="$interop_temp/legacy/classes" \
  cargo test --manifest-path "$store_root/backend/Cargo.toml" --offline --test paravoid_dvpk -- --include-ignored
