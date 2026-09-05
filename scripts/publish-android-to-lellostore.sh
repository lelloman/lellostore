#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPOSITORY_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
ANDROID_DIR="$REPOSITORY_DIR/android"
SIGNING_PROPERTIES="$ANDROID_DIR/signing.properties"
ARTIFACT="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
PUBLISHER="${LELLOSTORE_PUBLISHER:-$SCRIPT_DIR/publish-to-lellostore.py}"

if [[ ! -f "$SIGNING_PROPERTIES" ]]; then
    echo "Missing Android release signing configuration: $SIGNING_PROPERTIES" >&2
    echo "Copy android/signing.properties.example and configure the release keystore." >&2
    exit 1
fi

if [[ ! -x "$PUBLISHER" ]]; then
    echo "LelloStore publisher is not executable: $PUBLISHER" >&2
    exit 1
fi

echo "Building signed LelloStore release APK (including the bundled recovery companion)..."
(
    cd "$ANDROID_DIR"
    ./gradlew :app:assembleRelease
)

if [[ ! -s "$ARTIFACT" ]]; then
    echo "Expected signed release APK was not produced: $ARTIFACT" >&2
    exit 1
fi

ARTIFACT_SIZE=$(stat --format='%s' "$ARTIFACT")
echo "Artifact: $ARTIFACT"
echo "Variant:  release"
echo "Size:     $ARTIFACT_SIZE bytes"

"$PUBLISHER" upload "$ARTIFACT" "$@"
