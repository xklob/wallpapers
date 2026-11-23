"""Extend the stock Pixel 11 Pro XL frame around its home row and search bar."""
import argparse
from copy import deepcopy
import json
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile

NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", NS)
PIXEL = "pixel_11_pro_xl"
VERSION = "v1.2.4"


def extend_frame(standard, dock):
    root = ET.fromstring(standard)
    original = ET.fromstring(dock)
    defs = root.find(f"{{{NS}}}defs")
    defs.append(deepcopy(original.find(".//*[@id='home-outline']")))
    # The opaque fill clears only the dock interior. The continuous grid
    # remains visible in the space beside and below the rounded outline.
    root.append(deepcopy(original.find(".//*[@id='pixel-launcher-panel']")))
    return ET.tostring(root, encoding="unicode") + "\n"


def patch(site, output):
    catalog_file = site / "catalog.json"
    catalog = json.loads(catalog_file.read_text())
    theme = catalog["themes"][0]
    art = site / "art" / theme["id"]
    standard = {}
    dock = []
    for row in theme["files"]:
        path, design, device, _, width, height, style, camera, *flags = row
        if device != PIXEL or style != "rgb_border":
            continue
        if len(flags) > 1 and flags[1]:
            dock.append((path, design, bool(camera), width, height))
        else:
            standard[design, bool(camera)] = (art / path).read_text()

    plan = []
    for path, design, camera, width, height in dock:
        fitted = extend_frame(standard[design, camera], (art / path).read_text())
        (art / path).write_text(fitted)
        dest = output / "renders/svg" / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(fitted)
        plan.append({"path": path, "width": width, "height": height})
    if len(plan) != 2 * len(theme["designs"]):
        raise ValueError("Expected both bordered dock variants for every pattern")

    for field in ("pngRoot", "pngArchive"):
        theme[field] = theme[field].replace("v1.2.3", VERSION)
    data = json.dumps(catalog, separators=(",", ":")) + "\n"
    catalog_file.write_text(data)
    (output / "site/catalog.json").write_text(data)
    (output / "config/refresh_png.json").write_text(json.dumps(plan, indent=2) + "\n")

    archive_path = site / theme["archive"]
    temporary_path = archive_path.with_suffix(".tmp")
    revised = {entry["path"] for entry in plan}
    with ZipFile(archive_path) as previous, ZipFile(temporary_path, "w", ZIP_DEFLATED) as archive:
        for name in previous.namelist():
            if name in revised:
                archive.write(art / name, name)
            elif name == "README.md":
                archive.write(output / "docs/flagship-support.md", name)
            else:
                archive.writestr(name, previous.read(name))
        if archive.testzip() is not None:
            raise ValueError("Corrupt SVG archive")
    temporary_path.replace(archive_path)
    print(f"Updated {len(plan)} Pixel dock wallpapers")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    patch(args.site, args.output)
