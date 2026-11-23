#!/usr/bin/env bash
set -euo pipefail

project=$(cd -- "$(dirname -- "$0")" && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
platform=${ANDROID_JAR:-"${sdk:?Set ANDROID_SDK_ROOT or ANDROID_JAR}/platforms/android-36/android.jar"}
tools=${ANDROID_BUILD_TOOLS:-"${sdk:?Set ANDROID_SDK_ROOT or ANDROID_BUILD_TOOLS}/build-tools/36.0.0"}
build=$(realpath -m -- "${BUILD_DIR:-"$project/build"}")
mkdir -p "$build/generated" "$build/classes" "$build/dex"
assets=${ART_ASSETS:-"$build/assets"}

if [[ -z ${ART_ASSETS:-} ]]; then
    python3 "$project/prepare_art.py" \
        "$project/../renders/svg" "$project/../config/camera_profiles_additions.json" "$assets"
fi

"$tools/aapt2" compile --dir "$project/res" -o "$build/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest "$project/AndroidManifest.xml" \
    --java "$build/generated" -A "$assets" -o "$build/resources.apk" "$build/resources.zip"

mapfile -t sources < <(find "$project/src" "$build/generated" -name '*.java' -print | sort)
if [[ -n ${ECJ_JAR:-} ]]; then
    java -jar "$ECJ_JAR" -8 -encoding UTF-8 -classpath "$platform" -d "$build/classes" "${sources[@]}"
else
    javac --release 8 -encoding UTF-8 -classpath "$platform" -d "$build/classes" "${sources[@]}"
fi
mapfile -t classes < <(find "$build/classes" -name '*.class' -print | sort)
"$tools/d8" --lib "$platform" --min-api 33 --release --output "$build/dex" "${classes[@]}"

apk="$build/rgb-geometry-live-unsigned.apk"
cp "$build/resources.apk" "$apk"
zip -q -j "$apk" "$build/dex/classes.dex"
"$tools/zipalign" -f 4 "$apk" "$build/rgb-geometry-live-aligned.apk"
echo "Built $build/rgb-geometry-live-aligned.apk"
