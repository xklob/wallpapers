# klob // wallpapers

Browse geometric wallpapers by device at [wallpapers.klob.io](https://wallpapers.klob.io/) or [open the hosted picker](https://klob-wallpapers.deep-krill-3494.chatgpt.site/). Get the complete PNG and SVG packs from the [v1.0.0 release](https://github.com/xklob/wallpapers/releases/tag/v1.0.0).

**RGB Geometry** includes bright and subtle honeycombs, spaced hexagons, and wireframe cubes. All four patterns have 60 screen and orientation presets, two border styles, and 30 optional camera-ring profiles. Clipped cells close with solid edges. There are 720 wallpapers per format.

[Device sizes](config/device_sizes.csv) · [Device and camera guide](docs/device-guide.md)

The PNGs retain the source raster artwork. The native SVGs reconstruct the artwork as paths and gradients and may differ slightly in color or line feel. SVG is suitable for editing and scales without raster pixelation. PNG is ready for standard wallpaper pickers.

## Build

From the repository root, install Python 3.12, ImageMagick 7, and the Python dependencies. The four source images and frame assets are in `assets/`. Input geometry and device metadata are in `config/`.

```sh
python -m pip install -r requirements.txt
python src/render_png.py
python src/render_camera.py
python src/render_vectors.py --data-root . --output renders/svg
python src/validate.py
python src/package_release.py
```

The renderers write to `renders/`. The packager writes the complete downloads to `release/`. Run the release workflow from the Actions tab to publish another version.
