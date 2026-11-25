"""Regenerate native Pixel Tablet frames, calibrated Home variants and RGB PNGs."""
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "resvg-py"]
# ///
import io
import json
import re
from pathlib import Path
from xml.etree import ElementTree as ET

from PIL import Image
import resvg_py

from add_phone_presets import add_home_panel

ROOT = Path(__file__).resolve().parents[1]
NS = "{http://www.w3.org/2000/svg}"
XLINK = "{http://www.w3.org/1999/xlink}href"
ET.register_namespace("", NS[1:-1])
ET.register_namespace("xlink", XLINK[1:XLINK.index("}")])


def fit_frame(svg, spec, layout, width, height):
    root = ET.fromstring(svg)
    defs = root.find(NS + "defs")
    clip = defs.find("*[@id='rgb-frame-clip']")
    # Google's mask uses absolute move, line and cubic coordinate pairs in
    # the tablet's natural (landscape) display space. Rotate all control points.
    perimeter = spec["display_shape"]
    if (width, height) == (spec["height"], spec["width"]):
        if not re.fullmatch(r"[MCLz0-9.\s-]+", perimeter):
            raise ValueError("Unexpected commands in Pixel Tablet display mask")
        perimeter = re.sub(r"(-?[\d.]+)\s+(-?[\d.]+)",
                           lambda p: f"{width - float(p[2]):.6f} {p[1]}", perimeter)
    elif (width, height) != (spec["width"], spec["height"]):
        raise ValueError("Pixel Tablet catalog dimensions disagree with its display mask")

    clip[:] = []
    ET.SubElement(clip, NS + "path", {"id": "tablet-display-shape", "d": perimeter})
    old_frame = root.find("*[@id='tablet-frame']")
    if old_frame is not None:
        root.remove(old_frame)
    frame = ET.SubElement(root, NS + "g", {
        "id": "tablet-frame", "clip-path": "url(#rgb-frame-clip)", "fill": "none",
    })
    # Stroke the exact silhouette inward. Each side keeps its existing gradient.
    # The final narrow black stroke leaves a uniform reveal along the panel edge.
    clearance = layout["outer_clearance_px"]
    stroke = 2 * (clearance + layout["frame_width_px"])
    corners = [(0, 0), (width, 0), (width, height), (0, height)]
    for index, side in enumerate(("top", "right", "bottom", "left")):
        old = root.find(f"*[@stroke='url(#frame-{side})']")
        if old is not None:
            root.remove(old)
        clip_id = f"tablet-{side}-clip"
        region = defs.find(f"*[@id='{clip_id}']")
        if region is None:
            region = ET.SubElement(defs, NS + "clipPath", {"id": clip_id})
        region[:] = []
        triangle = [corners[index], corners[(index + 1) % 4], (width // 2, height // 2)]
        ET.SubElement(region, NS + "polygon", {"points": " ".join(f"{x},{y}" for x, y in triangle)})
        ET.SubElement(frame, NS + "use", {
            XLINK: "#tablet-display-shape", "stroke": f"url(#frame-{side})",
            "stroke-width": str(stroke), "clip-path": f"url(#{clip_id})",
        })
    ET.SubElement(frame, NS + "use", {
        XLINK: "#tablet-display-shape", "stroke": "#000", "stroke-width": str(2 * clearance),
    })
    return ET.tostring(root, encoding="unicode") + "\n"


def write_art(relative, svg):
    vector = ROOT / "renders/svg" / relative
    raster = (ROOT / "renders/png" / relative).with_suffix(".png")
    if vector.is_file() and raster.is_file() and vector.read_text() == svg:
        return False
    vector.write_text(svg)
    png = resvg_py.svg_to_bytes(svg_string=svg, skip_system_fonts=True)
    with Image.open(io.BytesIO(png)) as image:
        image.convert("RGB").save(raster)
    return True


def main():
    catalog_path = ROOT / "site/catalog.json"
    catalog = json.loads(catalog_path.read_text())
    theme = next(t for t in catalog["themes"] if t["id"] == "rgb-geometry")
    spec = json.loads((ROOT / "config/google_display_specs.json").read_text())["devices"]["pixel_tablet"]
    layout = json.loads((ROOT / "config/pixel_tablet_layout.json").read_text())
    rows = {row[0]: row for row in theme["files"]}
    for row in list(rows.values()):
        relative, design, device, _, width, height, style, *_ = row
        if not device.startswith("pixel_tablet_") or design not in theme["designs"] or (len(row) > 9 and row[9]):
            continue
        svg = (ROOT / "renders/svg" / relative).read_text()
        if style == "rgb_border":
            svg = fit_frame(svg, spec, layout, width, height)
            if write_art(relative, svg):
                # Show the new vector until its matching native PNG is published.
                rows[relative] = [*row[:8], True]
        if device in layout["home"]:
            home = relative.removesuffix(".svg") + "_home_screen.svg"
            if write_art(home, add_home_panel(svg, layout["home"][device]["panel"])) or home not in rows:
                rows[home] = [home, *row[1:8], True, True]
    theme["files"] = list(rows.values())
    catalog_path.write_text(json.dumps(catalog, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
