# klob // wallpapers

Browse [wallpapers.klob.io](https://wallpapers.klob.io/) and download the [PNG or SVG packs](https://github.com/xklob/wallpapers/releases/tag/v1.2.9).

RGB Geometry includes Bright Honeycomb, Spaced Hexagons, and Wireframe Cubes. The site previews each pattern in an expandable gallery and filters available sizes by device type, manufacturer, and model.

The flagship index covers 370 named phone and foldable models from 2020 through November 2026. Each named panel has a manufacturer source for its native pixel dimensions. Models can share artwork when their dimensions and frame treatment match. [Device support and display specifications](docs/flagship-support.md)

- `assets/` contains artwork and frame references.
- `config/` contains resolution, cutout, and launcher profiles.
- `renders/` contains the native PNG and SVG wallpapers.
- `site/` contains the static site and catalog.
- `src/` contains generation and packaging scripts.

- `android-live/` contains the Android 13+ app for live and regular wallpapers. Its offline picker includes all 603 catalog screen profiles.
- `wear-watch/` contains the Wear OS watch face.
- `wear-live/` contains the always-on 30 FPS Wear OS live clock companion app.
- `wear-tesseract/` contains the standalone Wear OS rave app (`rgb poly`), running Poly's 6-solid morph loop with RGB hue rotation and bass pulse.

Download the [Android & Wear OS apps (`apps-v1.4.2`)](https://github.com/xklob/wallpapers/releases/tag/apps-v1.4.2), or view build instructions for [Android Live](android-live/README.md), [Wear OS Watch Face](wear-watch/README.md), [Wear OS Always-On Live](wear-live/README.md), and [rgb poly](wear-tesseract/README.md).

## Build the site locally

```sh
python src/package_release.py
python src/build_site.py
python -m http.server --directory build/site 8000
```

Open `http://localhost:8000` to preview the site. The release workflow generates RGB PNGs from the checked-in native SVGs using Sharp, validates image dimensions, and publishes both archives.
