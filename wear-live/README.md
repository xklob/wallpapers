# RGB Honeycomb Live for Wear OS

Always-on interactive Wear OS companion app that bypasses the 1-redraw-per-minute ambient watch face limit by keeping the screen awake (`FLAG_KEEP_SCREEN_ON`) with a real-time 30 FPS AGSL hue-rotation shader.

## Controls

- **Single tap:** cycle display modes (`FULL` // `MINIMAL` // `DIM`)
  - `FULL`: full honeycomb dial, outer ring, date, time, live heart rate, and battery in Space Grotesk
  - `MINIMAL`: RGB hue-rotating time only on pure black, with subtle burn-in pixel drift
  - `DIM`: low-brightness RGB hue-rotating time for dark rooms and lower battery draw
- **Double tap:** cycle hue speed (`10S` medium // `4S` fast // `20S` slow)
- **Crown / hardware button:** exit back to your watch face (accidental swipe-to-dismiss is disabled)

## Install

```sh
adb connect WATCH_IP:DEBUG_PORT
adb -s WATCH_IP:DEBUG_PORT install -r RGB-Honeycomb-Live-Pixel-Watch-3.apk
```

Launch **RGB Honeycomb Live** from your watch's app drawer.

## Build

Requires a JDK 11+, Android SDK Platform 36, Build Tools 36.0.0, Python 3 with Pillow and NumPy, and `zip`:

```sh
bash wear-live/build.sh /path/to/SpaceGrotesk.ttf
```

Sign `wear-live/build/RGB-Honeycomb-Live-unsigned.apk` with `apksigner` before installing.
