import json
import shutil
from pathlib import Path

root = Path(__file__).resolve().parents[1]
output = root / "build/site"
shutil.copytree(root / "site", output, dirs_exist_ok=True)
for theme in json.loads((output / "catalog.json").read_text())["themes"]:
    for file, *_ in theme["files"]:
        dest = output / "art" / theme["id"] / file
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / "renders/svg" / file, dest)
    shutil.copyfile(root / "release" / theme["archive"], output / theme["archive"])
