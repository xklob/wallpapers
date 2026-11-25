# rgb geometry live for Android

The Android 13+ app includes the same three patterns and 603 selectable model and screen profiles as [wallpapers.klob.io](https://wallpapers.klob.io/). The catalog includes separate foldable panels and orientations, tablets, and desktop sizes. Every profile is available offline. Wallpaper installation uses Android's wallpaper services.

Open **rgb geometry live**, choose **Home screen** or **Lock screen**, then tap **Model** to search by name, manufacturer, or resolution. Select the entry for the panel and orientation you want to use. On a fresh install, the app tries to match the hardware model and screen. If Android reports an unfamiliar product code, it suggests a size and leaves hardware-specific outlines off until you choose them. Choose a pattern and toggle **RGB border**. Camera rings appear on supported models. Pixel 11 Pro XL supports status bar and home row outlines. Pixel Tablet has separate landscape and portrait home row layouts. Choose **Home screen** to enable the home row. The Lock preview and applied Lock wallpaper use the Lock layout. Version 1.2.3 updates 1.2.2 in place. Home and Lock selections of the retired honeycomb variant switch to Bright Honeycomb.

- **Apply live wallpaper** opens Android’s live wallpaper preview. Confirm the destination in the system picker. A shared wallpaper switches between your Home and Lock layouts as the device locks and unlocks. Audio and animation settings are shared.
- **Set home wallpaper** or **Set lock wallpaper** applies a still image to the selected destination. The app renders it at the current display dimensions. The app keeps the original colors and accounts for Android’s wallpaper zoom so the frame and model-specific outlines stay visible.

The live wallpaper renders the selected vector artwork for the surface size and animates its colors at about 30 FPS while visible. Turning off **Animate colors** restores the original colors. Camera coordinates marked estimated in the site catalog and the Pixel Launcher outlines can vary with hardware cutouts, launcher zoom, grid and display settings. On a matching Android model and panel, RGB frames with estimated curves use the four corner centers and radii reported by Android. Published panel outlines take precedence. Models without usable corner data retain their catalog geometry. OEM model codes come from [Google Play’s supported devices list](https://storage.googleapis.com/play_public/supported_devices.csv).

## Audio Sync

With **Animate colors** on, enable **Audio Sync** and grant Android's audio permission. **Device audio** uses Android's output-mix FFT. **Microphone** follows ambient sound. Tap **Start microphone** in the app to arm that session and **Stop microphone** to end it. The microphone is released while the wallpaper and preview are hidden, and the session never saves audio. Silence fades back to the normal animation. Audio Sync is off by default.

**Auto (device audio first)** monitors playback while the wallpaper or preview is visible. Tap **Start microphone fallback** to allow room sound after about 1.25 seconds of quiet. Playback takes priority as soon as it returns, and the microphone recorder closes during playback. If Android reports active playback without a usable signal, the animation stays ambient until capture is available or playback pauses.

Look for **Device audio detected** or **Microphone audio detected** in the app, then open **Tune audio effects**. Choose Pulse + color, Brightness pulse, Color acceleration, or Diagonal color wave. Intensity sets the effect strength, Smoothness sets its rise and decay, and Bass sensitivity sets how readily a kick triggers it. Changes apply to the preview and active wallpaper. Reset affects only the tuner.

Output-mix capture was confirmed on a Pixel over standard playback and Bluetooth. The microphone mode and lock-screen capture still need testing on the device.

## Build

From the repository root, install a JDK, Android SDK Platform 36 and Build Tools 36.0.0, Python 3, curl, and zip. Set `ANDROID_SDK_ROOT` to your SDK path and run:

```sh
bash android-live/build.sh
```

The build packages the checked-in SVGs and resolves the website catalog at build time. It downloads the checksum-pinned AndroidSVG dependency on the first build. Set `ANDROIDSVG_JAR` to an existing copy for an offline build. The build produces `android-live/build/rgb-geometry-live-aligned.apk`. The release application ID is `io.klob.wallpapers.catalog`. Sign the aligned APK with Android SDK's `apksigner` and your own keystore before installing. Keep the same signing key for future app updates.

## Verify the catalog and app

From the repository root, check every local PNG and SVG against the catalog dimensions and compare their visible artwork.

```sh
uv run --script src/validate_catalog_art.py --report build/qa/regular-art.csv
```

After building the app, run the Android rendering checks with an API 34+ test target selected in `ANDROID_SERIAL`. The script uses Python with resvg-py and runs in a separate test package.

```sh
bash android-live/tests/matrix/run.sh
```

To test Android’s system picker, live service and still wallpaper setter on a disposable emulator, follow the [device test instructions](tests/device/README.md).
