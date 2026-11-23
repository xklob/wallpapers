#!/usr/bin/env python3
"""Render the round, hue-shifting neon honeycomb dial and its system-picker preview."""

import argparse
import math
import shutil
from pathlib import Path
from xml.etree import ElementTree

import numpy as np
from PIL import Image, ImageDraw, ImageFont

SIZE = 456
CENTER = SIZE / 2
SCALE = 4
ANIM_SCALE = 2
ANIM_FRAMES = 100
ANIM_FRAME_MS = 100
PALETTE = (
    (255, 18, 37), (255, 106, 0), (255, 238, 0), (110, 255, 5),
    (0, 255, 138), (0, 236, 255), (0, 91, 255),
    (94, 11, 255), (227, 0, 255), (255, 0, 137), (255, 18, 37),
)
WEIGHTS = {"MEDIUM": 500, "BOLD": 700, "NORMAL": 400}


def spectrum(phase):
    stops = np.linspace(0, 1, len(PALETTE))
    return np.stack([np.interp(phase % 1.0, stops, channel) for channel in zip(*PALETTE)], axis=-1)


def geometry_layers(scale):
    resolution = SIZE * scale
    coordinates = (np.arange(resolution, dtype=np.float32) + 0.5) / scale - CENTER
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
                ((center_x + radius_cell * math.sin(vertex * math.pi / 3)) * scale,
                 (center_y - radius_cell * math.cos(vertex * math.pi / 3)) * scale)
                for vertex in range(6)
            ]
            drawing.line(points + points[:1], fill=255, width=round(1.65 * scale), joint="curve")

    core_distance = np.hypot(x / 190, y / 128)
    opening = np.clip((core_distance - 0.74) / 0.31, 0, 1)
    cell_alpha = (np.asarray(cells) / 255.0) * opening * opening * (radius < 211.5) * 0.78
    cell_phase = 0.5 + (x + y * 0.83) / 555.0

    ring_radius = 219
    ring_distance = np.abs(radius - ring_radius)
    ring_alpha = np.clip(2.0 - ring_distance, 0, 1)
    ring_glow = 0.12 * np.exp(-(ring_distance / 3.6) ** 2)
    ring_weight = np.maximum(ring_alpha, ring_glow)
    ring_phase = np.arctan2(y, x) / (2 * math.pi) + 0.44
    return cell_alpha, cell_phase, ring_weight, ring_phase


def render_frame(layers, shift=0.0):
    cell_alpha, cell_phase, ring_weight, ring_phase = layers
    rgb = (
        spectrum(cell_phase + shift) * cell_alpha[:, :, None]
        + spectrum(ring_phase + shift) * ring_weight[:, :, None]
    )
    return Image.fromarray(np.uint8(np.clip(rgb, 0, 255)))


def create_battery_icon():
    w, h = 24, 14
    image = Image.new("RGBA", (w * SCALE, h * SCALE), (0, 0, 0, 0))
    drawing = ImageDraw.Draw(image)
    drawing.rounded_rectangle(
        (1 * SCALE, 1 * SCALE, 20 * SCALE, 13 * SCALE),
        radius=2.5 * SCALE,
        outline=(255, 255, 255, 220),
        width=round(1.5 * SCALE),
    )
    drawing.rounded_rectangle(
        (4 * SCALE, 4 * SCALE, 14 * SCALE, 10 * SCALE),
        radius=1 * SCALE,
        fill=(255, 255, 255, 255),
    )
    drawing.rounded_rectangle(
        (21 * SCALE, 4.5 * SCALE, 23 * SCALE, 9.5 * SCALE),
        radius=1 * SCALE,
        fill=(255, 255, 255, 220),
    )
    return image.resize((w, h), Image.Resampling.LANCZOS)


def create_heart_icon():
    size = 18
    s = size * SCALE
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    drawing = ImageDraw.Draw(image)
    r = s * 0.27
    cx1, cx2, cy = s * 0.30, s * 0.70, s * 0.34
    white = (255, 255, 255, 255)
    drawing.ellipse((cx1 - r, cy - r, cx1 + r, cy + r), fill=white)
    drawing.ellipse((cx2 - r, cy - r, cx2 + r, cy + r), fill=white)
    drawing.polygon([
        (s * 0.05, s * 0.42),
        (s * 0.95, s * 0.42),
        (s * 0.50, s * 0.90),
    ], fill=white)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def load_font(font_path, size_px, weight_name):
    font = ImageFont.truetype(str(font_path), round(size_px * SCALE))
    try:
        font.set_variation_by_axes([WEIGHTS.get(weight_name, 500)])
    except Exception:
        pass
    return font


def paste_tinted_icon(canvas, icon, part_element, color):
    x, y, w, h = (int(part_element.attrib[k]) for k in ("x", "y", "width", "height"))
    tinted = Image.new("RGBA", (w * SCALE, h * SCALE), color)
    icon_hi = icon.resize((w * SCALE, h * SCALE), Image.Resampling.LANCZOS)
    canvas.paste(tinted, (x * SCALE, y * SCALE), icon_hi.getchannel("A"))


def render_preview(image, heart_icon, battery_icon, face_xml, font_path):
    result = image.copy().convert("RGBA")
    drawing = ImageDraw.Draw(result)
    scene = ElementTree.parse(face_xml).getroot().find("Scene")

    for path, text, anchor in (
        (".//PartText[@name='date']", "MON · NOV 23", "mm"),
        (".//PartText[@name='time_12h']", "10:24", "mm"),
        (".//PartText[@name='heart_rate']", "72", "lm"),
        (".//PartText[@name='battery']", "68%", "lm"),
    ):
        part = scene.find(path)
        font_element = part.find("Text/Font")
        x, y, width, height = (int(part.attrib[key]) for key in ("x", "y", "width", "height"))
        font = load_font(font_path, float(font_element.attrib["size"]), font_element.attrib.get("weight", "NORMAL"))
        tx = (x if anchor == "lm" else x + width / 2) * SCALE
        ty = (y + height / 2) * SCALE
        drawing.text((tx, ty), text, font=font, fill=font_element.attrib["color"], anchor=anchor)

    paste_tinted_icon(result, heart_icon, scene.find(".//PartImage[@name='heart_icon']"), "#D3D5EA")
    paste_tinted_icon(result, battery_icon, scene.find(".//PartImage[@name='battery_icon']"), "#D3D5EA")
    return result.convert("RGB")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True, help="Watch face XML")
    parser.add_argument("--res", type=Path, required=True, help="Staged Android resources")
    args = parser.parse_args()
    fonts = args.res / "font"
    drawables = args.res / "drawable-nodpi"
    fonts.mkdir(parents=True, exist_ok=True)
    drawables.mkdir(parents=True, exist_ok=True)

    font_dest = fonts / "space_grotesk.ttf"
    shutil.copyfile(args.font, font_dest)

    hi_layers = geometry_layers(SCALE)
    artwork = render_frame(hi_layers, 0.0)
    heart_icon = create_heart_icon()
    battery_icon = create_battery_icon()
    heart_icon.save(drawables / "heart_icon.png", optimize=True)
    battery_icon.save(drawables / "battery_icon.png", optimize=True)

    preview = render_preview(artwork, heart_icon, battery_icon, args.input, font_dest)
    for name, image in (("honeycomb", artwork), ("preview", preview)):
        image.resize((SIZE, SIZE), Image.Resampling.LANCZOS).save(drawables / (name + ".png"), optimize=True)

    anim_layers = geometry_layers(ANIM_SCALE)
    frames = [
        render_frame(anim_layers, i / ANIM_FRAMES).resize((SIZE, SIZE), Image.Resampling.LANCZOS)
        for i in range(ANIM_FRAMES)
    ]
    frames[0].save(
        drawables / "honeycomb_anim.webp",
        save_all=True,
        append_images=frames[1:],
        duration=ANIM_FRAME_MS,
        loop=0,
        quality=85,
        method=4,
    )


if __name__ == "__main__":
    main()
