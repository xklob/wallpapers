#!/usr/bin/env bash
# Build with the app's debug-signed APK installed. APP_CLASSES points to its javac output.
set -euo pipefail
project=$(cd -- "$(dirname -- "$0")" && pwd)
sdk=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-36/android.jar"
build=${DEVICE_TEST_BUILD:-"$project/../../build/apply-test"}
classes=${APP_CLASSES:-"$project/../../build/classes"}
mkdir -p "$build/classes" "$build/dex"
"$tools/aapt2" link --manifest "$project/AndroidManifest.xml" -I "$platform" -o "$build/test.apk"
javac --release 8 -classpath "$platform:$classes" -d "$build/classes" "$project"/*.java
mapfile -t compiled < <(find "$build/classes" -name '*.class')
"$tools/d8" --release --min-api 33 --lib "$platform" --classpath "$classes" --output "$build/dex" "${compiled[@]}"
zip -q -j "$build/test.apk" "$build/dex/classes.dex"
"$tools/zipalign" -f 4 "$build/test.apk" "$build/test-aligned.apk"
"$tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --ks-key-alias androiddebugkey --out "$build/test-signed.apk" "$build/test-aligned.apk"
echo "$build/test-signed.apk"
