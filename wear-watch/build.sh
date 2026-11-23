#!/usr/bin/env bash
set -euo pipefail

project=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
font=${1:?Pass a path to the Space Grotesk variable font}
output=$(realpath -m "${2:-"$project/build"}")
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
tools=${ANDROID_BUILD_TOOLS:-"${sdk:?Set ANDROID_SDK_ROOT or ANDROID_BUILD_TOOLS}/build-tools/36.0.0"}
platform=${ANDROID_JAR:-"${sdk:?Set ANDROID_SDK_ROOT or ANDROID_JAR}/platforms/android-36/android.jar"}

mkdir -p "$output"
staging=$(mktemp -d "$output/rgb-honeycomb-build.XXXXXXXX")
trap 'rm -rf -- "$staging"' EXIT
cp -R "$project/res" "$staging/"
python3 "$project/prepare_art.py" --font "$font" \
    --input "$project/res/raw/watchface.xml" --res "$staging/res"
"$tools/aapt2" compile --dir "$staging/res" -o "$staging/compiled.zip"
"$tools/aapt2" link -I "$platform" --manifest "$project/AndroidManifest.xml" \
    -o "$staging/unsigned.apk" "$staging/compiled.zip"
"$tools/zipalign" -f 4 "$staging/unsigned.apk" "$output/RGB-Honeycomb-unsigned.apk"
cp "$staging/res/drawable-nodpi/preview.png" "$output/RGB-Honeycomb-preview.png"
