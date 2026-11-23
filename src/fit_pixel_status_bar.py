"""Fit the outer frame and generate the Pixel 11 Pro XL status-bar variants."""
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
DEVICE = "pixel_11_pro_xl"
OLD_VERSION = "v1.2.4"
VERSION = "v1.2.6"


@dataclass(frozen=True)
class Viewport:
    width: int
    height: int
    zoom: float
    shift_x: float
    shift_y: float

    def unproject(self, x, y):
        return (self.width / 2 + (x - self.width / 2 - self.shift_x) / self.zoom,
                self.height / 2 + (y - self.height / 2 - self.shift_y) / self.zoom)


def find(root, identifier):
    return root.find(f".//*[@id='{identifier}']")


def fit_outer_frame(root, viewport, layout):
    inset = find(root, "android-wallpaper-inset")
    tx, ty, sx, sy = map(float, re.findall(r"[0-9.]+", inset.get("transform")))
    clip = find(root, "rgb-frame-clip")[0]
    left, top, old_width, old_height = (float(clip.get(key)) for key in ("x", "y", "width", "height"))
    margin = layout["outer_frame_inset"]
    ax, ay = viewport.unproject(margin, margin)
    bx, by = viewport.unproject(viewport.width - margin, viewport.height - margin)
    x, y = (ax - tx) / sx, (ay - ty) / sy
    right, bottom = (bx - tx) / sx, (by - ty) / sy
    fx, fy = (right - x) / old_width, (bottom - y) / old_height
    radius = layout["outer_frame_radius"] / (viewport.zoom * (sx * fx + sy * fy) / 2)

    defs = root.find(f"{{{NS}}}defs")
    colors = {}
    for side in ("top", "right", "bottom", "left"):
        gradient = find(defs, f"frame-{side}")
        colors[side] = [(float(stop.get("offset")), stop.get("stop-color")) for stop in gradient]
        defs.remove(gradient)
        inset.remove(inset.find(f"./*[@stroke='url(#frame-{side})']"))
    gradients, strokes = frame_svg(viewport.width, viewport.height, colors, radius)
    defs.extend(ET.fromstring(f'<defs xmlns="{NS}">{gradients}</defs>'))
    group = ET.fromstring(f'<g xmlns="{NS}">{strokes}</g>')
    group.set("transform", f"translate({x-left*fx:.6f} {y-top*fy:.6f}) scale({fx:.6f} {fy:.6f})")
    inset.append(group)
    for key, value in zip(("x", "y", "width", "height", "rx", "ry"),
                          (x, y, right - x, bottom - y, radius * fx, radius * fy)):
        clip.set(key, f"{value:.6f}")


def add_status_outline(root, viewport, layout):
    sx = viewport.width / layout["reference_width"]
    sy = viewport.height / layout["reference_height"]
    x, y = viewport.unproject(layout["left"] * sx, layout["top"] * sy)
    right, bottom = viewport.unproject(layout["right"] * sx, layout["bottom"] * sy)
    width, height = right - x, bottom - y
    top_radius = layout["top_radius"] * sx / viewport.zoom
    bottom_radius = layout["bottom_radius"] * sx / viewport.zoom
    stroke = layout["stroke_width"] * sx / viewport.zoom

    defs = root.find(f"{{{NS}}}defs")
    gradient = ET.SubElement(defs, f"{{{NS}}}linearGradient", {
        "id": "status-outline", "x1": "0%", "y1": "0%", "x2": "100%", "y2": "100%",
    })
    for offset, color in (("0", "#ff4000"), (".39", "#ffed00"), (".68", "#59ff00"), ("1", "#00bdff")):
        ET.SubElement(gradient, f"{{{NS}}}stop", {"offset": offset, "stop-color": color})

    # Keep the top corners aligned with the display and use shallower bottom curves.
    bottom_curve_height = min(bottom_radius, height - top_radius)
    if bottom_curve_height <= 0:
        raise ValueError("Status outline must be taller than its top corner radius")
    bottom_curve_top = bottom - bottom_curve_height
    d = (f"M{x + top_radius:.3f},{y:.3f} H{right - top_radius:.3f} "
         f"A{top_radius:.3f},{top_radius:.3f} 0 0 1 {right:.3f},{y + top_radius:.3f} "
         f"V{bottom_curve_top:.3f} "
         f"A{bottom_radius:.3f},{bottom_curve_height:.3f} 0 0 1 {right - bottom_radius:.3f},{bottom:.3f} "
         f"H{x + bottom_radius:.3f} "
         f"A{bottom_radius:.3f},{bottom_curve_height:.3f} 0 0 1 {x:.3f},{bottom_curve_top:.3f} "
         f"V{y + top_radius:.3f} A{top_radius:.3f},{top_radius:.3f} 0 0 1 {x + top_radius:.3f},{y:.3f} Z")
    panel = ET.Element(f"{{{NS}}}path", {
        "id": "pixel-status-panel", "d": d, "fill": "#000",
        "stroke": "url(#status-outline)", "stroke-width": f"{stroke:.3f}",
    })
    # The existing black cutout and ring sit over this clear strip.
    ring = root.find(f"{{{NS}}}circle")
    root.insert(list(root).index(ring) if ring is not None else len(root), panel)


def patch(site, output, layout_file, camera_file):
    catalog_file = site / "catalog.json"
    catalog = json.loads(catalog_file.read_text())
    theme = catalog["themes"][0]
    if not theme["pngRoot"].endswith(f"/{OLD_VERSION}/renders/png/"):
        raise ValueError(f"Expected a {OLD_VERSION} catalog")
    layout = json.loads(layout_file.read_text())
    viewports = json.loads(camera_file.read_text())[DEVICE]["wallpaper_viewports"]
    art = site / "art" / theme["id"]
    rows = [row for row in theme["files"] if row[2] == DEVICE]
    if len(rows) != 8 * len(theme["designs"]):
        raise ValueError("Expected all eight Pixel 11 Pro XL variants for each pattern")
    modified = []

    def write(path, root, width, height):
        payload = ET.tostring(root, encoding="unicode") + "\n"
        (art / path).write_text(payload)
        destination = output / "renders/svg" / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(payload)
        modified.append({"path": path, "width": width, "height": height})

    for path, design, device, family, width, height, style, camera, *flags in rows:
        view = Viewport(width, height, **viewports[style])
        root = ET.parse(art / path).getroot()
        if style == "rgb_border":
            fit_outer_frame(root, view, layout)
            write(path, root, width, height)
        add_status_outline(root, view, layout["status_outline"])
        status_path = path.removesuffix(".svg") + "_status_bar.svg"
        write(status_path, root, width, height)
        theme["files"].append([
            status_path, design, device, family, width, height, style, camera,
            False, len(flags) > 1 and flags[1], True,
        ])

    for field in ("pngRoot", "pngArchive"):
        theme[field] = theme[field].replace(OLD_VERSION, VERSION)
    data = json.dumps(catalog, separators=(",", ":")) + "\n"
    catalog_file.write_text(data)
    (output / "site/catalog.json").write_text(data)
    (output / "config/refresh_png.json").write_text(json.dumps(modified, indent=2) + "\n")

    archive_path = site / theme["archive"]
    temp = archive_path.with_suffix(".tmp")
    with ZipFile(archive_path) as previous, ZipFile(temp, "w", ZIP_DEFLATED) as archive:
        for row in theme["files"]:
            archive.write(art / row[0], row[0])
        for name in ("Device_Sizes.txt", "Camera_Ring_Adjuster.html"):
            archive.writestr(name, previous.read(name))
        archive.write(output / "docs/flagship-support.md", "README.md")
        archive.writestr("device_aliases.json", json.dumps(theme["deviceAliases"], indent=2) + "\n")
        archive.writestr("catalog.json", json.dumps(theme["files"], separators=(",", ":")) + "\n")
    temp.replace(archive_path)
    print(f"Updated {len(modified)} SVGs, including {len(rows)} status bar variants")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("site", "output", "layout", "camera"):
        parser.add_argument(f"--{argument}", type=Path, required=True)
    args = parser.parse_args()
    patch(args.site, args.output, args.layout, args.camera)
