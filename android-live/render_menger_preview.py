"""Render the gallery loop from the Android Menger module and detail textures."""
from pathlib import Path
from subprocess import PIPE, Popen

import numpy as np
from PIL import Image

WIDTH, HEIGHT, FPS, SECONDS = 540, 1156, 30, 16
FOCUS_X, FOCUS_Y = .34971622, .59916291
DETAIL_WIDTH = .25
ROOT = Path(__file__).resolve().parents[1]
TEXTURES = ROOT / "android-live/res/drawable-nodpi"
ASSETS = ROOT / "site/assets"


def image_at(base, detail, phase):
    output = Image.new("RGBA", (WIDTH, HEIGHT), (16, 16, 21, 255))
    span = HEIGHT * 5 ** phase
    while span > 1:
        span /= 5
    while span < HEIGHT * 13:
        left, top = WIDTH * .49 - span * FOCUS_X, HEIGHT * .50 - span * FOCUS_Y
        x, y = max(0, int(np.floor(left))), max(0, int(np.floor(top)))
        w = min(WIDTH, int(np.ceil(left + span))) - x
        h = min(HEIGHT, int(np.ceil(top + span))) - y
        if w > 0 and h > 0:
            u, v = (x - left) / span, (y - top) / span
            low = sample(base, w, h, u, v, span)
            if span > .8 * base.width:
                u0, v0 = FOCUS_X - DETAIL_WIDTH / 2, FOCUS_Y - DETAIL_WIDTH / 2
                high = sample(detail, w, h, (u - u0) / DETAIL_WIDTH, (v - v0) / DETAIL_WIDTH,
                              span * DETAIL_WIDTH)
                xx = u + (np.arange(w)[None, :] + .5) / span - FOCUS_X
                yy = v + (np.arange(h)[:, None] + .5) / span - FOCUS_Y
                edge = DETAIL_WIDTH / 2 - np.maximum(np.abs(xx), np.abs(yy))
                spatial = smoothstep(np.clip(edge * (base.width / 18), 0, 1))
                temporal = smoothstep(min(1, (span / base.width - .8) / .5))
                mask = Image.fromarray(np.rint(255 * spatial * temporal).astype(np.uint8))
                low = Image.composite(high, low, mask)
            output.alpha_composite(low, (x, y))
        span *= 5
    return output.convert("RGB")


def smoothstep(t):
    return t * t * (3 - 2 * t)


def sample(image, width, height, u, v, mapped_span):
    pixels = image.width / mapped_span
    return image.transform((width, height), Image.Transform.AFFINE,
                           (pixels, 0, u * image.width, 0, pixels, v * image.height),
                           Image.Resampling.BILINEAR)


if __name__ == "__main__":
    base = Image.open(TEXTURES / "menger_windows.webp").convert("RGBA")
    detail = Image.open(TEXTURES / "menger_windows_detail.webp").convert("RGBA")
    image_at(base, detail, .28).save(ASSETS / "menger-windows.jpg", quality=94)
    with Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pixel_format", "rgb24",
                "-video_size", f"{WIDTH}x{HEIGHT}", "-framerate", str(FPS), "-i", "-",
                "-c:v", "libx264", "-crf", "16", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", str(ASSETS / "menger-windows-1x.mp4")], stdin=PIPE) as encoder:
        for frame in range(FPS * SECONDS):
            encoder.stdin.write(image_at(base, detail, frame / (FPS * SECONDS)).tobytes())
        encoder.stdin.close()
        if encoder.wait():
            raise SystemExit("Failed to encode gallery preview.")
