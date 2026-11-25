"""Check catalog selection and native RGB frame coverage against Google's panel masks.

Run with uv run --script src/validate_google_frames.py. Pixel Tablet has separate
screenshot and panel checks. This covers the phone and foldable download presets.
"""
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "resvg-py", "scipy"]
# ///
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET

import numpy as np
from PIL import Image
import resvg_py
from scipy.ndimage import distance_transform_edt

ROOT = Path(__file__).resolve().parents[1]


def render_mask(svg):
    png = resvg_py.svg_to_bytes(svg_string=svg, skip_system_fonts=True)
    with Image.open(io.BytesIO(png)) as image:
        return np.asarray(image.convert("RGBA"))[:, :, 3] >= 128


def compare(spec, width, height, paths, stroke):
    transform = ""
    if (width, height) != (spec["width"], spec["height"]):
        assert (height, width) == (spec["width"], spec["height"])
        matrix = (f"0 -1 1 0 0 {height}" if spec["height"] > spec["width"]
                  else f"0 1 -1 0 {width} 0")
        transform = f' transform="matrix({matrix})"'
    shape = spec.get("display_shape")
    panel = (f'<path d="{shape}"{transform}/>' if shape else
             f'<rect width="{width}" height="{height}" rx="{spec["top_radius_px"]}"/>')
    header = f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">'
    target = render_mask(header + panel + "</svg>")
    frame = render_mask(header + "".join(
        f'<path d="{path}" fill="none" stroke="#fff" stroke-linejoin="round" stroke-width="{stroke}"/>'
        for path in paths) + "</svg>")

    # Include pixels outside the physical display even along perfectly vertical
    # and horizontal edges. Half-pixel raster coverage can vary at the bounds.
    distance = distance_transform_edt(np.pad(target, 1))[1:-1, 1:-1]
    measured = distance[frame]
    core = (distance >= 4) & (distance <= stroke + .25)
    coverage = np.count_nonzero(core & frame) / np.count_nonzero(core)
    assert measured.min() >= 1 and measured.max() <= stroke + 3.55 and coverage >= .998, (
        measured.min(), measured.max(), stroke, coverage)


def main():
    theme = json.loads((ROOT / "site/catalog.json").read_text())["themes"][0]
    specs = json.loads((ROOT / "config/google_display_specs.json").read_text())["devices"]
    base = {}
    shapes = {}
    count = 0
    for row in theme["files"]:
        path, _, device, _, width, height, style, ring, *flags = row
        if style != "rgb_border":
            continue
        if not ring and not any(flags[1:]):
            base[device] = (width, height)
        model = re.sub(r"_(portrait|landscape)$", "", device)
        if model not in specs or model == "pixel_tablet":
            continue
        key = specs[model].get("same_shape_as", model)
        svg = ET.parse(ROOT / "renders/svg" / path).getroot()
        assert svg.get("data-google-display") == key, path
        edges = [svg.find(f".//*[@stroke='url(#frame-{side})']") for side in ("top", "right", "bottom", "left")]
        shape = (tuple(p.get("d") for p in edges), float(edges[0].get("stroke-width")))
        identity = (key, width, height)
        if identity not in shapes:
            compare(specs[key], width, height, *shape)
            shapes[identity] = shape
        else:
            assert shapes[identity] == shape, path
        count += 1

    for alias in theme["deviceAliases"]:
        device, preset = (re.sub(r"_(portrait|landscape)$", "", alias[k]) for k in ("id", "preset"))
        if device in specs or preset in specs:
            assert device in specs and preset in specs, alias
            assert specs[device].get("same_shape_as", device) == specs[preset].get("same_shape_as", preset), alias
            w, h = base[alias["preset"]]
            assert sorted((w, h)) == sorted((specs[device]["width"], specs[device]["height"])), alias
    print(f"Verified {count} Google phone/foldable frames, {len(shapes)} distinct oriented masks and all shared aliases.")


if __name__ == "__main__":
    main()
