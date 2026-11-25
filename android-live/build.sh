#!/usr/bin/env bash
set -euo pipefail

project=$(cd -- "$(dirname -- "$0")" && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
platform=${ANDROID_JAR:-"${sdk:?Set ANDROID_SDK_ROOT or ANDROID_JAR}/platforms/android-36/android.jar"}
tools=${ANDROID_BUILD_TOOLS:-"${sdk:?Set ANDROID_SDK_ROOT or ANDROID_BUILD_TOOLS}/build-tools/36.0.0"}
build=$(realpath -m -- "${BUILD_DIR:-"$project/build"}")
mkdir -p "$build/generated" "$build/classes" "$build/dex"
# AndroidSVG renders only bundled artwork. Pin the dependency for repeatable builds.
svgjar="${ANDROIDSVG_JAR:-$build/androidsvg-1.4.jar}"
if [[ ! -f "$svgjar" && -z ${ANDROIDSVG_JAR:-} ]]; then
    curl --fail --silent --show-error --location --retry 3 \
        https://repo.maven.apache.org/maven2/com/caverock/androidsvg/1.4/androidsvg-1.4.jar \
        -o "$svgjar.tmp"
    mv "$svgjar.tmp" "$svgjar"
fi
printf '%s  %s\n' 729c67ef475f19f112d01e4fc8e55b1f23841912f8e5090db27992801a04f79d "$svgjar" | sha256sum --check --status
assets=${ART_ASSETS:-"$build/catalog-assets"}

if [[ -z ${ART_ASSETS:-} ]]; then
    staging=$(mktemp -d "$build/catalog-assets.XXXXXX")
    trap 'rm -rf -- "$staging"' EXIT
    python3 "$project/prepare_art.py" \
        "$project/../renders/svg" "$project/../config/camera_profiles_additions.json" "$staging"
    rm -rf -- "$assets"
    mv -- "$staging" "$assets"
fi

"$tools/aapt2" compile --dir "$project/res" -o "$build/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest "$project/AndroidManifest.xml" \
    --rename-manifest-package io.klob.wallpapers.catalog \
    --java "$build/generated" -A "$assets" -o "$build/resources.apk" "$build/resources.zip"

mapfile -t sources < <(find "$project/src" "$build/generated" -name '*.java' -print | sort)
if [[ -n ${ECJ_JAR:-} ]]; then
    java -jar "$ECJ_JAR" -8 -encoding UTF-8 -classpath "$platform:$svgjar" -d "$build/classes" "${sources[@]}"
else
    javac --release 8 -encoding UTF-8 -classpath "$platform:$svgjar" -d "$build/classes" "${sources[@]}"
fi
mapfile -t classes < <(find "$build/classes" -name '*.class' -print | sort)
"$tools/d8" --lib "$platform" --min-api 33 --release --output "$build/dex" "$svgjar" "${classes[@]}"

apk="$build/rgb-geometry-live-unsigned.apk"
cp "$build/resources.apk" "$apk"
zip -q -j "$apk" "$build/dex/classes.dex"
"$tools/zipalign" -f 4 "$apk" "$build/rgb-geometry-live-aligned.apk"
echo "Built $build/rgb-geometry-live-aligned.apk"
