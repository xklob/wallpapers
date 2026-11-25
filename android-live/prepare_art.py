#!/usr/bin/env python3
"""Package website artwork and resolve model aliases exactly as the wallpaper picker does."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import shutil
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PATTERNS = ("01_honeycomb_bright", "02_hexagons", "04_cubes")
MENGER_FRAMES = "menger-frames"
SPECIAL = {"ipad": "iPad", "iphone": "iPhone", "rgb": "RGB", "xl": "XL", "oppo": "OPPO",
           "huawei": "HUAWEI", "oneplus": "OnePlus", "honor": "HONOR", "fhd": "FHD", "4k": "4K"}
BRANDS = {"iphone": "Apple", "ipad": "Apple", "pixel": "Google", "google": "Google"}
ET.register_namespace("", "http://www.w3.org/2000/svg")


def label(name):
    return " ".join(SPECIAL.get(part, part.capitalize()) for part in name.split("_"))


SIDES = ("top", "right", "bottom", "left")


def mark_device_frame(svg):
    """Describe the authored frame in viewport, clipping, and gradient coordinates."""
    clip = svg.find(".//*[@id='rgb-frame-clip']")
    if clip is None or len(clip) != 1 or not clip[0].tag.endswith("}rect"):
        return False  # A calibrated panel silhouette or an unframed design.
    rect = clip[0]
    left, top, width, height = [float(rect.get(k)) for k in ("x", "y", "width", "height")]
    rx = float(rect.get("rx", 0))
    ry = float(rect.get("ry", rx))
    view_x, view_y, view_w, view_h = map(float, svg.get("viewBox").split())
    x, y, w, h = left, top, width, height
    parents = {child: parent for parent in svg.iter() for child in parent}
    group = svg.find(".//*[@clip-path='url(#rgb-frame-clip)']")
    while group is not None:
        transform = group.get("transform")
        if transform:
            values = re.fullmatch(r"translate\(([-.\d]+) ([-.\d]+)\) scale\(([-.\d]+) ([-.\d]+)\)", transform)
            if not values:
                raise ValueError(f"Unrecognized frame transform {transform}")
            tx, ty, sx, sy = map(float, values.groups())
            x, y, w, h = tx + sx*x, ty + sy*y, sx*w, sy*h
        group = parents.get(group)
    upper = svg.find(".//*[@id='frame-top']")
    lower = svg.find(".//*[@id='frame-bottom']")
    fx, fy, right, bottom = map(float, [upper.get("x1"), upper.get("y1"), upper.get("x2"), lower.get("y1")])
    data = [(x-view_x)/view_w, (y-view_y)/view_h, w/view_w, h/view_h, rx/width, ry/height,
            left, top, width, height, fx, fy, right-fx, bottom-fy]
    svg.set("data-device-frame", " ".join(f"{value:.9g}" for value in data))
    # Stable markers allow the app to replace these five shapes in one pass,
    # without reparsing or changing any of the lattice paths or launcher overlays.
    rect.set("id", "device-clip")
    for side in SIDES:
        path = svg.find(f".//*[@stroke='url(#frame-{side})']")
        attrs = dict(path.attrib)
        path.attrib.clear()
        path.set("id", f"device-{side}")
        path.set("d", attrs.pop("d"))
        path.attrib.update(attrs)
    return True


def prepare_file(source, output, row, viewport):
    name, _, _, _, width, height, *_ = row
    svg = ET.parse(source / name).getroot()
    if (int(svg.get("width")), int(svg.get("height"))) != (width, height):
        raise ValueError(f"SVG size disagrees with catalog: {name}")
    inset = svg.find(".//*[@id='android-wallpaper-inset']")
    if viewport:
        # Pixel 11 Pro XL overlays are calibrated against the stock launcher's
        # observed wallpaper crop. A live surface draws directly to the screen.
        zoom = viewport["zoom"]
        left = width / 2 - (width / 2 + viewport["shift_x"]) / zoom
        top = height / 2 - (height / 2 + viewport["shift_y"]) / zoom
        svg.set("viewBox", f"{left} {top} {width / zoom} {height / zoom}")
    elif inset is not None:
        # The static pack adds blank space around the frame for launcher zoom.
        # Remove only this group's inset. Camera rings use display coordinates.
        inset.attrib.pop("transform")
    calibrated_tablet = row[2].startswith("pixel_tablet_")
    marked = row[6] == "rgb_border" and not calibrated_tablet and mark_device_frame(svg)
    path = Path("display-art" if viewport or inset is not None else "art") / name
    (output / path).parent.mkdir(parents=True, exist_ok=True)
    if marked or viewport or inset is not None:
        ET.ElementTree(svg).write(output / path, encoding="utf-8")
    else:
        shutil.copyfile(source / name, output / path)
    if row[1] == PATTERNS[0]:
        write_menger_frame(svg, output / MENGER_FRAMES / path, row[6] == "rgb_border")
    return path.as_posix()


def write_menger_frame(svg, output, bordered):
    """Keep the profile's stationary frame and launcher artwork, exposing the fractal inside."""
    ns = "{http://www.w3.org/2000/svg}"
    parents = {child: parent for parent in svg.iter() for child in parent}
    group = svg.find(".//*[@stroke='url(#rainbow)']")
    while group.get("clip-path") is None:
        group = parents[group]
    parents[group].remove(group)
    background = svg.find(ns + "rect")
    if bordered:
        # Paint the black gutter only outside the calibrated silhouette. The clip
        # shares the original transforms and any runtime hardware-corner adjustment.
        defs = svg.find(ns + "defs")
        mask = ET.SubElement(defs, ns + "mask", id="menger-gutter")
        area = dict(x="-100%", y="-100%", width="300%", height="300%")
        ET.SubElement(mask, ns + "rect", area, fill="white")
        branch = ET.Element(ns + "g", {"clip-path": group.get("clip-path")})
        ET.SubElement(branch, ns + "rect", area, fill="black")
        parent = parents[group]
        while parent is not svg:
            wrapper = ET.Element(ns + "g", dict(parent.attrib))
            wrapper.attrib.pop("id", None)
            wrapper.append(branch)
            branch = wrapper
            parent = parents[parent]
        mask.append(branch)
        background.set("mask", "url(#menger-gutter)")
    else:
        svg.remove(background)
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(svg).write(output, encoding="utf-8")


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
        if pattern == PATTERNS[0]:
            group[preset][f"{len(PATTERNS)}-{mask}"] = f"{MENGER_FRAMES}/{path}"

    hidden = set(active.get("hiddenPresets", []))
    devices = [{**presets[a["preset"]], **a} for a in active["deviceAliases"]]
    devices.extend(p for p in presets.values() if p["id"] not in hidden)
    hardware = json.loads((ROOT / "config/android_hardware_models.json").read_text())
    for device in devices:
        product = re.sub(r"(?: (?:Inner|Cover))? (?:Portrait|Landscape)$", "", device["name"], flags=re.I)
        if product in hardware:
            device["hardwareModels"] = hardware[product]
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
