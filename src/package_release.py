import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

root = Path(__file__).resolve().parents[1]
release = root / "release"
release.mkdir(exist_ok=True)

for extension in ("png", "svg"):
    render_root = root / "renders" / extension
    if extension == "png":
        manifests = ["files.json", "camera_files.json"]
        images = [
            render_root / entry["file"]
            for name in manifests
            for entry in json.loads((render_root / name).read_text())
        ]
    else:
        images = sorted(render_root.rglob("*.svg"))
    if len(set(images)) != 720 or not all(image.is_file() for image in images):
        raise ValueError(f"Expected 720 {extension} wallpapers, found {len(images)}")
    with ZipFile(release / f"rgb-geometry-{extension}.zip", "w",
                 compression=ZIP_STORED if extension == "png" else ZIP_DEFLATED,
                 compresslevel=6) as archive:
        archive.write(root / "docs/device-guide.md", "README.md")
        archive.write(root / "config/device_sizes.csv", "device_sizes.csv")
        archive.write(root / "config/Camera_Ring_Adjuster.html", "Camera_Ring_Adjuster.html")
        for image in images:
            archive.write(image, image.relative_to(render_root))
