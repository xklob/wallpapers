"""Render the gallery's 30 fps Mandelbrot loop from the same tiles as the app."""
from pathlib import Path
from subprocess import PIPE, Popen

import numpy as np
from PIL import Image

from generate_mandelbrot import FOCUS, LEVELS, MIN_SCALE, OUT, SCENE_SPAN, START, color

WIDTH, HEIGHT, FPS, SECONDS = 480, 1068, 30, 32
VIEW_SPAN = max(SCENE_SPAN, min(5.1, 1.92 * HEIGHT / WIDTH))
WRAP_START = .90
ASSETS = Path(__file__).resolve().parents[1] / "site/assets"
TILES = [Image.open(OUT / f"mandelbrot_level_{level}.webp") for level in range(LEVELS)]


def center(scale):
    return FOCUS + (START - FOCUS) * scale


def ease(x):
    return x * x * (3 - 2 * x)


def sample(level, scale):
    tile = TILES[level]
    tile_scale = MIN_SCALE ** (level / LEVELS)
    extent = (5.6 if level == 0 else 5.1) * tile_scale
    world_span = VIEW_SPAN * scale
    pixel = world_span / HEIGHT / extent * tile.width
    u = (.5 + (center(scale) - center(tile_scale)) / extent) * tile.width
    coords = (0, pixel, u - HEIGHT * pixel / 2, -pixel, 0, tile.height / 2 + WIDTH * pixel / 2)
    data = np.asarray(tile.transform((WIDTH, HEIGHT), Image.Transform.AFFINE, coords, Image.Resampling.BILINEAR))
    code = data[:, :, 0].astype(np.float32) * 16 + data[:, :, 1] / 17
    palette = color(np.exp2(-48 + np.arange(4096) * (56 / 4095)), SCENE_SPAN * scale)
    result = palette[np.rint(code).astype(np.int32)] * (data[:, :, 2].astype(np.float32) / 255)[..., None]
    return result


def frame(phase):
    position = phase * LEVELS
    level = min(int(position), LEVELS - 1)
    scale = MIN_SCALE ** phase
    current = sample(level, scale)
    if phase > WRAP_START:
        blend = ease((phase - WRAP_START) / (1 - WRAP_START))
        room = (2.79 + START - FOCUS) / (VIEW_SPAN / 2 + START - FOCUS) - 1
        incoming = 1 - room * np.expm1(np.log(MIN_SCALE) * (1 - phase) / room)
        current = (1 - blend) * current + blend * sample(0, incoming)
    elif level and position - level < .20:
        blend = ease((position - level) / .20)
        current = blend * current + (1 - blend) * sample(level - 1, scale)
    return np.rint(current).astype(np.uint8)


if __name__ == "__main__":
    Image.fromarray(frame(0)).save(ASSETS / "mandelbrot-classic.jpg", quality=94)
    with Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pixel_format", "rgb24",
                "-video_size", f"{WIDTH}x{HEIGHT}", "-framerate", str(FPS), "-i", "-",
                "-c:v", "libx264", "-crf", "16", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", str(ASSETS / "mandelbrot-classic-1x.mp4")], stdin=PIPE) as encoder:
        for f in range(SECONDS * FPS):
            encoder.stdin.write(frame(f / (SECONDS * FPS)).tobytes())
        encoder.stdin.close()
        if encoder.wait():
            raise SystemExit("Failed to encode gallery preview.")
