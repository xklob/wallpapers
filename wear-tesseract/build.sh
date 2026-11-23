#!/usr/bin/env bash
set -euo pipefail

project=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
font=${1:?Pass a path to the Space Grotesk variable font}
output=$(realpath -m "${2:-"$project/build"}")
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
tools=${ANDROID_BUILD_TOOLS:-"${sdk:?Set ANDROID_SDK_ROOT or ANDROID_BUILD_TOOLS}/build-tools/36.0.0"}
platform=${ANDROID_JAR:-"${sdk:?Set ANDROID_SDK_ROOT or ANDROID_JAR}/platforms/android-36/android.jar"}

mkdir -p "$output"
staging=$(mktemp -d "$output/rgb-tesseract-build.XXXXXXXX")
trap 'rm -rf -- "$staging"' EXIT

cp -R "$project/res" "$staging/"
mkdir -p "$staging/assets" "$staging/generated" "$staging/classes" "$staging/dex"

python3 "$project/prepare_assets.py" --font "$font" \
    --res "$staging/res" --assets "$staging/assets"

"$tools/aapt2" compile --dir "$staging/res" -o "$staging/compiled.zip"
"$tools/aapt2" link -I "$platform" --manifest "$project/AndroidManifest.xml" \
    --java "$staging/generated" -A "$staging/assets" \
    -o "$staging/resources.apk" "$staging/compiled.zip"

mapfile -t sources < <(find "$project/src" "$staging/generated" -name '*.java' -print | sort)
javac --release 11 -encoding UTF-8 -classpath "$platform" -d "$staging/classes" "${sources[@]}"

mapfile -t classes < <(find "$staging/classes" -name '*.class' -print | sort)
"$tools/d8" --lib "$platform" --min-api 33 --release --output "$staging/dex" "${classes[@]}"

cp "$staging/resources.apk" "$staging/unsigned.apk"
zip -q -j "$staging/unsigned.apk" "$staging/dex/classes.dex"
"$tools/zipalign" -f 4 "$staging/unsigned.apk" "$output/RGB-Tesseract-unsigned.apk"
echo "Built $output/RGB-Tesseract-unsigned.apk"
