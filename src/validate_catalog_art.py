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
from pathlib import Path
from xml.etree import ElementTree

import numpy as np
import resvg_py
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


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
