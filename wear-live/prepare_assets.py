#!/usr/bin/env python3
"""Render the honeycomb dial, launcher icon, and bundle Space Grotesk for wear-live."""

import argparse
import math
import shutil
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

SIZE = 456
CENTER = SIZE / 2
SCALE = 4
PALETTE = (
    (255, 18, 37), (255, 106, 0), (255, 238, 0), (110, 255, 5),
    (0, 255, 138), (0, 236, 255), (0, 91, 255),
    (94, 11, 255), (227, 0, 255), (255, 0, 137), (255, 18, 37),
)


def spectrum(phase):
    stops = np.linspace(0, 1, len(PALETTE))
    return np.stack([np.interp(phase % 1.0, stops, channel) for channel in zip(*PALETTE)], axis=-1)


def render_honeycomb():
    resolution = SIZE * SCALE
    coordinates = (np.arange(resolution, dtype=np.float32) + 0.5) / SCALE - CENTER
    x, y = np.meshgrid(coordinates, coordinates, sparse=True)
    radius = np.hypot(x, y)

    cells = Image.new("L", (resolution, resolution))
    drawing = ImageDraw.Draw(cells)
    pitch = 32.4
    radius_cell = pitch - 1.35
    pitch_x = math.sqrt(3) * pitch
    for row in range(-5, 6):
        center_y = CENTER + row * pitch * 1.5
        for column in range(-5, 6):
            center_x = CENTER + (column + 0.5 * (row % 2)) * pitch_x
            points = [
                ((center_x + radius_cell * math.sin(vertex * math.pi / 3)) * SCALE,
                 (center_y - radius_cell * math.cos(vertex * math.pi / 3)) * SCALE)
                for vertex in range(6)
            ]
            drawing.line(points + points[:1], fill=255, width=round(1.65 * SCALE), joint="curve")

    core_distance = np.hypot(x / 190, y / 128)
    opening = np.clip((core_distance - 0.74) / 0.31, 0, 1)
    cell_alpha = (np.asarray(cells) / 255.0) * opening * opening * (radius < 211.5) * 0.78
    cells_rgb = spectrum(0.5 + (x + y * 0.83) / 555.0) * cell_alpha[:, :, None]

    ring_radius = 219
    ring_distance = np.abs(radius - ring_radius)
    ring_alpha = np.clip(2.0 - ring_distance, 0, 1)
    ring_glow = 0.12 * np.exp(-(ring_distance / 3.6) ** 2)
    ring_hue = np.arctan2(y, x) / (2 * math.pi) + 0.44
    ring_rgb = spectrum(ring_hue) * np.maximum(ring_alpha, ring_glow)[:, :, None]

    return Image.fromarray(np.uint8(np.clip(cells_rgb + ring_rgb, 0, 255)))


def render_icon(artwork, font_path):
    icon = artwork.copy()
    drawing = ImageDraw.Draw(icon)
    font = ImageFont.truetype(str(font_path), round(112 * SCALE))
    try:
        font.set_variation_by_axes([700])
    except Exception:
        pass
    drawing.text((CENTER * SCALE, CENTER * SCALE), "LIVE", font=font, fill="#F5F4FF", anchor="mm")
    return icon.resize((192, 192), Image.Resampling.LANCZOS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path, required=True)
    parser.add_argument("--res", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()

    drawables = args.res / "drawable-nodpi"
    drawables.mkdir(parents=True, exist_ok=True)
    args.assets.mkdir(parents=True, exist_ok=True)

    font_dest = args.assets / "space_grotesk.ttf"
    shutil.copyfile(args.font, font_dest)

    artwork = render_honeycomb()
    artwork.resize((SIZE, SIZE), Image.Resampling.LANCZOS).save(args.assets / "honeycomb.png", optimize=True)
    render_icon(artwork, font_dest).save(drawables / "ic_launcher.png", optimize=True)


if __name__ == "__main__":
    main()
