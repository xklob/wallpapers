"""Apply Google's panel geometry to downloadable Pixel phone and foldable frames.

Run with uv run --script src/fit_google_displays.py. The stock static wallpaper
crop is centered at 1.10x. Android removes that compensation for live surfaces.
"""
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "resvg-py", "shapely>=2", "svgpathtools"]
# ///
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
import io
import json
from math import ceil
from pathlib import Path
from tempfile import TemporaryDirectory
import re
import shutil
from xml.etree import ElementTree as ET

from PIL import Image
import resvg_py
from shapely import affinity
from shapely.geometry import Polygon, box
from shapely.geometry.polygon import orient
from svgpathtools import Line, parse_path

ROOT = Path(__file__).resolve().parents[1]
SVG = ROOT / "renders/svg"
PNG = ROOT / "renders/png"
NS = "{http://www.w3.org/2000/svg}"
ET.register_namespace("", NS[1:-1])
SIDES = ("top", "right", "bottom", "left")
SPECS = json.loads((ROOT / "config/google_display_specs.json").read_text())["devices"]
DESIGNS = {"01_honeycomb_bright", "02_hexagons", "04_cubes"}


def number(n):
    return f"{n:.3f}".rstrip("0").rstrip(".")


def shape_key(device):
    model = re.sub(r"_(portrait|landscape)$", "", device)
    spec = SPECS.get(model)
    return spec.get("same_shape_as", model) if spec and model != "pixel_tablet" else None


def silhouette(spec):
    width, height = spec["width"], spec["height"]
    if "display_shape" not in spec:
        # These two stock fold cover resources describe an older panel height.
        # Use the separate Android radius resource at the actual panel dimensions.
        radius = spec["top_radius_px"]
        assert radius == spec["bottom_radius_px"]
        return box(radius, radius, width - radius, height - radius).buffer(radius, quad_segs=32)

    # Some Android paths start with a full display rectangle and subtract tiny
    # corner masks. Sampling curves every four native pixels also handles paths
    # that nearly retrace the left or right edge before closing the corner.
    parts = []
    for subpath in parse_path(spec["display_shape"]).continuous_subpaths():
        points = []
        for segment in subpath:
            count = 1 if isinstance(segment, Line) else max(2, ceil(segment.length() / 4))
            for i in range(count):
                z = segment.point(i / count)
                points.append((z.real, z.imag))
        parts.append(Polygon(points).buffer(0))
    panel, *cutouts = sorted(parts, key=lambda p: p.area, reverse=True)
    for cutout in cutouts:
        panel = panel.difference(cutout)
    if panel.area < .95 * width * height:
        raise ValueError("Invalid display silhouette")
    return panel


@lru_cache(maxsize=None)
def frame_paths(key, width, height, stroke):
    spec = SPECS[key]
    panel = silhouette(spec)
    if (width, height) == (spec["height"], spec["width"]):
        # The catalog's landscape views rotate a native portrait to the left.
        if spec["width"] < spec["height"]:
            panel = affinity.affine_transform(panel, [0, 1, -1, 0, 0, height])
        else:
            panel = affinity.affine_transform(panel, [0, -1, 1, 0, width, 0])
    elif (width, height) != (spec["width"], spec["height"]):
        raise ValueError(f"Display dimensions disagree with {key}")

    # A narrow black reveal follows the curved glass at the same distance on
    # the straight edges and corners. Slivers outside vendor masks drop out.
    inset = panel.buffer(-(2 + stroke / 2))
    if inset.geom_type == "MultiPolygon":
        inset = max(inset.geoms, key=lambda p: p.area)
    coords = list(orient(inset.simplify(.09), sign=1).exterior.coords)[:-1]
    corners = [(0, 0), (width, 0), (width, height), (0, height)]
    idx = [min(range(len(coords)), key=lambda i: (coords[i][0]-x)**2 + (coords[i][1]-y)**2)
           for x, y in corners]

    def path(points):
        return "M" + " ".join(f"{number(x)},{number(y)}" for x, y in points)

    sides = []
    for start, stop in zip(idx, idx[1:] + idx[:1]):
        extended = coords if stop > start else coords + coords
        end = stop if stop > start else stop + len(coords)
        sides.append(path(extended[start:end+1]))
    return path(coords) + "Z", sides


def fit(data, key, width, height):
    root = ET.fromstring(data)
    inset = root.find(".//*[@id='android-wallpaper-inset']")
    if inset is None:
        raise ValueError("Missing static wallpaper crop group")
    scale = 1 / 1.10
    inset.set("transform", f"translate({number(width*(1-scale)/2)} {number(height*(1-scale)/2)}) scale({scale:.10f})")
    clip = root.find(".//*[@id='rgb-frame-clip']")
    if clip[0].tag == NS + "rect":
        # The original lattice is fit to the old frame. Expand this layer to
        # the panel, preserving the camera, status and launcher layers outside it.
        x, y, w, h = [float(clip[0].get(k)) for k in ("x", "y", "width", "height")]
        pattern = inset.find("*[@clip-path='url(#rgb-frame-clip)']")
        wrapper = ET.Element(NS + "g", {"transform":
            f"scale({width/w:.9f} {height/h:.9f}) translate({number(-x)} {number(-y)})"})
        wrapper[:] = list(pattern)
        pattern[:] = [wrapper]
    old = {side: inset.find(f".//*[@stroke='url(#frame-{side})']") for side in SIDES}
    stroke = float(old["top"].get("stroke-width"))
    outline, sides = frame_paths(key, width, height, stroke)
    clip[:] = [ET.Element(NS + "path", {"d": outline})]
    parents = {child: parent for parent in inset.iter() for child in parent}
    for side, d in zip(SIDES, sides):
        frame = old[side]
        parent = parents[frame]
        parent.remove(frame)
        if parent is not inset and not len(parent):
            parents[parent].remove(parent)
        frame.set("d", d)
        frame.set("stroke-linejoin", "round")
        inset.append(frame)
    root.set("data-google-display", key)
    return ET.tostring(root, encoding="unicode") + "\n"


def rgb_png(relative):
    return (PNG / relative).with_suffix(".png")


def main():
    catalog_path = ROOT / "site/catalog.json"
    catalog = json.loads(catalog_path.read_text())
    theme = next(t for t in catalog["themes"] if t["id"] == "rgb-geometry")
    rows = theme["files"]
    base = {}
    for row in rows:
        if not row[7] and not any(row[9:]):
            base.setdefault(row[2], []).append(row)
    # Share only when both the base lattice and the OEM panel shape match.
    # Materialize base styles for formerly resolution-only aliases as needed.
    aliases = []
    new_sources = {}
    representatives = {}
    for alias in sorted(theme["deviceAliases"], key=lambda a: a["id"]):
        device, preset = alias["id"], alias["preset"]
        key = shape_key(device)
        if not key or key == shape_key(preset):
            aliases.append(alias)
            continue
        identity = (preset, key, device.endswith("_landscape"))
        if identity in representatives:
            aliases.append({**alias, "preset": representatives[identity]})
            continue
        representatives[identity] = device
        for old in base[preset]:
            _, design, _, family, width, height, style, *_ = old
            if design not in DESIGNS:
                continue
            folder = "phone" if family == "Phones" else "foldable"
            path = f"{design}/{folder}/google/{style}/{device}_{width}x{height}_{style}.svg"
            new_sources[path] = old[0]
            rows.append([path, design, device, family, width, height, style, "", True])
    alias_by_id = {a["id"]: a for a in aliases}
    theme["deviceAliases"] = [alias_by_id[a["id"]] for a in theme["deviceAliases"] if a["id"] in alias_by_id]

    jobs = []
    for row in rows:
        path, design, device, _, width, height, style, *_ = row
        key = shape_key(device)
        if design not in DESIGNS or not key:
            continue
        source = new_sources.get(path, path)
        if style == "rgb_border":
            svg = fit((SVG / source).read_text(), key, width, height)
            if (SVG / path).exists() and (SVG / path).read_text() == svg and rgb_png(path).exists():
                continue
        elif path in new_sources:
            svg = (SVG / source).read_text()
        else:
            continue
        row[8:9] = [True]
        jobs.append((path, svg, source if style == "original" else None))

    def save(job):
        path, svg, copy = job
        output = SVG / path
        output.parent.mkdir(parents=True, exist_ok=True)
        target = rgb_png(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        # Write the SVG last: its contents are the completion marker on a retry.
        with TemporaryDirectory(dir=target.parent) as temp:
            staging = Path(temp) / target.name
            if copy:
                shutil.copyfile(rgb_png(copy), staging)
            else:
                raster = resvg_py.svg_to_bytes(svg_string=svg, skip_system_fonts=True)
                with Image.open(io.BytesIO(raster)) as image:
                    image.convert("RGB").save(staging)
            staging.replace(target)
        output.write_text(svg)

    with ThreadPoolExecutor(max_workers=4) as workers:
        list(workers.map(save, jobs))
    catalog_path.write_text(json.dumps(catalog, separators=(",", ":")) + "\n")
    print(f"{len(jobs)} Pixel SVG/PNG pairs updated, {len(new_sources)} base files materialized.")


if __name__ == "__main__":
    main()
