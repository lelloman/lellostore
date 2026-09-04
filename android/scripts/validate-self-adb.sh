#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 <legacy|tls> <fixture.apk> [adb-serial]" >&2
    exit 2
fi

transport=$1
fixture_apk=$2
device_serial=${3:-}
instrument_timeout=${SELF_ADB_TEST_TIMEOUT_SECONDS:-90}

if [[ $transport != legacy && $transport != tls ]]; then
    echo "Transport must be 'legacy' or 'tls'." >&2
    exit 2
fi
if [[ ! -f $fixture_apk ]]; then
    echo "Fixture APK not found: $fixture_apk" >&2
    exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
android_dir=$(cd -- "$script_dir/.." && pwd)
aapt2_bin=${AAPT2_PATH:-}
if [[ -z $aapt2_bin ]]; then
    sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    if [[ -z $sdk_root ]]; then
        echo "Set ANDROID_HOME, ANDROID_SDK_ROOT, or AAPT2_PATH." >&2
        exit 2
    fi
    aapt2_bin=$(find "$sdk_root/build-tools" -name aapt2 -type f | sort -V | tail -1)
fi

badging=$($aapt2_bin dump badging "$fixture_apk")
badging=${badging%%$'\n'*}
package_name=$(sed -n "s/.*package: name='\([^']*\)'.*/\1/p" <<<"$badging")
version_code=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")
if [[ -z $package_name || -z $version_code ]]; then
    echo "Could not read package metadata from $fixture_apk" >&2
    exit 2
fi

adb_args=()
if [[ -n $device_serial ]]; then
    adb_args=(-s "$device_serial")
fi

cd "$android_dir"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest

adb "${adb_args[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
adb "${adb_args[@]}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb "${adb_args[@]}" push "$fixture_apk" /data/local/tmp/lellostore-self-adb-validation.apk
adb "${adb_args[@]}" shell run-as com.lelloman.store.debug mkdir -p files
adb "${adb_args[@]}" shell run-as com.lelloman.store.debug \
    cp /data/local/tmp/lellostore-self-adb-validation.apk files/self-adb-validation.apk

if ! instrument_output=$(timeout --foreground "${instrument_timeout}s" \
    adb "${adb_args[@]}" shell am instrument -w \
        -e class com.lelloman.store.installation.SelfAdbInstallationDeviceTest \
        -e selfAdbValidation true \
        -e selfAdbTransport "$transport" \
        -e selfAdbPackage "$package_name" \
        -e selfAdbVersionCode "$version_code" \
        com.lelloman.store.debug.test/com.lelloman.store.HiltTestRunner); then
    echo "Self-ADB validation did not finish within ${instrument_timeout}s" >&2
    exit 1
fi
printf '%s\n' "$instrument_output"
if ! rg -q '^OK \(1 test\)$' <<<"$instrument_output"; then
    exit 1
fi

adb "${adb_args[@]}" logcat -d -s SelfAdbValidation:I '*:S' | tail -1
