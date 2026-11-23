"""Crop the continuous lattice at the RGB frame, preserving the original linework."""
import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile

from render_vectors import frame_svg


NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", NS)
PIXEL = "pixel_11_pro_xl"
PIXEL_BASE = "pixel_9_pro_xl"
VERSION = "v1.2.3"


def num(n):
    return f"{n:.3f}".rstrip("0").rstrip(".")


@dataclass(frozen=True)
class Frame:
    left: float
    top: float
    right: float
    bottom: float
    radius: float


def fit_pixel_border(root, frame, dock):
    inset = root.find(f".//*[@id='android-wallpaper-inset']")
    width, height = (int(root.get(k)) for k in ("width", "height"))
    # Both screenshots show a centered 1.10x crop. Target a 16-display-pixel
    # centerline inset, about 8 pixels in the supplied half-size screenshots.
    margin, zoom = 16, 1.10
    sx = (width - 2 * margin) / ((frame.right - frame.left) * zoom)
    sy = (height - 2 * margin) / ((frame.bottom - frame.top) * zoom)
    tx, ty = width * (1 - sx) / 2, height * (1 - sy) / 2
    inset.set("transform", f"translate({num(tx)} {num(ty)}) scale({num(sx)} {num(sy)})")
    if dock:
        panel = root.find(".//*[@id='pixel-launcher-panel']")
        wrapper = next(node for node in root if inset in node)
        wrapper.attrib.pop("transform")
        bottom = (float(panel.get("y")) - 30 - ty) / sy
        def_group = root.find(f"{{{NS}}}defs")
        sides = ("top", "right", "bottom", "left")
        colors = {}
        for side in sides:
            old = def_group.find(f"./*[@id='frame-{side}']")
            colors[side] = [(float(stop.get("offset")), stop.get("stop-color")) for stop in old]
            def_group.remove(old)
        gradients, paths = frame_svg(width, bottom + frame.top, colors, frame.radius)
        def_group.extend(ET.fromstring(f'<defs xmlns="{NS}">{gradients}</defs>'))
        for side in sides:
            inset.remove(inset.find(f"./*[@stroke='url(#frame-{side})']"))
        inset.extend(ET.fromstring(f'<g xmlns="{NS}">{paths}</g>'))
        return Frame(frame.left, frame.top, frame.right, bottom, frame.radius)
    return frame


def refine(svg, pixel=False, dock=False):
    root = ET.fromstring(svg)
    defs = root.find(f"{{{NS}}}defs")
    gradient = defs.find("./*[@id='frame-top']")
    bottom = defs.find("./*[@id='frame-bottom']")
    first_side = root.find(".//*[@stroke='url(#frame-top)']")
    arc = re.search(r"A([\d.]+),", first_side.get("d"))
    frame = Frame(float(gradient.get("x1")), float(gradient.get("y1")),
                  float(gradient.get("x2")), float(bottom.get("y1")),
                  float(arc.group(1)) if arc else 0)
    if pixel:
        frame = fit_pixel_border(root, frame, dock)
        first_side = root.find(".//*[@stroke='url(#frame-top)']")

    pattern = root.find(".//*[@clip-path='url(#pattern-clip)']")
    parent = next(node for node in root.iter() if pattern in node)
    defs.remove(defs.find("./*[@id='pattern-clip']"))
    pattern.attrib.pop("clip-path")
    # The final child is the old cutoff cap path. The original lattice and
    # any cube spokes remain as they were.
    pattern.remove(pattern[-1])

    clip = ET.SubElement(defs, f"{{{NS}}}clipPath", {"id": "rgb-frame-clip"})
    ET.SubElement(clip, f"{{{NS}}}rect", {
        "x": num(frame.left), "y": num(frame.top),
        "width": num(frame.right - frame.left), "height": num(frame.bottom - frame.top),
        "rx": num(frame.radius),
    })
    wrapper = ET.Element(f"{{{NS}}}g", {"clip-path": "url(#rgb-frame-clip)"})
    parent.remove(pattern)
    wrapper.append(pattern)
    parent.insert(list(parent).index(first_side), wrapper)
    root.set("data-frame-layout", "continuous-grid")
    return ET.tostring(root, encoding="unicode") + "\n"


def patch(site, output, device_index, adjuster):
    catalog = json.loads((site / "catalog.json").read_text())
    theme = catalog["themes"][0]
    art = site / "art" / theme["id"]
    rows = theme["files"]
    if any(row[2] == PIXEL and not row[7] and len(row) == 8 for row in rows):
        raise ValueError("Use a v1.2.1 catalog as input")
    sources = {}
    for row in rows:
        path, design, device, _, width, height, style, ring, *flags = row
        if device == PIXEL_BASE and not ring and not any(flags):
            sources[design, style] = (row, (art / path).read_text())
    for (design, style), (row, svg) in sources.items():
        path = f"{design}/phone/google/{style}/{PIXEL}_{row[4]}x{row[5]}_{style}.svg"
        rows.append([path, design, PIXEL, *row[3:]])
        (art / path).parent.mkdir(parents=True, exist_ok=True)
        (art / path).write_text(svg)
    theme["deviceAliases"] = [alias for alias in theme["deviceAliases"] if alias["id"] != PIXEL]

    plan = []
    for row in rows:
        path, _, device, _, width, height, style, ring, *flags = row
        if style != "rgb_border" and not (device == PIXEL and not ring and not any(flags)):
            continue
        svg = (art / path).read_text()
        if style == "rgb_border":
            svg = refine(svg, pixel=device == PIXEL, dock=len(flags) > 1 and flags[1])
        for target in (art / path, output / "renders/svg" / path):
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(svg)
        plan.append({"path": path, "width": width, "height": height})

    for field in ("pngRoot", "pngArchive"):
        theme[field] = theme[field].replace("v1.2.1", VERSION)
    data = json.dumps(catalog, separators=(",", ":")) + "\n"
    (site / "catalog.json").write_text(data)
    (output / "site/catalog.json").write_text(data)
    (output / "config/refresh_png.json").write_text(json.dumps(plan, indent=2) + "\n")
    with ZipFile(site / theme["archive"], "w", ZIP_DEFLATED, compresslevel=7) as archive:
        for row in rows:
            archive.write(art / row[0], row[0])
        archive.write(output / "docs/flagship-support.md", "README.md")
        archive.write(device_index, "Device_Sizes.txt")
        archive.write(adjuster, "Camera_Ring_Adjuster.html")
        archive.writestr("device_aliases.json", json.dumps(theme["deviceAliases"], indent=2) + "\n")
        archive.writestr("catalog.json", json.dumps(rows, separators=(",", ":")) + "\n")
        if archive.testzip() is not None:
            raise ValueError("Corrupt SVG archive")
    print(f"{len(plan)} changed SVGs, {len(rows)} total wallpapers per format")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("site", "output", "device-index", "adjuster"):
        parser.add_argument(f"--{argument}", type=Path, required=True)
    args = parser.parse_args()
    patch(args.site, args.output, args.device_index, args.adjuster)
