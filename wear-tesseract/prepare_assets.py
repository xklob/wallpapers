#!/usr/bin/env python3
"""Render Poly's neon solid-front / dotted-rear launcher icon and bundle Space Grotesk."""

import argparse
import colorsys
import math
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

SIZE = 192
SCALE = 4


def render_icon():
    res = SIZE * SCALE
    center = res / 2.0
    base = Image.new("RGBA", (res, res), (0, 0, 0, 255))
    glow = Image.new("RGBA", (res, res), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    cdraw = ImageDraw.Draw(base)

    # Rainbow outer bezel ring
    ring_r = res * 0.46
    steps = 180
    for s in range(steps):
        a0 = (s / steps) * math.tau
        a1 = ((s + 1.5) / steps) * math.tau
        r, g, b = colorsys.hsv_to_rgb((s / steps) % 1.0, 1.0, 1.0)
        col = (int(r * 255), int(g * 255), int(b * 255), 255)
        p0 = (center + ring_r * math.cos(a0), center + ring_r * math.sin(a0))
        p1 = (center + ring_r * math.cos(a1), center + ring_r * math.sin(a1))
        cdraw.line([p0, p1], fill=col, width=round(4.5 * SCALE))

    # Poly's iconic cube at three-quarter view: dotted rear edges, glowing solid front edges
    verts = []
    yaw, elev = math.radians(30), math.radians(20)
    cy, sy = math.cos(yaw), math.sin(yaw)
    ce, se = math.cos(elev), math.sin(elev)
    inv_r = 1.0 / math.sqrt(3.0)
    rad = res * 0.34
    for i in range(8):
        x = (1.0 if (i & 1) else -1.0) * inv_r
        y = (1.0 if (i & 2) else -1.0) * inv_r
        z = (1.0 if (i & 4) else -1.0) * inv_r
        xx = x * cy + z * sy
        zz = -x * sy + z * cy
        yy = y * ce - zz * se
        verts.append((center + xx * rad, center - yy * rad))

    # Cube faces & normals
    faces = [
        ((0, 1, 3, 2), (0, 0, -1)),
        ((4, 5, 7, 6), (0, 0, 1)),
        ((0, 2, 6, 4), (-1, 0, 0)),
        ((1, 3, 7, 5), (1, 0, 0)),
        ((0, 1, 5, 4), (0, -1, 0)),
        ((2, 3, 7, 6), (0, 1, 0)),
    ]
    visible_face = []
    for _, (nx, ny, nz) in faces:
        zz = -nx * sy + nz * cy
        rz = ny * se + zz * ce
        visible_face.append(rz > 1e-6)

    edge_faces = {}
    for fi, (ids, _) in enumerate(faces):
        for k in range(4):
            a, b = ids[k], ids[(k + 1) % 4]
            key = (min(a, b), max(a, b))
            edge_faces.setdefault(key, []).append(fi)

    rear = []
    front = []
    for (a, b), fis in edge_faces.items():
        if any(visible_face[fi] for fi in fis):
            front.append((a, b))
        else:
            rear.append((a, b))

    # Rear dotted edges
    for idx, (a, b) in enumerate(rear):
        x0, y0 = verts[a]
        x1, y1 = verts[b]
        r, g, b_c = colorsys.hsv_to_rgb((0.35 + idx * 0.14) % 1.0, 0.9, 1.0)
        col = (int(r * 255), int(g * 255), int(b_c * 255), 225)
        n_dots = 7
        for s in range(n_dots + 1):
            t = s / n_dots
            px, py = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
            dr = 3.6 * SCALE
            cdraw.ellipse((px - dr, py - dr, px + dr, py + dr), fill=col)

    # Front solid glowing edges
    for idx, (a, b) in enumerate(front):
        r, g, b_c = colorsys.hsv_to_rgb((0.18 + idx * 0.08) % 1.0, 0.95, 1.0)
        p0, p1 = verts[a], verts[b]
        gdraw.line([p0, p1], fill=(int(r * 255), int(g * 255), int(b_c * 255), 200), width=round(11 * SCALE))
        cdraw.line([p0, p1], fill=(int(r * 255), int(g * 255), int(b_c * 255), 255), width=round(5.0 * SCALE))

    glow = glow.filter(ImageFilter.GaussianBlur(radius=6 * SCALE))
    out = Image.alpha_composite(base, glow)
    cdraw2 = ImageDraw.Draw(out)
    for idx, (a, b) in enumerate(front):
        r, g, b_c = colorsys.hsv_to_rgb((0.18 + idx * 0.08) % 1.0, 0.70, 1.0)
        cdraw2.line([verts[a], verts[b]], fill=(int(r * 255), int(g * 255), int(b_c * 255), 255), width=round(3.6 * SCALE))

    return out.convert("RGB").resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path, required=True)
    parser.add_argument("--res", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()

    drawables = args.res / "drawable-nodpi"
    drawables.mkdir(parents=True, exist_ok=True)
    args.assets.mkdir(parents=True, exist_ok=True)

    shutil.copyfile(args.font, args.assets / "space_grotesk.ttf")
    render_icon().save(drawables / "ic_launcher.png", optimize=True)


if __name__ == "__main__":
    main()
