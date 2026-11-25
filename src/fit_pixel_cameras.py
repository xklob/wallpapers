"""Regenerate Pixel camera rings from the native Android framework cutout paths.

Run with uv run --script src/fit_pixel_cameras.py. Add --check to validate
the metadata, SVG ring, and native PNG for every published Pixel variant.
"""
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "resvg-py", "svgpathtools"]
# ///
import argparse
import io
import json
import math
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from xml.etree import ElementTree as ET

import resvg_py
from PIL import Image
from svgpathtools import Arc, parse_path

from add_phone_presets import add_camera_ring

ROOT = Path(__file__).resolve().parents[1]
NS = "{http://www.w3.org/2000/svg}"
ET.register_namespace("", NS[1:-1])
ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")
RING_CLEARANCE = 8  # The rainbow's outer edge extends eight native pixels beyond the hole.


def native_cameras(devices):
    cameras = {}
    for model, spec in devices.items():
        if "cutout_path" not in spec:
            continue
        path, anchor = spec["cutout_path"].rsplit("@", 1)
        arcs = parse_path(path.strip())
        if anchor != "left" or not arcs.isclosed() or not all(isinstance(a, Arc) for a in arcs):
            raise ValueError(f"{model} requires a different cutout projection")
        center, radius = arcs[0].center, arcs[0].radius.real
        if any(abs(a.center - center) > .001 or abs(a.radius - complex(radius, radius)) > .001 for a in arcs):
            raise ValueError(f"{model} does not have a circular cutout")
        left, right, top, bottom = arcs.bbox()
        if not all(math.isclose(v, 2 * radius, abs_tol=.001) for v in (right - left, bottom - top)):
            raise ValueError(f"{model} cutout does not describe the entire hole")
        source = spec.get("ota_source") or spec["shape_source"]
        x, y = center.real, center.imag
        if model.endswith(("_inner", "_cover")):
            cameras[model + "_portrait"] = (x, y, radius, source)
            cameras[model + "_landscape"] = (y, spec["width"] - x, radius, source)
        else:
            cameras[model] = (x, y, radius, source)
    return cameras


def save_json(path, content, check, compact=False):
    if json.loads(path.read_text()) == content:
        return
    if check:
        raise ValueError(f"Camera metadata out of date in {path.relative_to(ROOT)}")
    path.write_text(json.dumps(content, separators=(",", ":") if compact else None,
                               indent=None if compact else 2) + "\n")


def fit_wallpaper(row, profile, check):
    previous, _, model, _, width, height, style = row[:7]
    name = previous.replace("camera_outline_estimated", "camera_outline")
    vector = ROOT / "renders/svg" / previous
    svg = ET.parse(vector).getroot()
    ring = ET.fromstring(add_camera_ring(
        f'<svg xmlns="{NS[1:-1]}"><defs></defs></svg>', profile, style, width, height))
    current = [svg.find(NS + "circle")]
    expected = [ring.find(NS + "circle")]
    for i in range(6):
        for selector in (f".//*[@id='camera-{i}']", f".//*[@stroke='url(#camera-{i})']"):
            current.append(svg.find(selector))
            expected.append(ring.find(selector))
    if any(node is None for node in current):
        raise ValueError(f"Missing camera layer in {previous}")
    stale_geometry = any(a.attrib != b.attrib for a, b in zip(current, expected))
    if check and (stale_geometry or name != previous or row[7] != "published"):
        raise ValueError(f"Incorrect camera ring or source label in {previous}")
    target = ROOT / "renders/svg" / name
    png = (ROOT / "renders/png" / name).with_suffix(".png")
    if not check and (stale_geometry or name != previous or not png.exists()):
        for existing, desired in zip(current, expected):
            existing.attrib.update(desired.attrib)
        data = ET.tostring(svg, encoding="unicode") + "\n"
        target.parent.mkdir(parents=True, exist_ok=True)
        png.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(data)
        with Image.open(io.BytesIO(resvg_py.svg_to_bytes(svg_string=data, skip_system_fonts=True))) as image:
            image.convert("RGB").save(png)
        # Old release archives can no longer provide the correct PNG for this file.
        row[8:9] = [True]
    if check:
        circle = expected[0]
        cx, cy = float(circle.get("cx")), float(circle.get("cy"))
        outer = float(circle.get("r")) - 1.5
        with Image.open(png) as im:
            if im.size != (width, height) or max(im.getpixel((int(cx), int(cy)))[:3]) > 8:
                raise ValueError(f"Incorrect PNG dimensions or center in {name}")
            for i in range(6):
                angle = math.radians(i * 60 - 60)
                radius = outer * (1 - 17 / 508)
                color = im.getpixel((int(cx + radius * math.cos(angle)), int(cy + radius * math.sin(angle))))
                if max(color[:3]) < 150:
                    raise ValueError(f"Missing PNG camera-ring segment in {name}")
    row[0], row[7] = name, "published"


def main(check):
    config = ROOT / "config"
    cameras = native_cameras(json.loads((config / "google_display_specs.json").read_text())["devices"])
    legacy_file, extra_file = config / "camera_profiles.json", config / "camera_profiles_additions.json"
    legacy, extra = json.loads(legacy_file.read_text()), json.loads(extra_file.read_text())
    profiles = {row["name"]: row for row in legacy}
    for device, (cx, cy, radius, source) in cameras.items():
        profile = profiles[device] if device in profiles else extra[device]
        profile.update(cx=round(cx, 4), cy=round(cy, 4), cutout_radius=radius,
                       accuracy="published", source=source)
        if device in extra:
            profile["radius"] = radius + RING_CLEARANCE
    save_json(legacy_file, legacy, check)
    save_json(extra_file, extra, check)

    catalog_file = ROOT / "site/catalog.json"
    catalog = json.loads(catalog_file.read_text())
    theme = next(t for t in catalog["themes"] if t["id"] == "rgb-geometry")
    rows = [r for r in theme["files"] if r[2] in cameras and r[7]]
    # The offline adjuster embeds its profiles so it can run from the ZIP.
    adjuster_file = config / "Camera_Ring_Adjuster.html"
    html = adjuster_file.read_text()
    prefix, body = html.split("const profiles=", 1)
    _, code = body.split(",ring=new Image()", 1)
    adjuster_profiles = list(legacy)
    for model in cameras:
        if model in extra:
            width, height = next(r[4:6] for r in rows if r[2] == model)
            adjuster_profiles.append({**extra[model], "name": model, "width": width, "height": height})
    adjusted = prefix + "const profiles=" + json.dumps(adjuster_profiles) + ",ring=new Image()" + code
    if html != adjusted:
        if check:
            raise ValueError("The offline adjuster's profiles are out of date")
        adjuster_file.write_text(adjusted)
    if {r[2] for r in rows} != set(cameras):
        raise ValueError("Cutout and catalog camera profiles disagree")

    def process(row):
        model = row[2]
        profile = extra.get(model)
        if profile is None:
            profile = {**profiles[model], "radius": profiles[model]["cutout_radius"] + RING_CLEARANCE}
        fit_wallpaper(row, profile, check)

    updated = {}
    for row in rows:
        updated[row[0]] = row
    with ThreadPoolExecutor(max_workers=4) as workers:
        list(workers.map(process, rows))
    # Rendering takes time. Preserve independent catalog updates made in the meantime.
    catalog = json.loads(catalog_file.read_text())
    theme = next(t for t in catalog["themes"] if t["id"] == "rgb-geometry")
    for row in theme["files"]:
        changed = updated.get(row[0])
        if changed is not None:
            row[0], row[7] = changed[0], changed[7]
            if len(changed) > 8 and changed[8]:
                row[8:9] = [True]
    save_json(catalog_file, catalog, check, compact=True)
    for manifest in (config / "camera_files.json", ROOT / "renders/svg/files.json"):
        entries = json.loads(manifest.read_text())
        for item in entries:
            model = item.get("camera_profile")
            if model not in cameras:
                continue
            x, y, radius, _ = cameras[model]
            name = item["file"].replace("camera_outline_estimated", "camera_outline")
            item.update(file=name, camera_accuracy="published", camera_center=[round(x, 4), round(y, 4)],
                        ring_outer_radius=radius + RING_CLEARANCE)
            if "bytes" in item:
                item["bytes"] = (ROOT / "renders/svg" / name).stat().st_size
        save_json(manifest, entries, check)
    if not check:
        for old, row in updated.items():
            if old != row[0]:
                (ROOT / "renders/svg" / old).unlink(missing_ok=True)
                (ROOT / "renders/png" / old).with_suffix(".png").unlink(missing_ok=True)
    print(f"{len(cameras)} published Pixel camera profiles, {len(rows)} wallpaper variants.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    main(parser.parse_args().check)
