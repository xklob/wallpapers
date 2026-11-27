# rgb geometry live for Android

The Android 13+ app includes **Mandelbrot Zoom** and the three RGB patterns from [wallpapers.klob.io](https://wallpapers.klob.io/). All four work offline with the 603 model and screen profiles, including foldable panels, tablets, and desktop sizes.

**Mandelbrot Zoom** starts on the full black silhouette with a cobalt field and luminous edges, then dives into a real miniature Mandelbrot set. At 1×, the zoom loops every 32 seconds. It offers 0.25× to 4× speed and 15, 30, or 60 fps caps. Actual frame rate depends on the device. Mandelbrot offers **Balanced** half-resolution textures, **High** full-resolution textures, and **Ultra** full-resolution textures with four-sample antialiasing. The outlines stay in place as you zoom. The regular wallpaper button saves a still view.

Open **rgb geometry live** and choose **Home** or **Lock** in the pinned preview panel. Tap **Enlarge** for a larger preview. Under **Wallpaper**, tap **Model** to search by name, manufacturer, or resolution. Select the entry for the panel and orientation you want to use. On a fresh install, the app tries to match the hardware model and screen. If Android reports an unfamiliar product code, it suggests a size and leaves hardware-specific outlines off until you choose them. Choose a pattern and toggle **RGB border**. Camera rings appear on supported models. Pixel 11 Pro XL supports status bar and home row outlines. Pixel Tablet has separate landscape and portrait home row layouts. Choose **Home screen** to enable the home row. The Lock preview and applied Lock wallpaper use the Lock layout. Use **Rotation** to turn any pattern clockwise from 0° to 360°. **Framing zoom** ranges from 0.25× to 4×, with 1× as the original fit. Zoom out to reveal more cells in Bright Honeycomb, Spaced Hexagons, and Wireframe Cubes. It works on live and still wallpapers and does not change the fractal’s animation speed or move the outlines. Home and Lock remember separate rotation and zoom settings. The artwork is cropped to fill the screen, while the RGB border, camera ring, and system UI outlines stay in place. The settings screen follows Android’s theme unless you pick Light or Dark. Home and Lock selections of the retired honeycomb variant switch to Bright Honeycomb. Saved Menger selections switch to Mandelbrot Zoom, keeping their framing, colors, and animation speed.

- **Preview Home live** and **Preview Lock live** open the selected layout in Android’s live preview. Choose the final destination in the system picker. Each screen uses its own saved layout. Some Android 13 pickers offer Home and both screens, with no Lock-only live option. A shared wallpaper switches between your Home and Lock layouts as the device locks and unlocks. Audio, color animation, and fractal performance settings are shared. Editing an already-active live layout updates it immediately.
- **Set Home still** or **Set Lock still** applies a still image to the selected destination. The app renders it at the current display dimensions. The app keeps the original colors and accounts for Android’s wallpaper zoom so the frame and model-specific outlines stay visible.

In **Color Adjustments**, set **Brightness**, **Contrast**, and **Saturation** from 0% to 200%. The original colors are 100% on each slider. Home and Lock save separate values across all four patterns. Adjustments affect the complete wallpaper, including its outlines, and work with rotation and color animation. **Reset color adjustments** restores 100% for the screen you’re editing. Existing selections keep their colors and framing until you change them.

The live wallpaper fits the artwork to its surface and animates only while visible. Bright Honeycomb uses a connected grid with one stroke per shared edge. Color travels through its lines and branches. In Spaced Hexagons and Wireframe Cubes, color travels clockwise around each hexagon. **Cycle duration** sets the time for a full color cycle. Turning off **Animate colors** restores the original colors. Camera coordinates marked estimated in the site catalog and the Pixel Launcher outlines can vary with hardware cutouts, launcher zoom, grid and display settings. On a matching Android model and panel, RGB frames with estimated curves use the four corner centers and radii reported by Android. Published panel outlines take precedence. Models without usable corner data retain their catalog geometry. OEM model codes come from [Google Play’s supported devices list](https://storage.googleapis.com/play_public/supported_devices.csv).

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

Version 1.10.0 starts using a new release signing key. When updating from 1.9.0 or earlier, uninstall the old app before installing this release. Reinstalling resets saved app settings. Subsequent releases will use the new key for normal updates.

The Mandelbrot artwork can be regenerated with Python, NumPy and Pillow via `python3 android-live/generate_mandelbrot.py`. It packs overlapping tiles from the actual set using 520–856 escape iterations. With FFmpeg installed, `python3 android-live/render_mandelbrot_preview.py` recreates the gallery poster and video from the same tiles.

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
