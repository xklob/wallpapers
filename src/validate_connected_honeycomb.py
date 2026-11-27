"""Check shared-edge topology in every published Bright Honeycomb SVG."""
from collections import defaultdict
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET


def validate(path):
    root = ET.parse(path).getroot()
    lines = root.findall(".//*[@stroke='url(#rainbow)']")
    assert len(lines) == 1, f"Extra cell outlines or caps: {path}"
    line = lines[0]
    x0, y0, dx, dy = map(float, line.get("data-connected-honeycomb").split())
    edges, neighbors = set(), defaultdict(set)
    for a, b in re.findall(r"M([^ML]+)L([^ML]+)", line.get("d")):
        points = []
        for point in (a, b):
            x, y = map(float, point.split(","))
            ix, iy = round((x - x0) * 2 / dx), round((y - y0) * 3 / dy)
            assert abs(x - x0 - ix * dx / 2) < .00001
            assert abs(y - y0 - iy * dy / 3) < .00001
            points.append((ix, iy))
        a, b = points
        delta = (abs(b[0] - a[0]), abs(b[1] - a[1]))
        assert delta in ((0, 2), (1, 1)), f"Not a honeycomb edge: {path}"
        edge = tuple(sorted(points))
        assert edge not in edges, f"Duplicate shared edge: {path}"
        edges.add(edge); neighbors[a].add(b); neighbors[b].add(a)
    assert len(edges) > 12
    seen, todo = set(), [next(iter(neighbors))]
    while todo:
        node = todo.pop()
        if node not in seen:
            seen.add(node); todo.extend(neighbors[node] - seen)
    assert seen == set(neighbors), f"Disconnected cells: {path}"
    xmin = min(x for x, _ in seen); xmax = max(x for x, _ in seen)
    ymin = min(y for _, y in seen); ymax = max(y for _, y in seen)
    for (x, y), adjacent in neighbors.items():
        assert len(adjacent) <= 3, f"Overlapping cell edges: {path}"
        if xmin + 2 < x < xmax - 2 and ymin + 3 < y < ymax - 3:
            assert len(adjacent) == 3, f"Broken interior junction: {path}"


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    theme = json.loads((root / "site/catalog.json").read_text())["themes"][0]
    rows = [row for row in theme["files"] if row[1] == "01_honeycomb_bright"]
    for row in rows:
        validate(root / "renders/svg" / row[0])
    print(f"{len(rows)} connected honeycombs: unique edges, one component, complete three-way junctions.")
