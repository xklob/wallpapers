#!/usr/bin/env python3
"""Render the round, neon honeycomb dial and its system-picker preview."""

import argparse
import math
from pathlib import Path
from xml.etree import ElementTree

import numpy as np
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
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


def geometry():
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
    cell_alpha = (np.asarray(cells) / 255) * opening * opening * (radius < 211.5) * 0.78
    cells_rgb = spectrum(0.5 + (x + y * 0.83) / 555) * cell_alpha[:, :, None]

    ring_radius = 219
    ring_distance = np.abs(radius - ring_radius)
    ring_alpha = np.clip(2.0 - ring_distance, 0, 1)
    ring_glow = 0.12 * np.exp(-(ring_distance / 3.6) ** 2)
    ring_hue = np.arctan2(y, x) / (2 * math.pi) + 0.44
    ring_rgb = spectrum(ring_hue) * np.maximum(ring_alpha, ring_glow)[:, :, None]

    image = Image.fromarray(np.uint8(np.clip(cells_rgb + ring_rgb, 0, 255)))
    drawing = ImageDraw.Draw(image)
    drawing.rounded_rectangle((192*SCALE, 309*SCALE, 211*SCALE, 320*SCALE),
                              radius=2*SCALE, outline="#a4a7c0", width=SCALE)
    drawing.rounded_rectangle((195*SCALE, 312*SCALE, 204*SCALE, 317*SCALE),
                              radius=SCALE, fill="#d3d5ea")
    drawing.rectangle((212*SCALE, 312*SCALE, 213*SCALE, 317*SCALE), fill="#a4a7c0")
    return image


def create_fonts(variable_font, resource_folder):
    for name, weight in (("space_grotesk_bold", 700), ("space_grotesk_medium", 500)):
        font = TTFont(variable_font)
        instance = instantiateVariableFont(font, {"wght": weight}, updateFontNames=True)
        instance.save(resource_folder / (name + ".ttf"))


def render_preview(image, face_xml, fonts):
    result = image.copy()
    drawing = ImageDraw.Draw(result)
    scene = ElementTree.parse(face_xml).getroot().find("Scene")
    for path, text in (("Group/PartText[@name='date']/Text/Font", "MON · NOV 23"),
                       ("Group/PartText[@name='battery']/Text/Font", "68%"),
                       ("DigitalClock/TimeText/Font", "10:24")):
        font_element = scene.find(path)
        bounds = scene.find(path.split("/Text")[0] if "/Text" in path else "DigitalClock")
        x, y, width, height = (int(bounds.attrib[key]) for key in ("x", "y", "width", "height"))
        font = ImageFont.truetype(str(fonts / (font_element.attrib["family"] + ".ttf")),
                                  round(float(font_element.attrib["size"]) * SCALE))
        battery = "battery" in path
        drawing.text(((x if battery else x + width / 2)*SCALE, (y + height / 2)*SCALE),
                     text, font=font, fill=font_element.attrib["color"], anchor="lm" if battery else "mm")
    return result


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
    create_fonts(args.font, fonts)
    artwork = geometry()
    preview = render_preview(artwork, args.input, fonts)
    for name, image in (("honeycomb", artwork), ("preview", preview)):
        image.resize((SIZE, SIZE), Image.Resampling.LANCZOS).save(drawables / (name + ".png"), optimize=True)


if __name__ == "__main__":
    main()
