#!/usr/bin/env python3
"""Package website artwork and resolve model aliases exactly as the wallpaper picker does."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import shutil
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PATTERNS = ("01_honeycomb_bright", "02_hexagons", "03_honeycomb_subtle", "04_cubes")
SPECIAL = {"ipad": "iPad", "iphone": "iPhone", "rgb": "RGB", "xl": "XL", "oppo": "OPPO",
           "huawei": "HUAWEI", "oneplus": "OnePlus", "honor": "HONOR", "fhd": "FHD", "4k": "4K"}
BRANDS = {"iphone": "Apple", "ipad": "Apple", "pixel": "Google", "google": "Google"}
ET.register_namespace("", "http://www.w3.org/2000/svg")


def label(name):
    return " ".join(SPECIAL.get(part, part.capitalize()) for part in name.split("_"))


def prepare_file(source, output, row, viewport):
    name, _, _, _, width, height, *_ = row
    svg = ET.parse(source / name).getroot()
    if (int(svg.get("width")), int(svg.get("height"))) != (width, height):
        raise ValueError(f"SVG size disagrees with catalog: {name}")
    inset = svg.find(".//*[@id='android-wallpaper-inset']")
    if not viewport and inset is None:
        path = Path("art") / name
        (output / path).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, output / path)
        return path.as_posix()
    if viewport:
        # Pixel 11 Pro XL overlays are calibrated against the stock launcher's
        # observed wallpaper crop. A live surface draws directly to the screen.
        zoom = viewport["zoom"]
        left = width / 2 - (width / 2 + viewport["shift_x"]) / zoom
        top = height / 2 - (height / 2 + viewport["shift_y"]) / zoom
        svg.set("viewBox", f"{left} {top} {width / zoom} {height / zoom}")
    else:
        # The static pack adds blank space around the frame for launcher zoom.
        # Remove only this group's inset. Camera rings use display coordinates.
        inset.attrib.pop("transform")
    path = Path("display-art") / name
    (output / path).parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(svg).write(output / path, encoding="utf-8")
    return path.as_posix()


def assets(source, profiles, output, catalog):
    active = next(t for t in json.loads(catalog.read_text())["themes"] if t["id"] == "rgb-geometry")
    if set(PATTERNS) != set(active["designs"]):
        raise ValueError("Update the app pattern indices to match the website")
    cameras = json.loads(profiles.read_text())
    presets = {}
    accuracy = {}
    base, rings, overlays = (defaultdict(dict) for _ in range(3))
    for row in active["files"]:
        name, pattern, preset, family, width, height, style, ring, _, dock, status = (row + [False] * 3)[:11]
        border = {"original": 0, "rgb_border": 8}[style]
        mask = border | (4 if status else 0) | (2 if dock else 0) | bool(ring)
        key = f"{PATTERNS.index(pattern)}-{mask}"
        viewport = cameras.get(preset, {}).get("wallpaper_viewports", {}).get(style)
        path = prepare_file(source, output, row, viewport)
        if ring and accuracy.setdefault(preset, ring) != ring:
            raise ValueError(f"Inconsistent camera accuracy for {preset}")
        if dock or status:
            group = overlays
        elif ring:
            group = rings
        else:
            group = base
            _, category, brand, *_ = name.split("/")
            presets[preset] = dict(id=preset, preset=preset, name=label(preset), width=width, height=height,
                                   family=family, manufacturer="Generic" if category == "desktop"
                                   else BRANDS.get(brand, label(brand)))
        if key in group[preset]:
            raise ValueError(f"Duplicate artwork for {preset} {key}")
        group[preset][key] = path

    hidden = set(active.get("hiddenPresets", []))
    devices = [{**presets[a["preset"]], **a} for a in active["deviceAliases"]]
    devices.extend(p for p in presets.values() if p["id"] not in hidden)
    for device in devices:
        preset = device.pop("preset")
        camera = device.pop("cameraPreset", preset if device["id"] == preset else None)
        device["cameraAccuracy"] = accuracy.get(camera, "")
        device["variants"] = {**base[preset], **rings.get(camera, {}), **overlays.get(device["id"], {})}
    (output / "catalog.json").write_text(json.dumps(devices, separators=(",", ":")) + "\n")
    print(f"Prepared {len(devices)} screen profiles from {len(active['files'])} catalog wallpapers.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Wallpaper SVG directory")
    parser.add_argument("profiles", type=Path, help="Camera and viewport profile JSON")
    parser.add_argument("output", type=Path, help="Android asset output directory")
    parser.add_argument("--catalog", type=Path, default=ROOT / "site/catalog.json")
    args = parser.parse_args()
    assets(args.source, args.profiles, args.output, args.catalog)
