# klob // wallpapers

Browse by device at [wallpapers.klob.io](https://wallpapers.klob.io/). Download the complete [PNG and SVG packs](https://github.com/xklob/wallpapers/releases/tag/v1.1.0).

RGB Geometry includes bright and subtle honeycombs, spaced hexagons, and wireframe cubes. There are 832 wallpapers per format. Newer device presets include Pixel 10 and 11, Galaxy S25 and S26, current Samsung foldables, iPhone 17 and 18 Pro models, iPhone Air, and iPhone Duo. Desktop wallpapers have about twice the shape density.

- `assets/` contains the artwork and frame references.
- `renders/` contains the PNG and SVG artwork.
- `site/` contains the site source and device catalog.
- `src/` contains tools for rebuilding and packaging the artwork.

## Package and preview

Python 3.12 packages the checked-in artwork and assembles the static site. The original renderers also use ImageMagick 7 and the dependencies in `requirements.txt`. Updated PNGs are exported from their native SVGs using Node.js and Sharp.

```sh
python src/package_release.py
python src/build_site.py
python -m http.server --directory build/site 8000
```

Open `http://localhost:8000` to preview the picker. The release workflow commits any regenerated PNGs before publishing both packs.
