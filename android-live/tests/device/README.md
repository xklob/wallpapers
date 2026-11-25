# Android system wallpaper tests

Use a disposable Android 13+ emulator. These tests replace its actual Home wallpaper and change the app's preferences. Build the app and sign the app and test APK with the same Android debug key.

```sh
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_SERIAL=emulator-5554
bash android-live/build.sh
"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" sign \
  --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey \
  --out android-live/build/app-debug.apk android-live/build/rgb-geometry-live-aligned.apk
bash android-live/tests/device/build.sh
adb install -r android-live/build/app-debug.apk
adb install -r android-live/build/apply-test/test-signed.apk
```

Choose **APPLY LIVE WALLPAPER** in the app and confirm Home in Android's wallpaper picker. On Android 13, instrumentation startup can detach the service. Pass `-e waitPicker true` to the live run and confirm Home in the picker when it opens during startup. Dismiss Android's first-use immersive-mode tutorial if it appears. The test activity shows the wallpaper with the system bars and launcher controls hidden. Set a compact display override to speed up the exhaustive runs.

```sh
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell wm size 300x668
adb shell wm density 150
adb shell am instrument -w -e mode live \
  io.klob.wallpapers.applytest/io.klob.wallpapers.live.ApplyMatrix
adb shell am instrument -w -e mode static \
  io.klob.wallpapers.applytest/io.klob.wallpapers.live.ApplyMatrix
```

The live run changes the active service's preferences and compares Android screenshots with reference frames at the emulator's current display size. The static run calls the same setter as the app button, checks the committed wallpaper ID, and compares the unobstructed Android screenshot against the intended on-screen geometry. Static comparisons average local blocks of pixels to allow for Android's resampling. Both deduplicate aliases sharing the same artwork path. Pass `-e matchShape true` to resize the emulator to each model’s aspect ratio with a 240 px short edge before comparing screenshots. Use `-e start N -e end M` to run a half-open subrange. Success returns `INSTRUMENTATION_CODE: -1`. Android 16 caches the physical display sizes used to calculate its static wallpaper crop. A logical `wm size` override that changes the aspect ratio can leave the OS cropping to the old panel. To verify static fit on a particular profile, cold boot an AVD whose physical display aspect matches it, verify `adb shell wm size`, and run the matching cases. See the sibling render matrix for comparisons against the website artwork at each model's aspect ratio.

```sh
adb shell wm size reset
adb shell wm density reset
```

For example, boot a disposable AVD with physical dimensions 240 × 643. The shipped catalog's 112 × 300 portrait cover group scales to this panel. Confirm `Physical size: 240x643` before running its eight cases.

```sh
adb shell wm size
adb shell am instrument -w -e mode static -e matchShape true -e start 0 -e end 8 \
  io.klob.wallpapers.applytest/io.klob.wallpapers.live.ApplyMatrix
```

Sorting for `matchShape` groups cases by catalog width and then height. Group indices apply to the current catalog. For another example, a physical 240 × 249 panel covers both ranges `[80, 88)` (720 × 748) and `[1832, 1864)` (2076 × 2152).

Use a stable matching display before binding the live wallpaper on Android 13. Changing only the logical size while its engine is attached can leave the inherited hardware surface at the previous size on the emulator. Screenshots after a resize exercise that transition as well as the selected artwork. A full run at one fixed panel size exercises every selection through the same Android service and compositor.
