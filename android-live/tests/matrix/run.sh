#!/usr/bin/env bash
# Run after android-live/build.sh. Requires Python 3 with resvg-py.
# Set ANDROID_SERIAL to an API 34+ emulator or device and ANDROID_SDK_ROOT to its SDK.
set -euo pipefail
here=$(cd -- "$(dirname -- "$0")" && pwd)
project=$(cd "$here/../.." && pwd)
appbuild=${BUILD_DIR:-"$project/build"}
out="$appbuild/matrix"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_SDK_ROOT}}
bt=${ANDROID_BUILD_TOOLS:-"$sdk/build-tools/36.0.0"}
platform=${ANDROID_JAR:-"$sdk/platforms/android-36/android.jar"}
assets=${ART_ASSETS:-"$appbuild/catalog-assets"}
adb="$sdk/platform-tools/adb"
mkdir -p "$out/classes" "$out/dex"
zoom=$("$adb" -s "${ANDROID_SERIAL:?Select a test target}" shell cmd overlay lookup android android:dimen/config_wallpaperMaxScale | tr -d '\r')
"${PYTHON:-python3}" "$here/references.py" "$out/assets" "$zoom"
"$bt/aapt2" link -I "$platform" --manifest "$here/AndroidManifest.xml" \
    -A "$assets" -A "$out/assets" -o "$out/test.apk"
javac --release 8 -cp "$platform:$appbuild/classes" -d "$out/classes" "$here"/*.java
mapfile -t classes < <(find "$out/classes" -name '*.class')
"$bt/d8" --lib "$platform" --classpath "$appbuild/classes" --min-api 34 --output "$out/dex" "${classes[@]}"
cp "$out/dex/classes.dex" "$out/classes2.dex"
zip -q -j "$out/test.apk" "$appbuild/dex/classes.dex" "$out/classes2.dex"
"$bt/zipalign" -f 4 "$out/test.apk" "$out/test-aligned.apk"
if [[ ! -f "$out/test.keystore" ]]; then
    keytool -genkeypair -keystore "$out/test.keystore" -storepass android -keypass android \
        -alias matrix -dname 'CN=Wallpaper matrix tests' -keyalg RSA -validity 3650 >/dev/null
fi
"$bt/apksigner" sign --ks "$out/test.keystore" --ks-pass pass:android --out "$out/test-signed.apk" "$out/test-aligned.apk"
"$adb" -s "${ANDROID_SERIAL:?Select a test target}" install --no-incremental -r "$out/test-signed.apk"
"$adb" -s "$ANDROID_SERIAL" shell am instrument -w io.klob.wallpapers.matrix/io.klob.wallpapers.live.RenderMatrix | tee "$out/result.txt"
grep -Eq '[1-9][0-9]* compared frames, 0 failures' "$out/result.txt"
