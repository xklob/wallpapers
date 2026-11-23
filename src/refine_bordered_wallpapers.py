"""Keep whole cells inside RGB frames and join exposed cells to the frame."""
import argparse
from dataclasses import dataclass
import json
from math import hypot, sqrt
from pathlib import Path
import re
from statistics import median
from xml.etree import ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile

from render_vectors import frame_svg


NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", NS)
PIXEL = "pixel_11_pro_xl"
PIXEL_BASE = "pixel_9_pro_xl"
VERSION = "v1.2.2"
PATTERN_TRANSFORM = re.compile(r"translate\(([^)]+)\) scale\(([^)]+)\) translate\(([^)]+)\)")


def num(n):
    return f"{n:.3f}".rstrip("0").rstrip(".")


def points(path):
    return [tuple(map(float, pair.split(","))) for pair in re.split("[MLZ]", path) if pair]


def path_data(vertices, closed=False):
    return "M" + "L".join(f"{num(x)},{num(y)}" for x, y in vertices) + ("Z" if closed else "")


@dataclass(frozen=True)
class Frame:
    left: float
    top: float
    right: float
    bottom: float
    radius: float

    def contains(self, x, y, gap):
        if not (self.left + gap <= x <= self.right - gap
                and self.top + gap <= y <= self.bottom - gap):
            return False
        cx = max(self.left + self.radius, min(x, self.right - self.radius))
        cy = max(self.top + self.radius, min(y, self.bottom - self.radius))
        return hypot(x - cx, y - cy) <= max(0, self.radius - gap)

    def edge(self, position, vertical, near):
        if vertical:
            start, end, across_start, across_end = self.top, self.bottom, self.left, self.right
        else:
            start, end, across_start, across_end = self.left, self.right, self.top, self.bottom
        offset = max(0, across_start + self.radius - position, position - (across_end - self.radius))
        curvature = self.radius - sqrt(max(0, self.radius ** 2 - offset ** 2))
        return start + curvature if near else end - curvature


@dataclass(frozen=True)
class Cell:
    original: list
    screen: list

    @property
    def bounds(self):
        x, y = zip(*self.screen)
        return min(x), min(y), max(x), max(y)


def exposed_links(cells, frame):
    boxes = [cell.bounds for cell in cells]
    links = []
    for vertical in (True, False):
        axis = 0 if vertical else 1
        center = [(box[axis] + box[axis + 2]) / 2 for box in boxes]
        tolerance = median(box[axis + 2] - box[axis] for box in boxes) * .20
        groups = []
        for i in sorted(range(len(boxes)), key=center.__getitem__):
            if not groups or center[i] - center[groups[-1][0]] > tolerance:
                groups.append([])
            groups[-1].append(i)

        for group in groups:
            for near in (True, False):
                bound_index = int(vertical) + (0 if near else 2)
                extreme = min if near else max
                i = extreme(group, key=lambda j: boxes[j][bound_index])
                x0, y0, x1, y1 = boxes[i]
                p = (x0 + x1) / 2 if vertical else (y0 + y1) / 2
                start = boxes[i][bound_index]
                end = frame.edge(p, vertical, near)
                low, high = sorted((start, end))
                if high - low < 0.5:
                    continue

                def crosses_cell(box):
                    a, c, b, d = box
                    if vertical:
                        a, c, b, d = c, a, d, b
                    return c < p < d and max(a, low) < min(b, high) - 0.1

                if any(crosses_cell(box) for j, box in enumerate(boxes) if j != i):
                    continue

                if vertical:
                    links.append(((p, start), (p, end)))
                elif not any(min(a[1], b[1]) < p < max(a[1], b[1])
                             and low < a[0] < high for a, b in links if a[0] == b[0]):
                    links.append(((start, p), (end, p)))
    return links


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
    if root.get("data-frame-layout") == "whole-cells":
        return svg
    gradient = root.find(".//*[@id='frame-top']")
    bottom = root.find(".//*[@id='frame-bottom']")
    first_side = root.find(".//*[@stroke='url(#frame-top)']")
    radius = re.search(r"A([\d.]+),", first_side.get("d"))
    frame = Frame(float(gradient.get("x1")), float(gradient.get("y1")),
                  float(gradient.get("x2")), float(bottom.get("y1")),
                  float(radius.group(1)) if radius else 0)
    if pixel:
        frame = fit_pixel_border(root, frame, dock)
        first_side = root.find(".//*[@stroke='url(#frame-top)']")
    pattern = root.find(".//*[@clip-path='url(#pattern-clip)']")
    parent = next(node for node in root.iter() if pattern in node)
    transform = PATTERN_TRANSFORM.fullmatch(pattern.get("transform"))
    (tx, ty), (sx, sy), (ox, oy) = [tuple(map(float, part.split())) for part in transform.groups()]

    def forward(p):
        return tx + (p[0] + ox) * sx, ty + (p[1] + oy) * sy

    def inverse(p):
        return (p[0] - tx) / sx - ox, (p[1] - ty) / sy - oy

    outline, *details, old_closures = list(pattern)
    gap = float(first_side.get("stroke-width")) / 2 + float(outline.get("stroke-width")) * max(sx, sy) / 2
    polygons = [points(path) for path in outline.get("d").split("Z") if path]
    cells = []
    for polygon in polygons:
        if len(polygon) != 6:
            raise ValueError("Expected a six-vertex outline in the RGB pattern")
        projected = list(map(forward, polygon))
        if all(frame.contains(x, y, gap) for x, y in projected):
            cells.append(Cell(polygon, projected))
    if not cells:
        raise ValueError("No whole hexagons fit the frame")

    outline.set("d", "".join(path_data(cell.original, closed=True) for cell in cells))
    # Cube spokes start at the cell center and end at one of its vertices.
    vertices = {vertex for cell in cells for vertex in cell.original}
    for detail in details:
        segments = [points(segment) for segment in detail.get("d").split("M") if segment]
        detail.set("d", "".join(path_data(segment) for segment in segments if segment[-1] in vertices))
    pattern.remove(old_closures)
    pattern.attrib.pop("clip-path")
    links = exposed_links(cells, frame)
    if not links:
        raise ValueError("No perimeter connectors reach the frame")
    ET.SubElement(pattern, f"{{{NS}}}path", {
        "id": "frame-connectors", "fill": "none", "stroke": outline.get("stroke"),
        "stroke-width": outline.get("stroke-width"), "stroke-linecap": "round",
        "d": "".join(path_data(map(inverse, segment)) for segment in links),
    })
    parent.remove(pattern)
    parent.insert(list(parent).index(first_side), pattern)
    clip = root.find(".//*[@id='pattern-clip']")
    root.find(f"{{{NS}}}defs").remove(clip)
    root.set("data-frame-layout", "whole-cells")
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
