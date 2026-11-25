"""Check the local catalog artwork in both formats.

Run with `uv run --script src/validate_catalog_art.py`. An optional --report
writes the per-image measurements to CSV for inspection.
"""
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "resvg-py"]
# ///
import argparse
import csv
import io
import json
from concurrent.futures import ProcessPoolExecutor
from functools import lru_cache
from pathlib import Path
from xml.etree import ElementTree

import numpy as np
import resvg_py
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]


@lru_cache(maxsize=2)
def tablet_contour_samples(width, height):
    spec = json.loads((ROOT / "config/google_display_specs.json").read_text())["devices"]["pixel_tablet"]
    layout = json.loads((ROOT / "config/pixel_tablet_layout.json").read_text())
    path = spec["display_shape"]
    rotation = f"translate({width} 0) rotate(90)" if width < height else ""
    # Sample the middle of the RGB band and the black reveal independently
    # against Google's panel silhouette. Include short corner arcs on every side.
    clearance = layout["outer_clearance_px"]
    middle = clearance + layout["frame_width_px"] / 2
    strokes = [(2 * (middle + 1), "white"), (2 * (middle - 1), "black"), (2, "red")]
    lines = "".join(f'<path d="{path}" fill="none" stroke="{color}" stroke-width="{size}"/>'
                    for size, color in strokes)
    ref = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">'
           f'<g transform="{rotation}"><defs><clipPath id="p"><path d="{path}"/></clipPath></defs>'
           f'<g clip-path="url(#p)">{lines}</g></g></svg>')
    with Image.open(io.BytesIO(resvg_py.svg_to_bytes(svg_string=ref, background="#000000"))) as image:
        mask = np.asarray(image.convert("RGB"))
    return np.nonzero(mask[:, :, 1] > 180), np.nonzero((mask[:, :, 0] > 160) & (mask[:, :, 1] < 20))


def check_tablet(image, row, errors, fmt):
    _, _, device, _, width, height, style = row[:7]
    if image.size != (width, height):
        return
    pixels = np.asarray(image.convert("RGB")).max(axis=2)
    if style == "rgb_border":
        frame, reveal = tablet_contour_samples(width, height)
        if (pixels[frame] > 64).mean() < 0.99 or (pixels[reveal] > 30).mean() > 0.01:
            errors.append(f"{fmt} tablet screen perimeter")
        # The straight edges account for most of the frame. Test each corner separately.
        ys, xs = frame
        for top in (True, False):
            for left in (True, False):
                corner = (ys < 45 if top else ys >= height - 45) & (xs < 45 if left else xs >= width - 45)
                if not corner.any() or (pixels[ys[corner], xs[corner]] > 64).mean() < 0.95:
                    errors.append(f"{fmt} tablet corner")
                    return
    layouts = json.loads((ROOT / "config/pixel_tablet_layout.json").read_text())["home"]
    if device not in layouts:
        return
    panel = layouts[device]["panel"]
    x, y, w, h, r = (panel[k] for k in ("x", "y", "width", "height", "radius"))
    inside = pixels[y + panel["stroke"]:y + h - panel["stroke"], x + r:x + w - r]
    home = len(row) > 9 and row[9]
    if home:
        edge = pixels[[y, y + h], x + r:x + w - r]
        if np.any(inside > 16) or np.mean(edge > 64) < 0.99:
            errors.append(f"{fmt} tablet Home panel")
        if device == "pixel_tablet_landscape":
            # The 1280 × 800 launcher screenshot shows a rounded-square folder at
            # (1085, 690) to (1149, 755), with roughly 10 px corner radii. Keep at
            # least 4 px clear around that footprint at the native 2× resolution.
            padding = 4
            x0, x1 = 2 * (1085 - padding), 2 * (1149 + padding)
            y0, y1 = 2 * (690 - padding), 2 * (755 + padding)
            footprint = Image.new("1", (x1 - x0, y1 - y0))
            ImageDraw.Draw(footprint).rounded_rectangle(
                (0, 0, footprint.width - 1, footprint.height - 1),
                radius=2 * (10 + padding), fill=1)
            if np.any(pixels[y0:y1, x0:x1][np.asarray(footprint)] > 16):
                errors.append(f"{fmt} tablet Home folder clearance")
    elif np.mean(inside > 16) < 0.005:
        errors.append(f"{fmt} unexpected tablet Home cutout")


def validate(row):
    file, _, _, _, width, height, *_ = row
    svg = ROOT / "renders/svg" / file
    png = (ROOT / "renders/png" / file).with_suffix(".png")
    errors = []
    tree = ElementTree.parse(svg).getroot()
    if (int(tree.get("width")), int(tree.get("height"))) != (width, height):
        errors.append("SVG dimensions")
    if tuple(map(float, tree.get("viewBox").split())) != (0, 0, width, height):
        errors.append("SVG viewBox")

    # Downsample for layout inspection only, retaining the native aspect ratio.
    # Fully decode the PNG at native size before comparing the two formats.
    scale = min(1.0, 512 / max(width, height))
    render = resvg_py.svg_to_bytes(svg_path=str(svg), zoom=scale, skip_system_fonts=True)
    with Image.open(io.BytesIO(render)) as vector, Image.open(png) as raster:
        if raster.size != (width, height):
            errors.append("PNG dimensions")
        if raster.mode != "RGB" and not (raster.mode == "RGBA" and raster.getextrema()[3] == (255, 255)):
            errors.append("PNG opacity")
        if vector.mode == "RGBA" and vector.getextrema()[3] != (255, 255):
            errors.append("SVG opacity")
        if abs(vector.width - scale * width) > 1 or abs(vector.height - scale * height) > 1:
            errors.append("rendered aspect ratio")
        reduced = raster.convert("RGB").resize(vector.size, Image.Resampling.LANCZOS)
        vector_rgb = np.asarray(vector.convert("RGB"))
        raster_rgb = np.asarray(reduced)
        if row[2] in ("pixel_tablet_landscape", "pixel_tablet_portrait"):
            check_tablet(raster, row, errors, "PNG")
            native = resvg_py.svg_to_bytes(svg_path=str(svg), skip_system_fonts=True)
            with Image.open(io.BytesIO(native)) as native_svg:
                check_tablet(native_svg, row, errors, "SVG")
    vector_luma = vector_rgb.max(axis=2)
    raster_luma = raster_rgb.max(axis=2)
    correlation = float(np.corrcoef(vector_luma.ravel(), raster_luma.ravel())[0, 1])
    if not correlation >= 0.84:
        errors.append("PNG and SVG patterns disagree")

    # Check all four quadrants and both axes. The Android RGB frames have
    # intentionally padded margins, and very small foldable cover displays
    # can fit just a handful of cells.
    mask = raster_luma > 15
    ys, xs = np.nonzero(mask)
    w, h = reduced.size
    spans = ((int(xs.max()) - int(xs.min()) + 1) / w,
             (int(ys.max()) - int(ys.min()) + 1) / h) if xs.size else (0, 0)
    if min(spans) < 0.70:
        errors.append("insufficient visible extent")
    for quadrants in np.array_split(mask, 2, axis=0):
        if any(part.mean() < 0.005 for part in np.array_split(quadrants, 2, axis=1)):
            errors.append("empty quadrant")
            break
    if not 0.005 <= mask.mean() < 0.65:
        errors.append("pattern coverage")
    if min(raster_rgb[:, :, i].max() for i in range(3)) < 40:
        errors.append("missing color channel")
    return dict(file=file, width=width, height=height, correlation=round(correlation, 5),
                span_x=round(spans[0], 4), span_y=round(spans[1], 4),
                coverage=round(float(mask.mean()), 4), errors=", ".join(errors))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    themes = json.loads((ROOT / "site/catalog.json").read_text())["themes"]
    rows = [row for theme in themes for row in theme["files"]]
    with ProcessPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(validate, rows, chunksize=4))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=results[0].keys())
            writer.writeheader()
            writer.writerows(results)
    failures = [result for result in results if result["errors"]]
    for result in failures:
        print(result["file"], result["errors"], sep=": ")
    print(f"{len(rows)} native PNGs and SVG renderings checked, {len(failures)} require review.")
    raise SystemExit(bool(failures))
