"""Refresh config/android_hardware_models.json from Google's supported_devices.csv.

Inputs: https://storage.googleapis.com/play_public/supported_devices.csv and the
catalog.json produced by prepare_art.py. Only unambiguous OEM model codes are kept.
"""
import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path


def normalize(text):
    return re.sub("[^a-z0-9]", "", text.lower().replace("+", "plus"))


def product(brand, name):
    return normalize(name).removeprefix(normalize(brand))


def collect(csv_path, catalog):
    products = defaultdict(set)
    codes = defaultdict(set)
    with csv_path.open(encoding="utf-16", newline="") as source:
        for brand, name, _, code in list(csv.reader(source))[1:]:
            if brand and name and code:
                key = normalize(brand), product(brand, name)
                products[normalize(brand), normalize(code)].add(key)
                codes[key].add(code)
    result = {}
    for model in catalog:
        name = re.sub(r"(?: (?:Inner|Cover))? (?:Portrait|Landscape)$", "", model["name"], flags=re.I)
        brand = model["manufacturer"]
        verified = sorted(code for code in codes[normalize(brand), product(brand, name)]
                          if normalize(code) != normalize(name)
                          and len(products[normalize(brand), normalize(code)]) == 1)
        if verified:
            result[name] = verified
    return result


if __name__ == "__main__":
    csv_path, catalog_path, output = map(Path, sys.argv[1:])
    rows = collect(csv_path, json.loads(catalog_path.read_text()))
    output.write_text("{\n" + ",\n".join(f"  {json.dumps(k)}: {json.dumps(rows[k])}" for k in sorted(rows)) + "\n}\n")
