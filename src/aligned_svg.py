import render_vectors as vector
from export_aligned import select_cells


def layout(design, width, height, style, polygons, stroke_factor=1, fitted=False):
    pad = round(min(width, height) * (.037 if fitted else .085 if style == "rgb_border" else .025))
    inner_width, inner_height = width - 2*pad, height - 2*pad
    if fitted:
        factor = 1254 / max(inner_width, inner_height)
        crop_width, crop_height = inner_width * factor, inner_height * factor
        box = ((1254 - crop_width) / 2, (1254 - crop_height) / 2, crop_width, crop_height)
    else:
        _, box = select_cells(design, inner_width, inner_height)
    x, y, box_width, box_height = box
    scale = min(inner_width / box_width, inner_height / box_height)
    pattern_width, pattern_height = round(box_width*scale), round(box_height*scale)
    sx, sy = pattern_width / box_width, pattern_height / box_height
    stroke = max(1.5, vector.STROKES[design["id"]] * scale * stroke_factor)
    inset = stroke / 2 + 0.5

    def project(point):
        px, py = point
        return (max(inset, min(pattern_width - 1 - inset, (px-x)*sx)),
                max(inset, min(pattern_height - 1 - inset, (py-y)*sy)))

    rect = x, y, x+box_width-1, y+box_height-1
    caps = [{"a": project(a), "b": project(b)}
            for polygon in polygons for a, b, _ in vector_caps(polygon, rect)]
    return {"pad": pad, "source_box": box, "pattern_size": (pattern_width, pattern_height),
            "cap_stroke": stroke, "caps": caps}

def vector_caps(poly, rect):
    x0, y0, x1, y1 = rect
    for axis, value, low, high in ((0,x0,y0,y1),(0,x1,y0,y1),(1,y0,x0,x1),(1,y1,x0,x1)):
        coords = [point[axis] for point in poly]
        if not min(coords)+.2 < value < max(coords)-.2:
            continue
        intersections = []
        for a,b in zip(poly, poly[1:]+poly[:1]):
            if b[axis] == a[axis]:
                continue
            t = (value-a[axis])/(b[axis]-a[axis])
            if -1e-8 <= t <= 1+1e-8:
                intersections.append(a[1-axis]+t*(b[1-axis]-a[1-axis]))
        if len(intersections) < 2:
            continue
        start, end = max(low,min(intersections)), min(high,max(intersections))
        if end-start >= 1:
            yield ((value,start),(value,end),axis) if axis==0 else ((start,value),(end,value),axis)
