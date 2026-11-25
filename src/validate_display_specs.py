"""Compare every visible wallpaper profile with its model, panel or generic resolution.

The JSON sources record the bounding rectangle of each named display in pixels.
Rounded-corner geometry is checked separately by the artwork validators.
"""
import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config"


def slug(model):
    return re.sub(r"[^a-z0-9]+", "_", model.lower().replace("+", " plus")).strip("_")


def rotated_profiles(stem, portrait):
    return {stem + "_portrait": portrait, stem + "_landscape": portrait[::-1]}


def indexed_screens():
    """Find the model-index entry and visible profiles for each named panel."""
    screens = {}
    for line in (CONFIG / "flagship_phones.txt").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        brand, _, model, specs = line.split("|")
        for entry in specs.split(","):
            screen, _, pixels = entry.rpartition(":")
            size = tuple(map(int, pixels.split("x")))
            if screen:
                profiles = rotated_profiles(f"{slug(model)}_{screen}", size)
            else:
                screen = "main"
                profiles = {slug(model): size}
            screens[model, screen] = (brand, size, profiles)
    return screens


def catalog_profiles():
    sizes = defaultdict(set)
    visible = set()
    for theme in json.loads((ROOT / "site/catalog.json").read_text())["themes"]:
        base = set()
        for row in theme["files"]:
            _, _, preset, _, width, height, _, ring, _, dock, status = (row + [False] * 3)[:11]
            sizes[preset].add((width, height))
            if not (ring or dock or status):
                base.add(preset)
        visible.update(base.difference(theme.get("hiddenPresets", [])))
        for alias in theme.get("deviceAliases", []):
            visible.add(alias["id"])
            for key in ("preset", "cameraPreset"):
                if key in alias:
                    sizes[alias["id"]].update(sizes[alias[key]])
    return sizes, visible


def manufacturer_records():
    sources = {}
    for path in sorted(CONFIG.glob("*_display_specs.json")):
        if path.name == "google_display_specs.json":
            continue
        data = json.loads(path.read_text())
        duplicate = data.keys() & sources.keys()
        if duplicate:
            raise ValueError(f"Duplicate manufacturer records: {sorted(duplicate)}")
        sources.update(data)
    return sources


def validate(show_unverified):
    screens = indexed_screens()
    sources = manufacturer_records()
    google = json.loads((CONFIG / "google_display_specs.json").read_text())["devices"]
    sizes, visible = catalog_profiles()
    checked = set()
    errors = []
    missing = []
    general = {}
    with (CONFIG / "device_sizes.csv").open(newline="") as stream:
        for row in csv.DictReader(stream):
            group, preset = row["group"], row["preset"]
            size = (int(row["width"]), int(row["height"]))
            if group.startswith("tablet/") and preset.endswith("_portrait"):
                stem = preset.removesuffix("_portrait")
                brand = "Google" if group == "tablet/pixel" else "Apple"
                screens[stem, "main"] = (brand, size, rotated_profiles(stem, size))
            elif group == "desktop" or preset == "galaxy_fhd_20x9":
                general[preset] = size

    for model, record in sources.items():
        for screen in record["screens"]:
            if (model, screen) not in screens:
                errors.append(f"Unexpected manufacturer record {model} {screen}")

    def check(preset, size):
        checked.add(preset)
        if preset not in visible or sizes.get(preset) != {size}:
            errors.append(f"{preset}: catalog {sizes.get(preset)}, expected {size}")

    named_google = 0
    for (model, screen), (brand, expected, presets) in screens.items():
        if brand == "Google":
            named_google += 1
            key = slug(model) + ("" if screen == "main" else "_" + screen)
            record = google.get(key, {})
            # The first Pixel Fold inner panel and the tablet use landscape
            # native coordinates. The model index always names portrait first.
            reported = tuple(sorted((record["width"], record["height"]))) if record else None
            link = record.get("size_source")
        else:
            record = sources.get(model, {})
            reported = record.get("screens", {}).get(screen)
            link = record.get("source")
        if reported is None:
            missing.append(f"{model} ({screen})")
        else:
            reported = tuple(reported)
            if not (link or "").startswith("https://"):
                errors.append(f"{model} {screen} lacks a manufacturer source")
            if reported != expected:
                errors.append(f"{model} {screen}: manufacturer {reported}, model index {expected}")
            for member in record.get("models", []):
                if tuple(member["screens"][screen]) != reported:
                    errors.append(f"{model}: grouped model {member['model']} differs")
        for preset, size in presets.items():
            check(preset, size)

    for preset, models in {"iphone_15_16": ["iPhone 15", "iPhone 16"],
                           "galaxy_s24_plus_ultra": ["Galaxy S24+", "Galaxy S24 Ultra"]}.items():
        for model in models:
            check(preset, screens[model, "main"][1])
    for screen in ("cover", "inner"):
        size = screens["Motorola Razr 60 Ultra", screen][1]
        for preset, orientation in rotated_profiles(f"motorola_razr_ultra_2025_{screen}", size).items():
            check(preset, orientation)
    for preset, size in general.items():
        check(preset, size)
    if visible - checked:
        errors.append(f"Catalog profiles without an audit: {sorted(visible - checked)}")

    if show_unverified:
        for line in sorted(missing):
            print("Awaiting manufacturer source:", line)
    for error in errors:
        print(error)
    print(f"{len(screens) - len(missing)}/{len(screens)} named model/panel groups have manufacturer dimensions "
          f"({named_google} Google panel groups). Checked {len(checked)} selectable profiles, including "
          f"{len(general)} generic resolutions. {len(missing)} missing sources, {len(errors)} errors.")
    return bool(errors or missing)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unverified", action="store_true", help="list panels without manufacturer dimensions")
    raise SystemExit(validate(parser.parse_args().unverified))
