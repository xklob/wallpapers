"""Bake the isometric Menger module. Runtime draws nested copies through its open window.

Developer art tool: python3 (with numpy and Pillow) android-live/generate_menger.py
The checked-in texture is sufficient to build the APK.
"""
from pathlib import Path

import numpy as np
from PIL import Image


def unit(vector):
    return vector / np.linalg.norm(vector)


SIZE = 1536
GRID = 27
VIEW = unit(np.array([0.69, 0.62, 1.0]))
RIGHT = unit(np.cross([0, 1, 0], VIEW))
UP = np.cross(VIEW, RIGHT)
SPAN = 3.35
# Projected focus is the middle of the visible opening at the back of its short jamb.
FOCUS = np.array([-0.075, -0.068, 0.77])
PORTAL_Z = FOCUS[2]


def sponge():
    xyz = np.indices((GRID, GRID, GRID))
    solid = np.ones((GRID, GRID, GRID), dtype=bool)
    for size in (9, 3, 1):
        solid &= ((xyz // size % 3) == 1).sum(axis=0) < 2
    return solid


def trace(origins, solid):
    # The camera faces the positive octant. Walk exact voxel boundaries rather than
    # approximating distance to a thin wall. Keep the hit axis for flat face normals.
    enter = (origins - 1) / VIEW
    t = enter.max(axis=1)
    axis = enter.argmax(axis=1)
    cells = np.floor((origins - (t[:, None] + 1e-6) * VIEW + 1) * (GRID / 2)).astype(int)
    hits = np.zeros(len(t), dtype=bool)
    active = np.flatnonzero((cells.min(axis=1) >= 0) & (cells.max(axis=1) < GRID))
    while len(active):
        c = cells[active]
        found = solid[c[:, 0], c[:, 1], c[:, 2]]
        hits[active[found]] = True
        active = active[~found]
        c = cells[active]
        boundary = -1 + c * (2 / GRID)
        crossing = (origins[active] - boundary) / VIEW
        a = crossing.argmin(axis=1)
        axis[active] = a
        t[active] = crossing[np.arange(len(a)), a]
        cells[active, a] -= 1
        active = active[cells[active, a] >= 0]
    return origins - t[:, None] * VIEW, axis, hits


def render(rows, resolution, solid):
    y, x = np.meshgrid(rows, np.arange(resolution), indexing="ij")
    u = ((x.ravel() + .5) / resolution - .5) * SPAN
    v = (.5 - (y.ravel() + .5) / resolution) * SPAN
    origins = u[:, None] * RIGHT + v[:, None] * UP + 4 * VIEW
    pos, axis, hits = trace(origins, solid)
    t_portal = (origins[:, 2] - PORTAL_Z) / VIEW[2]
    portal = origins - t_portal[:, None] * VIEW
    opening = (np.abs(portal[:, :2]).max(axis=1) < 1 / 3) & (pos[:, 2] < PORTAL_Z + 1e-7)
    visible = hits & ~opening
    rgba = np.zeros((len(x.ravel()), 4), dtype=np.uint8)
    p, a = pos[visible], axis[visible]
    normal_position = p[np.arange(len(p)), a]
    exterior = normal_position > .9999
    # Each set of windows has a different light color. The app rotates these hues
    # with the same shader as its RGB vector patterns; graphite faces remain legible.
    hue = .14 + .32 * p[:, 0] + .21 * p[:, 1] - .16 * p[:, 2]
    wheel = np.clip(np.abs((hue[:, None] + [0, 2/3, 1/3]) % 1 * 6 - 3) - 1, 0, 1)
    light = np.array([.54, 1.0, .76])[a]
    depth = np.clip(1 - p.max(axis=1), 0, 1)
    # Scale the light falloff to the size of each individual tunnel. Its recessed
    # back stays dark while light catches the floor and the lip of the opening.
    boundary = np.rint((normal_position + 1) * GRID / 2).astype(int)
    cell = np.where(boundary % 9 == 0, 9, np.where(boundary % 3 == 0, 3, 1))
    local = ((p + 1) * GRID / (6 * cell[:, None])) % 1
    i = np.arange(len(p))
    s, t = local[i, (a + 1) % 3], local[i, (a + 2) % 3]
    pool = np.exp(-8 * (s - .59)**2 - 5 * (t - .87)**2)
    mouth = np.exp(-7 * (1 - np.maximum(s, t)))
    illumination = (.12 + .58 * pool + .70 * mouth) * (.42 + .58 * light) * np.exp(-.7 * depth)
    graphite = (18 + 46 * light + 5 * p[:, 1] - 2 * p[:, 0])[:, None]
    lining = (9 + 8 * light)[:, None] + illumination[:, None] * (18 + 191 * wheel)
    rgb = np.where(exterior[:, None], graphite * [.94, .97, 1.04] + wheel * 4, lining)

    # Highlight physical edges only. Adjacent coplanar voxels share a single face.
    coords = np.clip((p + 1) * GRID / 2 - np.eye(3)[a] * 1e-5, 0, GRID - 1e-6)
    indices = coords.astype(int)
    fraction = coords - indices
    edge = np.ones(len(p))
    for axis_in_face in ((a + 1) % 3, (a + 2) % 3):
        for step in (-1, 1):
            neighbor = indices.copy()
            neighbor[i, axis_in_face] += step
            outside = (neighbor.min(axis=1) < 0) | (neighbor.max(axis=1) >= GRID)
            neighbor = np.clip(neighbor, 0, GRID - 1)
            exposed = outside | ~solid[tuple(neighbor.T)]
            distance = fraction[i, axis_in_face]
            edge = np.minimum(edge, np.where(exposed, distance if step < 0 else 1-distance, 1))
    rgb += (8 + 44 * wheel) * np.exp(-15 * edge[:, None])
    rgba[visible, :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    rgba[visible, 3] = 255
    return rgba.reshape(len(rows), resolution, 4)


if __name__ == "__main__":
    solid = sponge()
    n = 2 * SIZE
    image = Image.new("RGBA", (n, n))
    for y in range(0, n, 32):
        block = render(np.arange(y, min(y + 32, n)), n, solid)
        image.paste(Image.fromarray(block), (0, y))
    image = image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    fx, fy = .5 + FOCUS @ RIGHT / SPAN, .5 - FOCUS @ UP / SPAN
    clear = tuple(round(value * SIZE) for value in (fx-.05, fy-.05, fx+.05, fy+.05))
    if image.getchannel("A").crop(clear).getbbox():
        raise ValueError("The opening is too small for the runtime zoom cutoff")
    dest = Path(__file__).parent / "res/drawable-nodpi/menger_windows.webp"
    dest.parent.mkdir(exist_ok=True)
    image.save(dest, lossless=True, method=6)
    print("Texture focus (normalized):", fx, fy)
