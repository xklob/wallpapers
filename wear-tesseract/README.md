# rgb tesseract // wear os

Standalone always-on Wear OS rave app (`io.klob.wallpapers.watch.tesseract`) that renders a single 16-vertex, 32-edge 4D tesseract rotating in 4D and smoothly morphing through five geometric polytopes (Tesseract, Clifford Torus, Merkaba Star, Hyper-Diamond, and Sacred Vortex) at ~45 FPS with neon RGB glow and optional live microphone bass reactivity.

## Controls

- **Single tap:** cycle display modes (`PURE` // `CLOCK` // `DIM`)
  - `PURE`: full-bleed morphing 4D tesseract on pure black
  - `CLOCK`: morphing 4D tesseract with Space Grotesk time, heart rate, and battery overlay
  - `DIM`: low-brightness morphing tesseract for dark rooms
- **Double tap:** cycle rotation & hue speed (`10S` medium // `4S` fast // `20S` slow)
- **Long press:** toggle **Bass Pulse** (16 kHz watch microphone 35-210 Hz bass detector driving 4D perspective rush, neon bloom flare, scale thump, and hue/rotation surge)
- **Crown / hardware button:** exit back to your watch face

## Install

```sh
adb connect WATCH_IP:DEBUG_PORT
adb -s WATCH_IP:DEBUG_PORT install -r RGB-Tesseract-Pixel-Watch-3.apk
```

Launch **rgb tesseract** from your watch's app list (installs alongside **RGB Honeycomb Live**).

## Build

Requires JDK 11+, Android SDK Platform 36, Build Tools 36.0.0, Python 3 with Pillow, and `zip`:

```sh
bash wear-tesseract/build.sh /path/to/SpaceGrotesk.ttf
```
