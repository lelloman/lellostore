#!/usr/bin/env bash
# Produce real D8/AAPT2 content in a caller-owned temporary directory, not an app release.
set -euo pipefail
output=$1
sdk=${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with build-tools 36.0.0 and platform 36}
build_tools="$sdk/build-tools/36.0.0"
mkdir -p "$output/res/values" "$output/classes" "$output/dex" "$output/java/example"
cat > "$output/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/><application android:label="@string/title"/></manifest>
MANIFEST
cat > "$output/res/values/strings.xml" <<'RESOURCES'
<resources><string name="title">Store VPK interoperability fixture</string></resources>
RESOURCES
cat > "$output/java/example/Payload.java" <<'JAVA'
package example;
public final class Payload { public static String title() { return "Store VPK interoperability fixture"; } }
JAVA
javac --release 8 -d "$output/classes" "$output/java/example/Payload.java"
"$build_tools/d8" --lib "$sdk/platforms/android-36/android.jar" --min-api 30 --output "$output/dex" "$output/classes/example/Payload.class"
"$build_tools/aapt2" compile --dir "$output/res" -o "$output/compiled.zip"
"$build_tools/aapt2" link -I "$sdk/platforms/android-36/android.jar" --manifest "$output/AndroidManifest.xml" --output-text-symbols "$output/R.txt" -o "$output/resources.apk" "$output/compiled.zip"
python3 - "$output" <<'PY'
import json, pathlib, sys, zipfile
root = pathlib.Path(sys.argv[1])
# Keep AAPT2 output intact, including Android local alignment padding.
entries = []
for line in (root / 'R.txt').read_text().splitlines():
    fields = line.split()
    if len(fields) == 4 and fields[0] == 'int' and fields[3].startswith('0x7f'):
        entries.append({'name': fields[1] + '/' + fields[2], 'id': fields[3], 'removed': False})
(root / 'resource-ledger.json').write_text(json.dumps({'version': 1, 'applicationId': 'example.app', 'entries': entries}))
with zipfile.ZipFile(root / 'java-resources.jar', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    archive.writestr('fixture.txt', 'Non-executable Java resource')
PY
