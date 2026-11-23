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
from export_aligned import select_cells

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

def layout(design, width, height, style, polygons, stroke_factor=1):
    pad = round(min(width, height) * (0.085 if style == "rgb_border" else 0.025))
    inner_width, inner_height = width - 2*pad, height - 2*pad
    _, box = select_cells(design, inner_width, inner_height)
    x, y, box_width, box_height = box
    scale = min(inner_width / box_width, inner_height / box_height)
    pattern_width, pattern_height = round(box_width*scale), round(box_height*scale)
    sx, sy = pattern_width / box_width, pattern_height / box_height
    stroke = max(1.5, vector.STROKES[design["id"]] * scale * stroke_factor)
    inset = stroke / 2 + 0.5

    def project(point):
        px, py = point
        return (max(inset, min(pattern_width - 1 - inset, (px-x)*sx)),
                max(inset, min(pattern_height - 1 - inset, (py-y)*sy)))

    rect = x, y, x+box_width-1, y+box_height-1
    caps = [{"a": project(a), "b": project(b)}
            for polygon in polygons for a, b, _ in vector_caps(polygon, rect)]
    return {"pad": pad, "source_box": box, "pattern_size": (pattern_width, pattern_height),
            "cap_stroke": stroke, "caps": caps}

def vector_caps(poly, rect):
    x0, y0, x1, y1 = rect
    for axis, value, low, high in ((0,x0,y0,y1),(0,x1,y0,y1),(1,y0,x0,x1),(1,y1,x0,x1)):
        coords = [point[axis] for point in poly]
        if not min(coords)+.2 < value < max(coords)-.2:
            continue
        intersections = []
        for a,b in zip(poly, poly[1:]+poly[:1]):
            if b[axis] == a[axis]:
                continue
            t = (value-a[axis])/(b[axis]-a[axis])
            if -1e-8 <= t <= 1+1e-8:
                intersections.append(a[1-axis]+t*(b[1-axis]-a[1-axis]))
        if len(intersections) < 2:
            continue
        start, end = max(low,min(intersections)), min(high,max(intersections))
        if end-start >= 1:
            yield ((value,start),(value,end),axis) if axis==0 else ((start,value),(end,value),axis)

def lattice_geometry(polygons):
    rows = []
    centers = sorted(((poly[0][0], (poly[0][1]+poly[3][1])/2) for poly in polygons),
                     key=lambda point: point[1])
    for x,y in centers:
        if not rows or abs(y-rows[-1][0][1]) > 4:
            rows.append([])
        rows[-1].append((x,y))
    ordinates = [vector.np.median([y for _,y in row]) for row in rows]
    dx = vector.np.median([b-a for row in rows
                           for a,b in zip(sorted(x for x,_ in row), sorted(x for x,_ in row)[1:])])
    dy = vector.np.median(vector.np.diff(ordinates))
    width = vector.np.median([poly[1][0]-poly[5][0] for poly in polygons])
    height = vector.np.median([poly[3][1]-poly[0][1] for poly in polygons])
    return dx, dy, width, height

def smaller_lattice(geometry, scale, phase_x, phase_y):
    dx, dy, width, height = (value*scale for value in geometry)
    polygons = []
    for row in range(-14,15):
        for column in range(-13,14):
            cx = 627+(column+0.5*(row%2)+phase_x)*dx
            cy = 627+(row+phase_y)*dy
            if cx+width/2 < 0 or cx-width/2 > 1254 or cy+height/2 < 0 or cy-height/2 > 1254:
                continue
            polygons.append([(cx,cy-height/2),(cx+width/2,cy-height/4),(cx+width/2,cy+height/4),
                             (cx,cy+height/2),(cx-width/2,cy+height/4),(cx-width/2,cy-height/4)])
    return polygons

def count_in_crop(polygons, box):
    x,y,width,height=box
    return sum(x <= poly[0][0] <= x+width and y <= (poly[0][1]+poly[3][1])/2 <= y+height
               for poly in polygons)

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
