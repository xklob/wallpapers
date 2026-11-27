"""Replace inset hexagon outlines with one connected, deduplicated honeycomb."""
import json
import math
from pathlib import Path
import re
from statistics import median
from xml.etree import ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"
ET.register_namespace("", NS[1:-1])
ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")


def connect(svg):
    root = ET.fromstring(svg)
    outline = root.find(".//*[@stroke='url(#rainbow)']")
    if outline.get("data-connected-honeycomb"):
        return svg
    cells = []
    for polygon in outline.get("d").rstrip("Z").split("Z"):
        points = [tuple(map(float, p.split(","))) for p in re.split("M|L", polygon)[1:]]
        if len(points) != 6:
            raise ValueError("Expected six-sided source honeycomb cells")
        cells.append((points[0][0], (points[0][1] + points[3][1]) / 2))
    rows = []
    for cell in sorted(cells, key=lambda p: p[1]):
        if not rows or cell[1] - rows[-1][0][1] > 4:
            rows.append([])
        rows[-1].append(cell)
    ys = [median(y for _, y in row) for row in rows]
    xs = [sorted(x for x, _ in row) for row in rows]
    dx = median(b - a for row in xs for a, b in zip(row, row[1:]))
    dy = median(b - a for a, b in zip(ys, ys[1:]))
    middle = len(rows) // 2
    x0, y0 = xs[middle][0], ys[middle]
    if min(dx, dy) <= 0:
        raise ValueError("Invalid honeycomb pitch")

    # Integer lattice addresses make both ends of every shared edge identical.
    # Each regular pointy hexagon has width dx and height 4*dy/3.
    edges = set()
    vertices = ((0, -2), (1, -1), (1, 1), (0, 2), (-1, 1), (-1, -1))
    left = min(x for x, _ in cells) - dx
    right = max(x for x, _ in cells) + dx
    for row in range(-middle - 1, len(rows) - middle + 1):
        for col in range(math.floor((left - x0) / dx) - 1, math.ceil((right - x0) / dx) + 1):
            points = [(2 * col + (row & 1) + x, 3 * row + y) for x, y in vertices]
            for a, b in zip(points, points[1:] + points[:1]):
                edges.add(tuple(sorted((a, b))))

    def point(p):
        return f"{x0 + p[0] * dx / 2:.6f},{y0 + p[1] * dy / 3:.6f}"

    outline.set("d", "".join(f"M{point(a)}L{point(b)}" for a, b in sorted(edges)))
    outline.set("stroke-linecap", "round")
    outline.set("data-connected-honeycomb", " ".join(f"{n:.9g}" for n in (x0, y0, dx, dy)))
    # The old cap paths close individual inset cells along the crop. They would
    # reintroduce parallel segments after the cells have been joined.
    parents = {child: parent for parent in root.iter() for child in parent}
    for path in list(root.iter()):
        if path is not outline and path.get("stroke") == "url(#rainbow)":
            parents[path].remove(path)
    return ET.tostring(root, encoding="unicode") + "\n"


def main():
    root = Path(__file__).resolve().parents[1]
    theme = json.loads((root / "site/catalog.json").read_text())["themes"][0]
    plan = []
    for row in theme["files"]:
        if row[1] != "01_honeycomb_bright":
            continue
        path = root / "renders/svg" / row[0]
        path.write_text(connect(path.read_text()))
        plan.append(dict(path=row[0], width=row[4], height=row[5]))
    (root / "config/refresh_png.json").write_text(json.dumps(plan, indent=2) + "\n")
    manifest = root / "renders/svg/files.json"
    records = json.loads(manifest.read_text())
    for row in records:
        path = root / "renders/svg" / row["file"]
        if path.exists():
            row["bytes"] = path.stat().st_size
    manifest.write_text(json.dumps(records, indent=2) + "\n")
    print(f"Connected {len(plan)} honeycomb SVGs; native PNG refresh plan updated.")


if __name__ == "__main__":
    main()
