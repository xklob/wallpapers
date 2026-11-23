#!/usr/bin/env python3
"""Render the Pixel 11 Pro XL variants into display-space WebP textures."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import json
from pathlib import Path
import subprocess
from xml.etree import ElementTree as ET

from PIL import Image

DEVICE = "pixel_11_pro_xl"
WIDTH = 1344
HEIGHT = 2992
PATTERNS = (
    "01_honeycomb_bright",
    "02_hexagons",
    "03_honeycomb_subtle",
    "04_cubes",
)
ET.register_namespace("", "http://www.w3.org/2000/svg")


def prepare(source, destination, viewport):
    zoom = viewport["zoom"]
    left = WIDTH / 2 - (WIDTH / 2 + viewport["shift_x"]) / zoom
    top = HEIGHT / 2 - (HEIGHT / 2 + viewport["shift_y"]) / zoom
    svg = ET.parse(source).getroot()
    svg.set("viewBox", f"{left} {top} {WIDTH / zoom} {HEIGHT / zoom}")
    # Inkscape uses the viewBox as the page, including the cropped RGB border.
    raster = subprocess.run(
        ["inkscape", "--pipe", "--export-type=png", "--export-filename=-",
         "--export-background=#000000", "--export-background-opacity=1"],
        input=ET.tostring(svg, encoding="utf-8"), check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout
    with Image.open(BytesIO(raster)) as png:
        if png.size != (WIDTH, HEIGHT):
            raise ValueError(f"Unexpected dimensions for {source}: {png.size}")
        png.convert("RGB").save(destination, "WEBP", lossless=True, method=4)


def assets(source, profiles, output):
    viewports = json.loads(profiles.read_text())[DEVICE]["wallpaper_viewports"]
    art = output / "art"
    art.mkdir(parents=True, exist_ok=True)
    jobs = []
    for index, pattern in enumerate(PATTERNS):
        for variant in range(16):
            style = "rgb_border" if variant & 8 else "original"
            dock = "_home_screen" if variant & 2 else ""
            camera = "_camera" if variant & 1 else ""
            status = "_status_bar" if variant & 4 else ""
            filename = f"{DEVICE}_{WIDTH}x{HEIGHT}_{style}{dock}{camera}{status}.svg"
            path = source / pattern / "phone" / "google" / style / filename
            if not path.is_file():
                raise FileNotFoundError(path)
            jobs.append((path, art / f"{index}-{variant}.webp", viewports[style]))

    with ThreadPoolExecutor(max_workers=4) as workers:
        for result in workers.map(lambda job: prepare(*job), jobs):
            pass
    print(f"Prepared {len(jobs)} Pixel wallpaper textures.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Wallpaper SVG directory")
    parser.add_argument("profiles", type=Path, help="Camera and viewport profile JSON")
    parser.add_argument("output", type=Path, help="Android asset output directory")
    args = parser.parse_args()
    assets(args.source, args.profiles, args.output)
