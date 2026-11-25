"""Expand the wallpaper catalog from device specifications and camera profiles."""
import argparse
import json
import re
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZIP_DEFLATED, ZipFile

import render_vectors as vector
from aligned_svg import layout
from desktop_density import count_in_crop, lattice_geometry, smaller_lattice


STYLES = ("original", "rgb_border")
PHONE_FAMILIES = ("Phones", "Foldables")
# 8% of the canvas on each side, before the original frame's own margin.
BORDER_SCALE = 0.84
BRANDS_WITHOUT_ANDROID_INSET = {"apple", "iphone", "huawei"}


def slug(value):
    return re.sub(r"[^a-z0-9]+", "_", value.lower().replace("+", " plus")).strip("_")


def specs(file):
    for line in file.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        manufacturer, year, model, value = line.split("|")
        if not 2020 <= int(year) <= 2026:
            raise ValueError(f"Out-of-range release year for {model}: {year}")
        if ":" not in value:
            yield slug(model), model, manufacturer, "Phones", tuple(map(int, value.split("x")))
            continue
        for display in value.split(","):
            screen, size = display.split(":")
            width, height = map(int, size.split("x"))
            for orientation, dims in (("portrait", (width, height)), ("landscape", (height, width))):
                display_id = f"{slug(model)}_{screen}_{orientation}"
                yield display_id, f"{model} {screen.title()} {orientation.title()}", manufacturer, "Foldables", dims


def inset_border(svg, width, height):
    if 'id="android-wallpaper-inset"' in svg:
        return svg
    x = vector.num(width * (1 - BORDER_SCALE) / 2)
    y = vector.num(height * (1 - BORDER_SCALE) / 2)
    # Keep the canvas black and the camera ring at its native screen coordinates.
    header, body = svg.split("</defs>", 1)
    background_end = body.index("/>") + 2
    body_end = body.find('<circle cx=', background_end)
    if body_end < 0:
        body_end = body.index("</svg>")
    return (header + "</defs>" + body[:background_end]
            + f'<g id="android-wallpaper-inset" transform="translate({x} {y}) scale({BORDER_SCALE})">'
            + body[background_end:body_end] + "</g>" + body[body_end:])


def add_camera_ring(svg, profile, style, width, height):
    cx, cy, radius = profile["cx"], profile["cy"], profile["radius"]
    viewport = profile.get("wallpaper_viewports", {}).get(style)
    if viewport:
        zoom, shift_x, shift_y = (viewport[k] for k in ("zoom", "shift_x", "shift_y"))
        cx = width/2 + (cx - width/2 - shift_x) / zoom
        cy = height/2 + (cy - height/2 - shift_y) / zoom
        radius /= zoom
    definitions, ring = vector.camera_svg({
        "camera_center": (cx, cy),
        "ring_outer_radius": radius,
    })
    return svg.replace("</defs>", definitions + "</defs>", 1).replace("</svg>", ring + "</svg>", 1)


def add_home_panel(svg, profile):
    x, y, width, height, radius, stroke = (profile[k] for k in ("x", "y", "width", "height", "radius", "stroke"))
    definitions = (
        '<linearGradient id="home-outline" x1="0%" y1="0%" x2="100%" y2="100%">'
        '<stop offset="0" stop-color="#00efff"/>'
        '<stop offset=".32" stop-color="#8844ff"/>'
        '<stop offset=".68" stop-color="#ff00ec"/>'
        '<stop offset="1" stop-color="#ff3030"/></linearGradient>'
    )
    panel = (
        f'<rect id="pixel-launcher-panel" x="{x}" y="{y}" width="{width}" height="{height}" '
        f'rx="{radius}" fill="#000" stroke="url(#home-outline)" stroke-width="{stroke}"/>'
    )
    return svg.replace("</defs>", definitions + "</defs>", 1).replace("</svg>", panel + "</svg>", 1)


def add_home_outline(svg, profile, style, canvas_width, canvas_height):
    if style == "rgb_border":
        # Fit the framed field above the launcher panel without stretching it.
        start = svg.index('<g id="android-wallpaper-inset"')
        end = svg.index("</g></g>", start) + len("</g></g>")
        top = canvas_height * (1 - BORDER_SCALE) / 2
        scale = (profile["y"] - 30 - top) / (canvas_height * BORDER_SCALE)
        shift_x = vector.num(canvas_width * (1 - scale) / 2)
        shift_y = vector.num(top * (1 - scale))
        svg = (svg[:start] + f'<g transform="translate({shift_x} {shift_y}) scale({vector.num(scale)})">'
               + svg[start:end] + "</g>" + svg[end:])
    return add_home_panel(svg, profile)


def inset_applies(folder, family):
    return family in PHONE_FAMILIES and folder.split("/")[1] not in BRANDS_WITHOUT_ANDROID_INSET


def frame_radius(width, height, family):
    if family == "Desktop":
        return 0
    if family == "Phones" and (width, height) == (1344, 2992):
        # Measured relative to the active display in Google's front-view render.
        return 185
    return round(min(width, height) * (.11 if family == "Phones" else .075))


def previous_camera(svg):
    match = re.search(r'<circle cx="([\d.]+)" cy="([\d.]+)" r="([\d.]+)" fill="#000"/>', svg)
    if not match:
        raise ValueError("A camera wallpaper is missing its cutout geometry")
    x, y, radius = map(float, match.groups())
    return {"camera_center": (x, y), "ring_outer_radius": radius - 1.5}


def build(source_root, site_dist, output_root):
    catalog_path = site_dist / "catalog.json"
    catalog = json.loads(catalog_path.read_text())
    theme = catalog["themes"][0]
    if theme.get("hiddenPresets"):
        raise ValueError("Start from the v1.1.0 catalog and artwork")
    art = site_dist / "art" / theme["id"]
    rows = theme["files"]
    aliases = {alias["id"]: alias for alias in theme.get("deviceAliases", [])}
    originals = {}
    equivalent = {}
    for path, _, device, family, width, height, style, camera, *_ in rows:
        if camera:
            continue
        art_folder = "/".join(path.split("/")[1:-2])
        originals[device] = (art_folder, family, width, height)
        if family in PHONE_FAMILIES:
            key = (family, width, height, inset_applies(art_folder, family))
            equivalent.setdefault(key, device)

    new = {}
    hidden = set(theme.get("hiddenPresets", []))
    support = output_root / "config"
    spec_rows = list(specs(support / "flagship_phones.txt"))
    registered = {}
    model_count = 0
    with (support / "flagship_phones.txt").open() as spec_file:
        model_count = sum(bool(line.strip()) and not line.startswith("#") for line in spec_file)

    for device, display, manufacturer, family, (width, height) in spec_rows:
        if device in registered:
            raise ValueError(f"Duplicate model or screen ID: {device}")
        registered[device] = (display, manufacturer, family, width, height)
        if device in originals:
            if originals[device][2:] != (width, height):
                raise ValueError(f"Existing preset {device} has different dimensions")
            continue

        brand = slug(manufacturer)
        folder = ("phone" if family == "Phones" else "foldable") + "/" + brand
        inset = inset_applies(folder, family)
        key = (family, width, height, inset)
        if device in aliases:
            old = aliases[device]
            if originals[old["preset"]][2:] != (width, height):
                raise ValueError(f"Existing alias {device} has different dimensions")
            continue
        if key not in equivalent:
            # Multiple brands can share exact-size, device-neutral artwork.
            preset = f"{'phone' if family == 'Phones' else 'foldable'}_size_{width}x{height}_{'inset' if inset else 'standard'}"
            asset_folder = ("phone" if family == "Phones" else "foldable") + ("/android" if inset else "/apple")
            equivalent[key] = preset
            originals[preset] = new[preset] = (asset_folder, family, width, height)
            hidden.add(preset)
        aliases[device] = {"id": device, "name": display, "preset": equivalent[key],
                           "manufacturer": manufacturer, "family": family}

    modified = {}

    def save_svg(path, svg, width, height):
        root = ElementTree.fromstring(svg)
        if (root.get("width"), root.get("height")) != (str(width), str(height)):
            raise ValueError(f"Invalid SVG dimensions for {path}")
        for folder in (art, output_root / "renders/svg"):
            target = folder / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(svg)
        modified[path] = {"path": path, "width": width, "height": height}

    existing = tuple(rows)
    for row in existing:
        path, design, _, family, width, height, style, *_ = row
        if style == "rgb_border":
            continue
        original = (art / path).read_text()
        svg = original
        svg = vector.align_vertical_edges(svg)
        if svg != original:
            save_svg(path, svg, width, height)

    for design in json.loads((source_root / "config/cells.json").read_text()):
        design_id = design["id"]
        palette = vector.fit_palette(vector.resolve_source(source_root, design["source"]), design_id == "04_cubes")
        frame = vector.frame_colors(vector.resolve_source(source_root, design["master"]))
        polygons = vector.grid_polygons(design)
        geometry = lattice_geometry(polygons)
        dense_candidates = [(scale, x, y, smaller_lattice(geometry, scale, x, y))
                            for scale in (.65,.67,.69,2**-.5,.73,.75,.77,.79,.81,.83,.85,.87,.89)
                            for x in (0,.25,.5) for y in (0,.25,.5)]

        for path, existing_design, _, family, width, height, style, camera, *_ in existing:
            if existing_design != design_id or style != "rgb_border":
                continue
            folder = "/".join(path.split("/")[1:-2])
            meta = {"width": width, "height": height, "style": style,
                    "frame_radius": frame_radius(width, height, family)}
            fitted = layout(design, width, height, style, polygons, fitted=True)
            shapes, density = polygons, 1
            if family == "Desktop":
                original_crop = layout(design, width, height, style, polygons)["source_box"]
                goal = 2 * count_in_crop(polygons, original_crop)
                density, _, _, shapes = min(dense_candidates, key=lambda candidate: (
                    abs(count_in_crop(candidate[3], fitted["source_box"]) - goal),
                    abs(candidate[0]-2**-.5), candidate[1]+candidate[2]))
                fitted = layout(design, width, height, style, shapes, density, fitted=True)
            old_stroke = vector.STROKES[design_id]
            vector.STROKES[design_id] *= density
            try:
                camera_shape = previous_camera((art / path).read_text()) if camera else None
                svg = vector.build_svg(meta, fitted, design, shapes, palette, frame, camera_shape)
            finally:
                vector.STROKES[design_id] = old_stroke
            if inset_applies(folder, family):
                svg = inset_border(svg, width, height)
            save_svg(path, svg, width, height)

        for preset, (folder, family, width, height) in new.items():
            for style in STYLES:
                fitted = style == "rgb_border"
                meta = {"width": width, "height": height, "style": style,
                        "frame_radius": frame_radius(width, height, family) if fitted else 0}
                svg = vector.build_svg(meta, layout(design, width, height, style, polygons, fitted=fitted),
                                       design, polygons, palette, frame)
                if style == "rgb_border" and inset_applies(folder, family):
                    svg = inset_border(svg, width, height)
                path = f"{design_id}/{folder}/{style}/{preset}_{width}x{height}_{style}.svg"
                save_svg(path, svg, width, height)
                rows.append([path, design_id, preset, family, width, height, style, ""])

    profiles = json.loads((support / "camera_profiles_additions.json").read_text())
    home_profiles = json.loads((support / "launcher_profiles.json").read_text())
    base = {(row[1], row[2], row[6]): row[0] for row in rows if not row[7] and not (len(row) > 9 and row[9])}
    for device in sorted(profiles.keys() | home_profiles.keys()):
        name, manufacturer, family, width, height = registered[device]
        alias = aliases.get(device)
        preset = alias["preset"] if alias else device
        folder = ("phone" if family == "Phones" else "foldable") + "/" + slug(manufacturer)
        profile = profiles.get(device)
        home = home_profiles.get(device)
        if profile and alias:
            alias["cameraPreset"] = device
        for design_id in theme["designs"]:
            for style in STYLES:
                svg = (art / base[design_id, preset, style]).read_text()
                stem = f"{design_id}/{folder}/{style}/{device}_{width}x{height}_{style}"
                if profile:
                    path = stem + "_camera.svg"
                    camera_svg = add_camera_ring(svg, profile, style, width, height)
                    save_svg(path, camera_svg, width, height)
                    rows.append([path, design_id, device, family, width, height, style, profile["accuracy"]])
                if home:
                    path = stem + "_home_screen.svg"
                    home_position = home[style]
                    save_svg(path, add_home_outline(svg, home_position, style, width, height), width, height)
                    rows.append([path, design_id, device, family, width, height, style, "", False, True])
                    if profile:
                        path = stem + "_home_screen_camera.svg"
                        save_svg(path, add_home_outline(camera_svg, home_position, style, width, height), width, height)
                        rows.append([path, design_id, device, family, width, height, style, profile["accuracy"], False, True])

    paths = [row[0] for row in rows]
    if len(paths) != len(set(paths)):
        raise ValueError("Duplicate wallpaper file path")
    theme["deviceAliases"] = list(aliases.values())
    theme["hiddenPresets"] = sorted(hidden)
    theme["pngRoot"] = "https://raw.githubusercontent.com/xklob/wallpapers/v1.2.1/renders/png/"
    theme["pngArchive"] = "https://github.com/xklob/wallpapers/releases/download/v1.2.1/rgb-geometry-png.zip"
    catalog_path.write_text(json.dumps(catalog, separators=(",", ":")) + "\n")
    write_output_files(output_root, site_dist, theme, modified)
    print(f"{model_count} named models, {len(registered)} device screens, {len(new)} new resolutions")
    print(f"{len(profiles)} new camera profiles, {len(modified)} changed SVGs, {len(rows)} wallpapers per format")


def write_output_files(output_root, site_dist, theme, modified):
    catalog = output_root / "site/catalog.json"
    catalog.parent.mkdir(parents=True, exist_ok=True)
    catalog.write_bytes((site_dist / "catalog.json").read_bytes())
    (output_root / "config/refresh_png.json").write_text(json.dumps(list(modified.values())) + "\n")
    art = site_dist / "art" / theme["id"]
    with ZipFile(site_dist / theme["archive"], "w", ZIP_DEFLATED, compresslevel=7) as archive:
        for path, *_ in theme["files"]:
            archive.write(art / path, path)
        archive.writestr("device_aliases.json", json.dumps(theme["deviceAliases"], indent=2) + "\n")
        archive.writestr("catalog.json", json.dumps(theme["files"], separators=(",", ":")) + "\n")
        archive.write(output_root / "docs/flagship-support.md", "README.md")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--site-dist", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    arguments = parser.parse_args()
    build(arguments.source_root, arguments.site_dist, arguments.output_root)
