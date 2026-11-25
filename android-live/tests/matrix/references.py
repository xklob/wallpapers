"""Build device selections and independent resvg reference frames from the website catalog."""
import json
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from xml.etree import ElementTree as ET

import resvg_py

ROOT = Path(__file__).resolve().parents[3]
OUTPUT = Path(sys.argv[1])
ZOOM = float(sys.argv[2])
CATALOG = next(theme for theme in json.loads((ROOT / "site/catalog.json").read_text())["themes"]
               if theme["id"] == "rgb-geometry")
VIEWPORTS = json.loads((ROOT / "config/camera_profiles_additions.json").read_text())["pixel_11_pro_xl"]["wallpaper_viewports"]
ET.register_namespace("", "http://www.w3.org/2000/svg")


def reference(args):
    index, row = args
    path, _, preset, _, width, height, style = row[:7]
    root = ET.parse(ROOT / "renders/svg" / path).getroot()
    # Keep the exact aspect, up to a pixel of rounding at preview size.
    scale = min(1, 320 / max(width, height))
    w, h = max(1, round(width * scale)), max(1, round(height * scale))

    inset = root.find(".//*[@id='android-wallpaper-inset']")
    if preset == "pixel_11_pro_xl":
        viewport = VIEWPORTS[style]
        zoom = viewport["zoom"]
        x = width / 2 - (width / 2 + viewport["shift_x"]) / zoom
        y = height / 2 - (height / 2 + viewport["shift_y"]) / zoom
        root.set("viewBox", f"{x} {y} {width / zoom} {height / zoom}")
    elif inset is not None and "transform" in inset.attrib:
        del inset.attrib["transform"]
    # Match the exact preview viewport. Rasterize each transform independently
    # so thin lines are sampled once, as on Android.
    root.set("width", str(w))
    root.set("height", str(h))
    root.set("preserveAspectRatio", "xMidYMid slice")
    def save(svg, suffix):
        png = resvg_py.svg_to_bytes(svg_string=ET.tostring(svg, encoding="unicode"),
                                    skip_system_fonts=True, background="#000000")
        (OUTPUT / "gold" / f"{index}{suffix}.png").write_bytes(png)

    save(root, "")
    outer = ET.Element(root.tag, {"width": str(w), "height": str(h)})
    group = ET.SubElement(outer, "{http://www.w3.org/2000/svg}g", {
        "transform": f"translate({w / 2} {h / 2}) scale({1 / ZOOM}) translate({-w / 2} {-h / 2})"
    })
    group.append(root)
    save(outer, "-static")
    if row[7]:
        circle = root.find("{*}circle")
        x, y, view_width, view_height = map(float, root.get("viewBox").split())
        cx = (float(circle.get("cx")) - x) * width / view_width
        cy = (float(circle.get("cy")) - y) * height / view_height
        radius = float(circle.get("r")) * width / view_width
        return index, cx, cy, radius


def main():
    files = CATALOG["files"]
    aliases = CATALOG["deviceAliases"]
    hidden = set(CATALOG["hiddenPresets"])
    devices = {r[2]: {"id": r[2], "preset": r[2]} for r in files if not r[7] and r[2] not in hidden}
    devices.update((a["id"], a) for a in aliases)
    designs = list(CATALOG["designs"])
    (OUTPUT / "gold").mkdir(parents=True, exist_ok=True)
    unique = {}
    selections = []
    for device in sorted(devices.values(), key=lambda d: d["id"]):
        for row in files:
            path, design, preset, _, width, height, style, camera, _, dock, status = (row + [False] * 3)[:11]
            if dock or status:
                eligible = device["id"] == preset
            elif camera:
                eligible = device.get("cameraPreset", device["id"] if device["id"] == device["preset"] else None) == preset
            else:
                eligible = device["preset"] == preset
            if not eligible:
                continue
            mask = 8 * (style == "rgb_border") + 4 * bool(status) + 2 * bool(dock) + bool(camera)
            index = unique.setdefault(path, (len(unique), row))[0]
            selections.append((device["id"], designs.index(design), mask, width, height, path, index))
    (OUTPUT / "cases.tsv").write_text("".join("\t".join(map(str, case)) + "\n" for case in selections))
    with ProcessPoolExecutor(max_workers=4) as pool:
        rings = [row for row in pool.map(reference, unique.values(), chunksize=8) if row is not None]
    (OUTPUT / "rings.tsv").write_text("".join("\t".join(map(str, ring)) + "\n" for ring in rings))
    print(f"{len(devices)} devices, {len(selections)} selections, {len(unique)} distinct images.")


if __name__ == "__main__":
    main()
