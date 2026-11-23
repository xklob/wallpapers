import argparse
import json
import sys
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZIP_DEFLATED, ZipFile

parser = argparse.ArgumentParser(description="Add flagship screens and denser desktop SVGs to the wallpaper catalog.")
parser.add_argument("--source-root", type=Path, required=True)
parser.add_argument("--site-dist", type=Path, required=True)
arguments = parser.parse_args()
renderer = arguments.source_root.resolve()
site = arguments.site_dist.resolve()
sys.path.insert(0, str(renderer / "src"))
import render_vectors as vector
from aligned_svg import layout
from desktop_density import count_in_crop, lattice_geometry, smaller_lattice

catalog_path = site / "catalog.json"
catalog = json.loads(catalog_path.read_text())
theme = catalog["themes"][0]
theme["files"] = [row[:8] for row in theme["files"] if row[3] == 'Desktop' or not (len(row) > 8 and row[8])]
presets = {row[2]: (row[4], row[5]) for row in theme["files"]}
aliases = []
new_screens = []

def alias(device_id, name, source, size):
    assert presets[source] == size, (device_id, source, presets[source], size)
    aliases.append(dict(id=device_id, name=name, preset=source))

for year in (11, 10):
    for slug, display, size in (
        ("", "", (1080, 2424)),
        ("_pro", " Pro", (1280, 2856)),
        ("_pro_xl", " Pro XL", (1344, 2992)),
    ):
        alias(f"pixel_{year}{slug}", f"Pixel {year}{display}", f"pixel_9{slug}", size)

for year in (26, 25):
    alias(f"galaxy_s{year}", f"Galaxy S{year}", "galaxy_s24", (1080, 2340))
    alias(f"galaxy_s{year}_plus", f"Galaxy S{year}+", "galaxy_s24_plus_ultra", (1440, 3120))
    alias(f"galaxy_s{year}_ultra", f"Galaxy S{year} Ultra", "galaxy_s24_plus_ultra", (1440, 3120))

for full_name, slug, source, size in (
    ("iPhone 18 Pro", "iphone_18_pro", "iphone_16_pro", (1206, 2622)),
    ("iPhone 18 Pro Max", "iphone_18_pro_max", "iphone_16_pro_max", (1320, 2868)),
    ("iPhone 17", "iphone_17", "iphone_16_pro", (1206, 2622)),
    ("iPhone 17 Pro", "iphone_17_pro", "iphone_16_pro", (1206, 2622)),
    ("iPhone 17 Pro Max", "iphone_17_pro_max", "iphone_16_pro_max", (1320, 2868)),
):
    alias(slug, full_name, source, size)

def fold(manufacturer, slug, screen, size, *, matching=None, name=None):
    for orientation, dimensions in (("portrait", size), ("landscape", size[::-1])):
        suffix = f"_{screen}_{orientation}"
        if matching:
            alias(slug + suffix, f"{name} {screen.title()} {orientation.title()}",
                  matching + suffix, dimensions)
        else:
            new_screens.append(("Foldables", f"foldable/{manufacturer}", slug + suffix, *dimensions))

fold("google", "pixel_11_pro_fold", "cover", (1080, 2342))
fold("google", "pixel_11_pro_fold", "inner", (2076, 2152),
     matching="pixel_10_pro_fold", name="Pixel 11 Pro Fold")
fold("samsung", "galaxy_z_fold8_ultra", "cover", (1080, 2520),
     matching="galaxy_z_fold7", name="Galaxy Z Fold8 Ultra")
fold("samsung", "galaxy_z_fold8_ultra", "inner", (2256, 2504))
fold("samsung", "galaxy_z_fold8", "cover", (1248, 1972))
fold("samsung", "galaxy_z_fold8", "inner", (1848, 2448))
fold("samsung", "galaxy_z_flip8", "cover", (948, 1048),
     matching="galaxy_z_flip7", name="Galaxy Z Flip8")
fold("samsung", "galaxy_z_flip8", "inner", (1080, 2520),
     matching="galaxy_z_flip7", name="Galaxy Z Flip8")
fold("apple", "iphone_duo", "cover", (1398, 2034))
fold("apple", "iphone_duo", "inner", (1878, 2670))
new_screens.extend([
    ("Phones", "phone/iphone", "iphone_air", 1260, 2736),
    ("Phones", "phone/iphone", "iphone_17e", 1170, 2532),
])

art_root = site / "art" / theme["id"]
ratios = []
for design in json.loads((renderer / "config/cells.json").read_text()):
    design_id = design["id"]
    palette = vector.fit_palette(renderer / design["source"], design_id == "04_cubes")
    frame = vector.frame_colors(renderer / design["master"])
    polygons = vector.grid_polygons(design)
    for family, folder, preset, width, height in new_screens:
        for style in ("original", "rgb_border"):
            meta = {"width": width, "height": height, "style": style}
            svg = vector.build_svg(meta, layout(design,width,height,style,polygons),
                                   design,polygons,palette,frame)
            root = ElementTree.fromstring(svg)
            assert (root.get("width"), root.get("height")) == (str(width),str(height))
            assert not root.findall("{http://www.w3.org/2000/svg}image")
            relative = f"{design_id}/{folder}/{style}/{preset}_{width}x{height}_{style}.svg"
            dest = art_root / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(svg)
            theme["files"].append([relative, design_id, preset, family, width, height, style, "", True])

    geometry = lattice_geometry(polygons)
    ideal = 2**-0.5
    dense_candidates = [(scale, px, py, smaller_lattice(geometry, scale, px, py))
                        for scale in (ideal, .65, .66, .67, .68, .69, .70, .72, .73, .74, .75, .76)
                        for px in (0, .25, .5) for py in (0, .25, .5)]
    for row in theme["files"]:
        if row[1] != design_id or row[3] != "Desktop":
            continue
        relative, _, _, _, width, height, style, _ = row
        meta = {"width": width, "height": height, "style": style}
        old_layout = layout(design, width, height, style, polygons)
        box = old_layout["source_box"]
        original_count = count_in_crop(polygons, box)
        goal = original_count * 2
        scale, _, _, dense = min(dense_candidates, key=lambda item:
            (abs(count_in_crop(item[3], box)-goal), abs(item[0]-ideal), item[1]+item[2]))
        dense_layout = layout(design, width, height, style, dense, scale)
        old_stroke = vector.STROKES[design_id]
        vector.STROKES[design_id] *= scale
        try:
            svg = vector.build_svg(meta, dense_layout, design, dense, palette, frame)
        finally:
            vector.STROKES[design_id] = old_stroke
        root = ElementTree.fromstring(svg)
        assert (root.get("width"), root.get("height")) == (str(width),str(height))
        (art_root / relative).write_text(svg)
        row.append(True)
        ratios.append(count_in_crop(dense, box) / original_count)


theme["deviceAliases"] = aliases
theme["packNote"] = ("The SVG pack includes every listed screen size. The new PNG pack is publishing. "
                     "You can download any listed size individually as a PNG.")
with ZipFile(site / theme["archive"], "w", ZIP_DEFLATED, compresslevel=8) as archive:
    for row in theme["files"]:
        archive.write(art_root / row[0], row[0])
    archive.writestr("device_aliases.json", json.dumps(aliases, indent=2)+"\n")
    archive.writestr("README.md",
        "# RGB Geometry wallpapers\n\n"
        f"{len(theme['files'])} SVG wallpapers. Model aliases share an exact screen size "
        "and use the associated standard or RGB-border wallpaper. "
        "Camera outlines are model-specific. See device_aliases.json for compatibility.\n")
catalog_path.write_text(json.dumps(catalog, separators=(",",":"))+"\n")
print(f"{len(aliases)} exact-size model aliases, {len(new_screens)} new screen presets, {len(theme['files'])} SVG files")

print(f"Desktop shape count ratios: {min(ratios):.2f} to {max(ratios):.2f}; mean {sum(ratios)/len(ratios):.2f}")
