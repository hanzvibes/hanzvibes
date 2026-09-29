#!/usr/bin/env python3
from pathlib import Path
from PIL import Image
import sys, zipfile

if len(sys.argv) != 3:
    raise SystemExit("usage: apply_hd2x_graphics.py <android-project-root> <overlay-zip>")

root = Path(sys.argv[1]).resolve()
overlay_zip = Path(sys.argv[2]).resolve()
assets = root / "app" / "src" / "main" / "assets"

if not assets.is_dir():
    raise SystemExit(f"assets directory missing: {assets}")
if not overlay_zip.is_file():
    raise SystemExit(f"overlay zip missing: {overlay_zip}")

# Fonts are loaded through the legacy BitmapCache pixel scanner, not the texture
# coordinate pipeline. Keep them 1x so text metrics and glyph separators are unchanged.
font_names = {"font1x.png", "font15x.png", "font2x.png", "font25x.png", "font3x.png"}

pngs = sorted(assets.glob("*.png"))
original_sizes = {}
for path in pngs:
    with Image.open(path) as im:
        original_sizes[path.name] = im.size

for path in pngs:
    if path.name in font_names:
        continue
    with Image.open(path) as im:
        out = im.convert("RGBA").resize((im.width * 2, im.height * 2), Image.Resampling.NEAREST)
        out.save(path, format="PNG", optimize=True)

with zipfile.ZipFile(overlay_zip) as zf:
    members = [m for m in zf.namelist() if not m.endswith("/")]
    for name in members:
        if Path(name).name != name:
            raise SystemExit(f"nested/unsafe overlay member: {name}")
        if name not in original_sizes:
            raise SystemExit(f"overlay atlas not present in game assets: {name}")
        if name in font_names:
            raise SystemExit(f"font atlas must remain 1x: {name}")
        data = zf.read(name)
        target = assets / name
        target.write_bytes(data)

selected = []
for path in pngs:
    with Image.open(path) as im:
        original = original_sizes[path.name]
        expected = original if path.name in font_names else (original[0] * 2, original[1] * 2)
        if im.size != expected:
            raise SystemExit(f"HD geometry mismatch: {path.name}: got {im.size}, expected {expected}")
        if path.name not in font_names:
            selected.append(path.name)

# Validate overlay atlases are decodable and exactly two times their legacy geometry.
with zipfile.ZipFile(overlay_zip) as zf:
    overlay_names = [m for m in zf.namelist() if not m.endswith("/")]
for name in overlay_names:
    with Image.open(assets / name) as im:
        ow, oh = original_sizes[name]
        if im.size != (ow * 2, oh * 2):
            raise SystemExit(f"overlay ratio mismatch: {name}")

print(f"HD2x graphics ready: {len(selected)} texture atlases at 2x, {len(font_names)} font atlases preserved at 1x.")
print("HD remaster overlays:", ", ".join(sorted(overlay_names)))
