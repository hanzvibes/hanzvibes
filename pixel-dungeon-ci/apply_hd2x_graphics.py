#!/usr/bin/env python3
from pathlib import Path
from PIL import Image, ImageFile, ImageEnhance
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply_hd2x_graphics.py <android-project-root>")

root = Path(sys.argv[1]).resolve()
assets = root / "app" / "src" / "main" / "assets"
if not assets.is_dir():
    raise SystemExit(f"assets directory missing: {assets}")

ImageFile.LOAD_TRUNCATED_IMAGES = True

FONT_NAMES = {"font1x.png", "font15x.png", "font2x.png", "font25x.png", "font3x.png"}

# Atlases where frame geometry is known from the game source. Processing them
# cell-by-cell prevents any neighboring animation frame from bleeding across UVs.
FRAME_GRIDS = {
    "warrior.png": (12, 15),
    "mage.png": (12, 15),
    "rogue.png": (12, 15),
    "ranger.png": (12, 15),
    "gnoll.png": (12, 15),
    "skeleton.png": (12, 15),
    "warlock.png": (12, 15),
    "bat.png": (15, 15),
    "goo.png": (20, 14),
    "mimic.png": (16, 16),
    "items.png": (16, 16),
    "dashboard.png": (32, 32),
    "tiles0.png": (16, 16),
    "tiles1.png": (16, 16),
    "tiles2.png": (16, 16),
    "tiles3.png": (16, 16),
    "tiles4.png": (16, 16),
}

def edge_aware_2x(frame):
    """Scale2x-style pixel-art enlargement confined to one logical frame."""
    src = frame.convert("RGBA")
    w, h = src.size
    px = src.load()
    out = Image.new("RGBA", (w * 2, h * 2), (0, 0, 0, 0))
    op = out.load()

    def at(x, y):
        x = max(0, min(w - 1, x))
        y = max(0, min(h - 1, y))
        return px[x, y]

    for y in range(h):
        for x in range(w):
            E = at(x, y)
            B = at(x, y - 1)
            D = at(x - 1, y)
            F = at(x + 1, y)
            H = at(x, y + 1)

            if B != H and D != F:
                E0 = D if D == B else E
                E1 = F if B == F else E
                E2 = D if D == H else E
                E3 = F if H == F else E
            else:
                E0 = E1 = E2 = E3 = E

            op[x * 2,     y * 2]     = E0
            op[x * 2 + 1, y * 2]     = E1
            op[x * 2,     y * 2 + 1] = E2
            op[x * 2 + 1, y * 2 + 1] = E3
    return out

def remaster_atlas(src, frame_size=None):
    rgba = src.convert("RGBA")
    # A restrained contrast/color lift keeps the dark-fantasy premium palette
    # readable on modern OLED screens without changing semantic colors.
    rgba = ImageEnhance.Contrast(rgba).enhance(1.06)
    rgba = ImageEnhance.Color(rgba).enhance(1.04)

    if not frame_size:
        return rgba.resize((rgba.width * 2, rgba.height * 2), Image.Resampling.NEAREST)

    fw, fh = frame_size
    out = rgba.resize((rgba.width * 2, rgba.height * 2), Image.Resampling.NEAREST)
    cols = rgba.width // fw
    rows = rgba.height // fh
    for row in range(rows):
        for col in range(cols):
            x0, y0 = col * fw, row * fh
            frame = rgba.crop((x0, y0, x0 + fw, y0 + fh))
            scaled = edge_aware_2x(frame)
            out.paste(scaled, (x0 * 2, y0 * 2), scaled)
    return out

pngs = sorted(assets.glob("*.png"))
original_sizes = {}
for path in pngs:
    with Image.open(path) as im:
        original_sizes[path.name] = im.size

processed = []
for path in pngs:
    if path.name in FONT_NAMES:
        continue
    print("HD2x frame-safe remaster:", path.name)
    with Image.open(path) as im:
        out = remaster_atlas(im, FRAME_GRIDS.get(path.name))
        out.save(path, format="PNG", optimize=True)
    processed.append(path.name)

# Hard geometry gate: every non-font atlas must be exactly 2x in both axes,
# while bitmap-scanned legacy fonts deliberately remain untouched at 1x.
for path in pngs:
    with Image.open(path) as im:
        ow, oh = original_sizes[path.name]
        expected = (ow, oh) if path.name in FONT_NAMES else (ow * 2, oh * 2)
        if im.size != expected:
            raise SystemExit(f"HD geometry mismatch: {path.name}: got {im.size}, expected {expected}")
        im.verify()

print(f"HD2x pass complete: {len(processed)} texture atlases remastered; {len(FONT_NAMES)} legacy font atlases preserved at 1x.")
print("Frame-isolated atlases:", ", ".join(sorted(FRAME_GRIDS)))
