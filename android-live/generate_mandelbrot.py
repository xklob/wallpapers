"""Bake a repeating log-polar slice of the Mandelbrot set and its still preview.

Run with Python 3, NumPy, and Pillow. The Android build uses the checked-in art.
"""
from pathlib import Path

import numpy as np
from PIL import Image


ATLAS_WIDTH = 4096
ATLAS_HEIGHT = 2048
STILL_SIZE = 2560
CENTER = complex(-0.101096363845622, 0.956286510809142)
RADIUS = 0.0000003
# Three turns of the repelling fixed-point multiplier nearly cancel its rotation.
z = 0j
for _ in range(4):
    z = z * z + CENTER
multiplier = (2 * z) ** 3
LOG_SCALE = np.log(abs(multiplier))
TURN = np.angle(multiplier)


def shade(theta, depth):
    points = CENTER + RADIUS * np.exp(LOG_SCALE * depth + 1j * (theta + TURN * depth))
    relative = np.abs(points - CENTER).ravel()
    c = points.ravel()
    z = np.zeros_like(c)
    derivative = np.zeros_like(c)
    rgb = np.tile(np.array([4, 5, 10], dtype=np.uint8), (len(c), 1))
    remaining = np.arange(len(c))
    depths = np.broadcast_to(depth, points.shape).ravel()
    for step in range(1, 175):
        derivative = 2 * z * derivative + 1
        z = z * z + c
        magnitude = np.abs(z)
        escaped = magnitude >= 64
        if escaped.any():
            active = remaining[escaped]
            radius = magnitude[escaped]
            coordinate = step + 1 - np.log2(np.log2(radius)) + 3 * depths[active]
            distance = radius * np.log(radius) / np.abs(derivative[escaped])
            distance /= relative[active]
            hue = coordinate * .109 + .29
            wheel = np.clip(np.abs((hue[:, None] + [0, 2/3, 1/3]) % 1 * 6 - 3) - 1, 0, 1)
            glow = np.exp(-np.power(distance / .024, .50))
            ridge = np.exp(-np.power(distance / .0035, .69))
            band = (.5 + .5 * np.cos(coordinate * (2 * np.pi))) ** 6
            light = .095 + .57 * glow + .20 * ridge + .13 * band * np.exp(-distance * 3)
            rgb[active] = np.clip(3 + (20 + 238 * wheel) * light[:, None], 0, 255).astype(np.uint8)
        inside = ~escaped
        if not inside.any():
            break
        c, z, derivative, remaining = c[inside], z[inside], derivative[inside], remaining[inside]
    return rgb.reshape(*points.shape, 3)


def atlas(width, height):
    theta = ((np.arange(width) + .5) / width)[None, :] * (2 * np.pi)
    output = np.empty((height, width, 3), dtype=np.uint8)
    for y in range(0, height, 32):
        depth = ((np.arange(y, min(height, y + 32)) + .5) / height)[:, None]
        primary = shade(theta, depth)
        # The deep renders are nearly identical one scale apart. Blend the first
        # few rows to the same branch as the last rows for a periodic texture.
        if depth[0, 0] < .06:
            other = shade(theta, depth + 1)
            weight = np.clip(depth / .06, 0, 1)
            weight = (weight * weight * (3 - 2 * weight))[:, :, None]
            primary = np.rint(other * (1 - weight) + primary * weight).astype(np.uint8)
        output[y:y + len(depth)] = primary
    return output


def view(texture, width, height, phase=0):
    h, w, _ = texture.shape
    result = np.empty((height, width, 3), dtype=np.uint8)
    extent = max(width, height)
    x = ((np.arange(width) + .5) - width * .5)[None, :] / extent
    for y0 in range(0, height, 48):
        y = (height * .5 - (np.arange(y0, min(height, y0 + 48)) + .5))[:, None] / extent
        radius = np.hypot(x, y)
        log_radius = np.log(np.maximum(radius, 1e-7)) / LOG_SCALE
        a = (np.arctan2(y, x) - TURN * log_radius) / (2 * np.pi)
        u, v = (a % 1) * w - .5, ((log_radius - phase) % 1) * h - .5
        x0, z0 = np.floor(u).astype(int), np.floor(v).astype(int)
        dx, dz = (u - x0)[:, :, None], (v - z0)[:, :, None]
        top = texture[z0 % h, x0 % w] * (1 - dx) + texture[z0 % h, (x0 + 1) % w] * dx
        bottom = texture[(z0 + 1) % h, x0 % w] * (1 - dx) + texture[(z0 + 1) % h, (x0 + 1) % w] * dx
        weight = np.clip((radius * extent - .75) / 1.50, 0, 1)[:, :, None]
        weight = weight * weight * (3 - 2 * weight)
        result[y0:y0 + len(y)] = np.rint(np.array([4, 5, 10]) * (1 - weight)
                                       + (top * (1 - dz) + bottom * dz) * weight).astype(np.uint8)
    return result


if __name__ == "__main__":
    out = Path(__file__).parent / "res/drawable-nodpi"
    texture = atlas(ATLAS_WIDTH, ATLAS_HEIGHT)
    Image.fromarray(texture).save(out / "mandelbrot_atlas.webp", lossless=True, method=6)
    Image.fromarray(view(texture, STILL_SIZE, STILL_SIZE)).save(out / "mandelbrot_still.webp",
                                                              lossless=True, method=6)
    print(f"Mandelbrot scale {LOG_SCALE:.12f}, turn {TURN:.12f}")
