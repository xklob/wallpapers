"""Bake overlapping distance-field tiles for a zoom from the full set into a real mini-set."""
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image

LEVELS = 9
MIN_SCALE = 0.0187
START = -.72
FOCUS = -1.7548776662466927 / (1 - MIN_SCALE)
SCENE_SPAN = 3.4
BASE = np.array([10., 34., 137.])
COOL = np.array([26., 116., 237.])
CYAN = np.array([136., 221., 255.])
CREAM = np.array([255., 248., 194.])
OUT = Path(__file__).parent / "res/drawable-nodpi"


def distances(x, y, iterations):
    points = x + 1j * y
    q = (points.real - .25)**2 + points.imag**2
    inside = q * (q + points.real - .25) <= .25 * points.imag**2
    inside |= np.abs(points + 1)**2 <= .0625
    remaining = np.flatnonzero(~inside)
    c = points.ravel()[remaining]
    z = np.zeros_like(c)
    derivative = np.zeros_like(c)
    result = np.zeros(points.size)
    for _ in range(iterations):
        derivative = 2 * z * derivative + 1
        z = z * z + c
        radius = np.abs(z)
        escaped = radius > 32
        r = radius[escaped]
        result[remaining[escaped]] = .5 * r * np.log(r) / np.abs(derivative[escaped])
        stay = ~escaped
        remaining, c, z, derivative = remaining[stay], c[stay], z[stay], derivative[stay]
        if not len(remaining):
            break
    return result.reshape(points.shape)


def color(distance, span=SCENE_SPAN):
    x = distance / span
    glow = np.exp(-np.power(x / .024, .73))
    blue = np.exp(-np.power(x / .006, .78))
    rim = np.exp(-np.power(x / .00115, .80))
    c = BASE + (COOL - BASE) * glow[..., None] + (CYAN - COOL) * blue[..., None] + (CREAM - CYAN) * rim[..., None]
    c[distance == 0] = 0
    return np.rint(c.clip(0, 255)).astype(np.uint8)


def extend_edge_codes(packed):
    # Extend the distance field behind the coverage edge. Filtering with an
    # interior texel should soften coverage, not inject a white fringe.
    covered = packed[..., 2] > 0
    codes = packed[..., :2]
    valid = covered
    for _ in range(2):
        next_valid = valid.copy()
        for dst, src in (((slice(1, None), slice(None)), (slice(None, -1), slice(None))),
                         ((slice(None, -1), slice(None)), (slice(1, None), slice(None))),
                         ((slice(None), slice(1, None)), (slice(None), slice(None, -1))),
                         ((slice(None), slice(None, -1)), (slice(None), slice(1, None)))):
            fill = ~next_valid[dst] & valid[src]
            codes[dst][fill] = codes[src][fill]
            next_valid[dst] |= fill
        valid = next_valid


def bake(job):
    level, sample = job
    scale = MIN_SCALE ** (level / LEVELS)
    extent = (5.6 if level == 0 else 5.1) * scale
    center = FOCUS + (START - FOCUS) * scale
    size = (3072 if level == 0 else 2560) // sample
    axis = ((np.arange(size) + .5) / size - .5) * extent
    packed = np.empty((size, size, 3), dtype=np.uint8)
    still = np.empty_like(packed) if level == 0 and sample == 1 else None
    for y in range(0, size, 24):
        rows = axis[y:y + 24]
        distance = distances(center + axis[None, :], rows[:, None], 520 + 42 * level)
        # Pack 12 bits of logarithmic distance and a separate exterior mask.
        number = np.rint((np.log2(np.maximum(distance, 2.**-48)) + 48) * (4095 / 56))
        number = np.clip(number, 0, 4095).astype(np.uint16)
        packed[y:y + len(rows), :, 0] = number >> 4
        packed[y:y + len(rows), :, 1] = (number & 15) * 17
        packed[y:y + len(rows), :, 2] = (distance > 0) * 255
        if still is not None:
            still[y:y + len(rows)] = color(distance)
    extend_edge_codes(packed)
    suffix = "_small" if sample == 2 else ""
    Image.fromarray(packed).save(OUT / f"mandelbrot_level_{level}{suffix}.webp", lossless=True, method=4)
    if still is not None:
        Image.fromarray(still).save(OUT / "mandelbrot_still.webp", lossless=True, method=6)
    print(f"Rendered level {level}{suffix}", flush=True)


if __name__ == "__main__":
    with ProcessPoolExecutor(max_workers=4) as pool:
        list(pool.map(bake, [(level, sample) for level in range(LEVELS) for sample in (1, 2)]))
