#!/usr/bin/env python3
"""Render the launcher icon and bundle Space Grotesk for rgb tesseract."""

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

    # Subtle rainbow outer bezel ring
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

    # Project a 4D tesseract at a classic isometric 4D angle
    verts = []
    axw, ayz, axy, azw = 0.62, 0.48, 0.35, 0.28
    cxw, sxw = math.cos(axw), math.sin(axw)
    cyz, syz = math.cos(ayz), math.sin(ayz)
    cxy, sxy = math.cos(axy), math.sin(axy)
    czw, szw = math.cos(azw), math.sin(azw)

    for i in range(16):
        x = 0.55 if (i & 1) else -0.55
        y = 0.55 if (i & 2) else -0.55
        z = 0.55 if (i & 4) else -0.55
        w = 0.55 if (i & 8) else -0.55

        x, w = x * cxw - w * sxw, x * sxw + w * cxw
        y, z = y * cyz - z * syz, y * syz + z * cyz
        x, y = x * cxy - y * sxy, x * sxy + y * cxy
        z, w = z * czw - w * szw, z * szw + w * czw

        s4 = 1.0 / (2.35 - w)
        x3, y3, z3 = x * s4, y * s4, z * s4
        s3 = 1.0 / (2.8 - z3)
        px = center + x3 * s3 * res * 1.42
        py = center + y3 * s3 * res * 1.42
        verts.append((px, py, w + z3))

    edges = []
    for i in range(16):
        for bit in range(4):
            j = i ^ (1 << bit)
            if j > i:
                depth = 0.5 * (verts[i][2] + verts[j][2])
                edges.append((depth, i, j))
    edges.sort()

    for idx, (depth, i, j) in enumerate(edges):
        hue = (idx / len(edges) + 0.12) % 1.0
        r, g, b = colorsys.hsv_to_rgb(hue, 0.95, 1.0)
        p0 = (verts[i][0], verts[i][1])
        p1 = (verts[j][0], verts[j][1])
        gdraw.line([p0, p1], fill=(int(r * 255), int(g * 255), int(b * 255), 180), width=round(8 * SCALE))
        cdraw.line([p0, p1], fill=(int(r * 255), int(g * 255), int(b * 255), 255), width=round(3.2 * SCALE))

    glow = glow.filter(ImageFilter.GaussianBlur(radius=6 * SCALE))
    out = Image.alpha_composite(base, glow)
    cdraw2 = ImageDraw.Draw(out)
    for idx, (depth, i, j) in enumerate(edges):
        hue = (idx / len(edges) + 0.12) % 1.0
        r, g, b = colorsys.hsv_to_rgb(hue, 0.75, 1.0)
        cdraw2.line([(verts[i][0], verts[i][1]), (verts[j][0], verts[j][1])],
                    fill=(int(r * 255), int(g * 255), int(b * 255), 255), width=round(2.4 * SCALE))
    for px, py, d in verts:
        rad = round((3.4 + max(-0.5, d) * 1.4) * SCALE)
        cdraw2.ellipse((px - rad, py - rad, px + rad, py + rad), fill=(245, 248, 255, 255))

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
