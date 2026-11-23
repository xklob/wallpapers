# rgb poly // wear os

Standalone always-on Wear OS rave app (`io.klob.wallpapers.watch.tesseract`) that renders Poly's exact 6-solid morph loop (**Cube → Cuboctahedron → Octahedron → Icosahedron → Tetrahedron → Truncated tetrahedron → Cube**) with geometric truncation and core-fold transitions, dotted hidden edges, neon glowing front edges, continuous RGB hue rotation, and live microphone bass reactivity at ~45 FPS.

## Controls

- **Single tap:** cycle display modes (`PURE` // `CLOCK` // `DIM`)
  - `PURE`: full-bleed morphing Poly geometry on pure black
  - `CLOCK`: morphing Poly geometry with Space Grotesk time, heart rate, and battery overlay
  - `DIM`: low-brightness morphing Poly visuals for dark rooms
- **Double tap:** cycle speed (`10S` medium // `4S` fast // `20S` slow)
- **Long press:** toggle **Bass Pulse** (16 kHz watch microphone 35-210 Hz bass detector driving scale thump, neon bloom flare, and hue/orbit surge)
- **Crown / hardware button:** exit back to your watch face

## Install

```sh
adb connect WATCH_IP:DEBUG_PORT
adb -s WATCH_IP:DEBUG_PORT install -r RGB-Poly-Pixel-Watch-3.apk
```

Launch **rgb poly** from your watch's app list (installs alongside **RGB Honeycomb Live**).

## Build

Requires JDK 11+, Android SDK Platform 36, Build Tools 36.0.0, Python 3 with Pillow, and `zip`:

```sh
bash wear-tesseract/build.sh /path/to/SpaceGrotesk.ttf
```
