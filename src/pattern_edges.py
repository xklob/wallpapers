"""Align clipped hexagon sides with the adjacent complete hexagons."""
import re
from statistics import median


_CLIP = re.compile(
    r'<clipPath id="pattern-clip"><rect x="([^"]+)" y="([^"]+)" width="([^"]+)" height="([^"]+)"/></clipPath>'
)
_STROKES = re.compile(
    r'<path d="([^"]*)" fill="none" stroke="url\(#rainbow\)" stroke-width="([^"]+)"([^>]*)/>'
)


def _point(value):
    return tuple(map(float, value.split(",")))


def _format(value):
    return f"{value:.3f}".rstrip("0").rstrip(".")


def _coords(point):
    return ",".join(map(_format, point))


def _side_position(polygons, left, right, top, bottom, at_left):
    edge = left if at_left else right
    widths = [poly[1][0] - poly[5][0] for poly in polygons]
    tolerance = max(7, median(widths) * .14)
    candidates = []
    for poly in polygons:
        near, far = (poly[5][0], poly[1][0]) if at_left else (poly[1][0], poly[5][0])
        if (abs(near - edge) <= tolerance and left <= far <= right
                and top <= poly[0][1] and poly[3][1] <= bottom):
            candidates.append(near)
    if not candidates:
        return None
    nearest = min(candidates, key=lambda x: abs(x - edge))
    cluster = [x for x in candidates if abs(x - nearest) <= 5]
    return float(median(cluster)), min(cluster) - .01, max(cluster) + .01


def _vertical_cap(poly, edge, top, bottom):
    if not min(x for x, _ in poly) + .001 < edge < max(x for x, _ in poly) - .001:
        return ""
    crossings = []
    for a, b in zip(poly, poly[1:] + poly[:1]):
        if a[0] == b[0]:
            continue
        offset = (edge - a[0]) / (b[0] - a[0])
        if 0 <= offset <= 1:
            crossings.append(a[1] + offset * (b[1] - a[1]))
    if len(crossings) < 2:
        return ""
    start, end = max(top, min(crossings)), min(bottom, max(crossings))
    return f"M{_coords((edge, start))}L{_coords((edge, end))}" if end - start >= 1 else ""


def align_vertical_edges(svg):
    clip = _CLIP.search(svg)
    strokes = list(_STROKES.finditer(svg))
    if not clip or len(strokes) < 2:
        return svg
    outline, caps = strokes[0], strokes[-1]
    polygons = [[_point(point) for point in path.removeprefix("M").split("L")]
                for path in outline[1].split("Z") if path]
    if not polygons:
        return svg
    left, top, width, height = map(float, clip.groups())
    right, bottom = left + width, top + height
    half_stroke = max(float(outline[2]), float(caps[2])) / 2 + .12
    lhs = _side_position(polygons, left, right, top, bottom, True)
    rhs = _side_position(polygons, left, right, top, bottom, False)
    if not lhs and not rhs:
        return svg

    left_line = lhs[0] if lhs else left + half_stroke
    right_line = rhs[0] if rhs else right - half_stroke

    def normalize_x(x):
        for side in (lhs, rhs):
            if side and side[1] <= x <= side[2]:
                return side[0]
        return x

    polygons = [[(normalize_x(x), y) for x, y in poly] for poly in polygons]
    polygons_path = "".join("M" + "L".join(map(_coords, poly)) + "Z" for poly in polygons)
    svg = svg.replace(outline[0], outline[0].replace(outline[1], polygons_path, 1), 1)
    for spokes in strokes[1:-1]:
        path = re.sub(r'([ML])(-?[\d.]+),', lambda m: m[1] + _format(normalize_x(float(m[2]))) + ",",
                      spokes[1])
        svg = svg.replace(spokes[0], spokes[0].replace(spokes[1], path, 1), 1)

    # Preserve the horizontal caps and compute new diagonal intersections at
    # the aligned vertical edges.
    rebuilt_caps = []
    for segment in filter(None, caps[1].split("M")):
        a, b = map(_point, segment.split("L"))
        if abs(a[1] - b[1]) < .001:
            start, end = max(left_line, min(a[0], b[0])), min(right_line, max(a[0], b[0]))
            if end - start >= 1:
                rebuilt_caps.append(f"M{_coords((start, a[1]))}L{_coords((end, a[1]))}")
    for poly in polygons:
        for edge in (left_line, right_line):
            rebuilt_caps.append(_vertical_cap(poly, edge, top + half_stroke, bottom - half_stroke))
    svg = svg.replace(caps[0], caps[0].replace(caps[1], "".join(rebuilt_caps), 1), 1)
    rect = (
        f'<clipPath id="pattern-clip" data-edges="aligned"><rect x="{_format(left_line-half_stroke)}" '
        f'y="{_format(top)}" width="{_format(right_line-left_line+2*half_stroke)}" height="{_format(height)}"/></clipPath>'
    )
    return svg.replace(clip[0], rect, 1)
