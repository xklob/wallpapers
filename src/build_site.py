import json
import shutil
import tarfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
output = root / "build/site"
catalog = json.loads((root / "site/catalog.json").read_text())
# Rebuilding in place must also retire art removed from the picker and packs.
art = output / "art"
published = {art / theme["id"] / row[0] for theme in catalog["themes"] for row in theme["files"]}
for previous in art.rglob("*.svg"):
    if previous not in published:
        previous.unlink()
shutil.copytree(root / "site", output, dirs_exist_ok=True)
for theme in catalog["themes"]:
    for file, *_ in theme["files"]:
        dest = output / "art" / theme["id"] / file
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / "renders/svg" / file, dest)
    shutil.copyfile(root / "release" / theme["archive"], output / theme["archive"])

# The unversioned source download follows the gallery's current designs.
with tarfile.open(output / "rgb-geometry-source.tar.gz", "w:gz") as bundle:
    for name in ("README.md", "requirements.txt", "assets", "config", "docs", "src", "site/catalog.json"):
        source = root / name
        files = sorted(source.rglob("*")) if source.is_dir() else [source]
        for file in files:
            if file.is_file() and "__pycache__" not in file.parts and file.suffix != ".pyc":
                bundle.add(file, arcname=file.relative_to(root), recursive=False)
