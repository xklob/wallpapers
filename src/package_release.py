import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

root = Path(__file__).resolve().parents[1]
release = root / "release"
release.mkdir(exist_ok=True)
themes = json.loads((root / "site/catalog.json").read_text())["themes"]

for theme in themes:
    for extension in ("png", "svg"):
        render_root = root / "renders" / extension
        images = [Path(row[0]).with_suffix("." + extension) for row in theme["files"]]
        if len(set(images)) != len(images):
            raise ValueError(f"Duplicate {extension} entries for {theme['id']}")
        for path in images:
            if not (render_root / path).is_file():
                raise FileNotFoundError(render_root / path)

        archive_path = release / f"{theme['id']}-{extension}.zip"
        with ZipFile(archive_path, "w",
                     compression=ZIP_STORED if extension == "png" else ZIP_DEFLATED,
                     compresslevel=7) as archive:
            archive.write(root / "docs/device-guide.md", "README.md")
            archive.write(root / "config/Camera_Ring_Adjuster.html", "Camera_Ring_Adjuster.html")
            archive.writestr("device_aliases.json", json.dumps(theme["deviceAliases"], indent=2)+"\n")
            archive.writestr("catalog.json", json.dumps(theme["files"], separators=(",", ":"))+"\n")
            for path in images:
                archive.write(render_root / path, path)
        print(f"{archive_path.name}: {len(images)} files, {archive_path.stat().st_size:,} bytes")
        if archive_path.stat().st_size >= 2**31:
            raise ValueError("GitHub release assets must be under 2 GiB")
