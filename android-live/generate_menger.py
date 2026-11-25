"""Bake the isometric Menger module. Runtime draws nested copies through its open window.

Bake with: uv run --with pillow --with pygeodesic==0.1.11 python android-live/generate_menger.py
The checked-in texture is sufficient to build the APK.
"""
from pathlib import Path

import numpy as np
from PIL import Image
from pygeodesic import geodesic


def unit(vector):
    return vector / np.linalg.norm(vector)


SIZE = 2304
DETAIL_SIZE = 3072
DETAIL_WIDTH = .25
ZOOM_RATIO = 5
CLEAR_RADIUS = .04
GRID = 27
SURFACE_GRID = GRID * 2
COLOR_WAVELENGTH = .95
VIEW = unit(np.array([0.69, 0.62, 1.0]))
RIGHT = unit(np.cross([0, 1, 0], VIEW))
UP = np.cross(VIEW, RIGHT)
SPAN = 3.35
# Projected focus is the middle of the visible opening at the back of its short jamb.
FOCUS = np.array([-0.075, -0.068, 0.77])
PORTAL_Z = FOCUS[2]
FX, FY = .5 + FOCUS @ RIGHT / SPAN, .5 - FOCUS @ UP / SPAN


def sponge():
    xyz = np.indices((GRID, GRID, GRID))
    solid = np.ones((GRID, GRID, GRID), dtype=bool)
    for size in (9, 3, 1):
        solid &= ((xyz // size % 3) == 1).sum(axis=0) < 2
    return solid


def surface_distances(solid):
    """Measure distance from the back of the central window along the voxel surface."""
    padded = np.pad(solid, 1)
    axes = np.eye(3, dtype=int)
    step = SURFACE_GRID // GRID
    quads = []
    for a in range(3):
        u, v = axes[(a + 1) % 3], axes[(a + 2) % 3]
        offsets = np.array([i * u + j * v for i in range(step) for j in range(step)])
        for sign in (-1, 1):
            neighbor = np.roll(padded, -sign, axis=a)[1:-1, 1:-1, 1:-1]
            faces = np.argwhere(solid & ~neighbor) * step
            faces[:, a] += step if sign > 0 else 0
            cells = (faces[:, None, :] + offsets).reshape(-1, 3)
            corners = [0 * u, u, u + v, v]
            if sign < 0:
                corners.reverse()
            quads.append(cells[:, None, :] + corners)

    vertices, indices = np.unique(np.concatenate(quads).reshape(-1, 3), axis=0, return_inverse=True)
    triangles = indices.reshape(-1, 4)[:, [0, 1, 2, 0, 2, 3]].reshape(-1, 3).astype(np.int32)
    z = round((1 + PORTAL_Z) * SURFACE_GRID / 2)
    radius = np.abs(vertices[:, :2] - SURFACE_GRID / 2).max(axis=1)
    source = np.flatnonzero((vertices[:, 2] == z) & (radius == SURFACE_GRID / 6)).astype(np.int32)
    if not len(source):
        raise ValueError("No surface at the central window")
    world = vertices * (2 / SURFACE_GRID) - 1
    mesh = geodesic.PyGeodesicAlgorithmExact(world, triangles)
    distances, _ = mesh.geodesicDistances(source, None)
    if not np.isfinite(distances).all():
        raise ValueError("The wave cannot reach the whole cube surface")
    field = np.full((SURFACE_GRID + 1,) * 3, np.nan)
    field[tuple(vertices.T)] = distances
    return field


def sample_surface(field, points, axis, cells):
    uvw = np.clip((points + 1) * SURFACE_GRID / 2, 0, SURFACE_GRID)
    # A ray on the edge of a face still belongs to the hit voxel. Sampling the
    # neighboring voxel would cross into an unrelated face or a hole.
    step = SURFACE_GRID // GRID
    base = np.clip(uvw.astype(int), cells * step, (cells + 1) * step - 1)
    i = np.arange(len(points))
    base[i, axis] = np.rint(uvw[i, axis]).astype(int)
    u, v = (axis + 1) % 3, (axis + 2) % 3
    s, t = uvw[i, u] - base[i, u], uvw[i, v] - base[i, v]
    axes = np.eye(3, dtype=int)
    du, dv = axes[u], axes[v]
    return ((1 - t) * ((1 - s) * field[tuple(base.T)] + s * field[tuple((base + du).T)])
            + t * ((1 - s) * field[tuple((base + dv).T)] + s * field[tuple((base + du + dv).T)]))


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
    return origins - t[:, None] * VIEW, axis, hits, cells


def rainbow(distance):
    # Hue advances uniformly in Android. A linear distance term makes the wave
    # travel outward at a fixed speed in model space, even across right angles.
    hue = -distance / COLOR_WAVELENGTH
    return np.clip(np.abs((hue[:, None] + [0, 2/3, 1/3]) % 1 * 6 - 3) - 1, 0, 1)


def portal_lining(origins, portal, opening, rgba):
    # In projection, the next cube is exactly this scaled copy about FOCUS.
    # Bring the jambs in to its silhouette and keep the inside transparent.
    rays = (origins[opening] - FOCUS) * ZOOM_RATIO + FOCUS
    # Tuck the liner slightly under the next silhouette so antialiased edges meet.
    near = ((rays - .98) / VIEW).max(axis=1)
    far = ((rays + .98) / VIEW).min(axis=1)
    gap = near > far
    outside = np.flatnonzero(opening)[gap]
    mouth = portal[outside]
    x, y = mouth[:, :2].T
    # Extend the zero-distance jamb inward along the recessed connector plane.
    distance = np.maximum(np.abs(x), np.abs(y)) - 1 / 3
    separation = (near - far)[gap]
    wheel = rainbow(distance)
    # Shade the inner bevel without losing the tunnel's color next to the cube.
    throat = 1 - np.exp(-1.4 * separation)
    wall = .58 + .42 * np.clip(.5 + y - x, 0, 1)
    light = wall * (.43 + .44 * throat)
    rgba[outside, :3] = (14 + light[:, None] * (24 + 145 * wheel)).astype(np.uint8)
    rgba[outside, 3] = 255


def render(rows, resolution, solid, field, left, top, span):
    y, x = np.meshgrid(rows, np.arange(resolution), indexing="ij")
    u = (left + (x.ravel() + .5) * (span / resolution) - .5) * SPAN
    v = (.5 - top - (y.ravel() + .5) * (span / resolution)) * SPAN
    origins = u[:, None] * RIGHT + v[:, None] * UP + 4 * VIEW
    pos, axis, hits, cells = trace(origins, solid)
    t_portal = (origins[:, 2] - PORTAL_Z) / VIEW[2]
    portal = origins - t_portal[:, None] * VIEW
    opening = (np.abs(portal[:, :2]).max(axis=1) < 1 / 3) & (~hits | (pos[:, 2] < PORTAL_Z + 1e-7))
    visible = hits & ~opening
    rgba = np.zeros((len(x.ravel()), 4), dtype=np.uint8)
    p, a = pos[visible], axis[visible]
    normal_position = p[np.arange(len(p)), a]
    exterior = normal_position > .9999
    # Sample on the face the ray actually hit. Projection and depth only affect
    # where the moving band appears on screen.
    distance = sample_surface(field, p, a, cells[visible])
    if not np.isfinite(distance).all():
        raise ValueError("Ray hit a voxel without a surface distance")
    wheel = rainbow(distance)
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
    face = (9 + 9 * light)[:, None] + (.42 + .58 * light[:, None]) * (16 + 115 * wheel)
    lining = (9 + 8 * light)[:, None] + illumination[:, None] * (18 + 191 * wheel)
    rgb = np.where(exterior[:, None], face, lining)

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
    rgb += (8 + 55 * wheel) * np.exp(-38 * edge[:, None])
    rgba[visible, :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    rgba[visible, 3] = 255
    portal_lining(origins, portal, opening, rgba)
    return rgba.reshape(len(rows), resolution, 4)


def bake(size, solid, field, left=0, top=0, span=1):
    n = 2 * size
    image = Image.new("RGBA", (n, n))
    for y in range(0, n, 32):
        block = render(np.arange(y, min(y + 32, n)), n, solid, field, left, top, span)
        image.paste(Image.fromarray(block), (0, y))
    image = image.resize((size, size), Image.Resampling.LANCZOS)
    clear = tuple(round(size * value / span) for value in
                  (FX - left - CLEAR_RADIUS, FY - top - CLEAR_RADIUS,
                   FX - left + CLEAR_RADIUS, FY - top + CLEAR_RADIUS))
    if image.getchannel("A").crop(clear).getbbox():
        raise ValueError("The opening is too small for the runtime zoom cutoff")
    return image


if __name__ == "__main__":
    solid = sponge()
    field = surface_distances(solid)
    dest = Path(__file__).parent / "res/drawable-nodpi"
    dest.mkdir(exist_ok=True)
    bake(SIZE, solid, field).save(dest / "menger_windows.webp", lossless=True, method=6)
    bake(DETAIL_SIZE, solid, field, FX - DETAIL_WIDTH / 2, FY - DETAIL_WIDTH / 2, DETAIL_WIDTH).save(
        dest / "menger_windows_detail.webp", lossless=True, method=6)
    print("Texture focus (normalized):", FX, FY)
