#!/usr/bin/env python3
import re
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 4:
    raise SystemExit("usage: package_agy_glibc.py <glibc-lib-dir> <jni-dir> <map-file>")

src = Path(sys.argv[1])
dst = Path(sys.argv[2])
map_file = Path(sys.argv[3])

def elf(path):
    try:
        with path.open("rb") as f:
            return f.read(4) == b"\x7fELF"
    except Exception:
        return False

if not src.is_dir():
    raise SystemExit("glibc lib directory not found: " + str(src))

dst.mkdir(parents=True, exist_ok=True)
map_file.parent.mkdir(parents=True, exist_ok=True)
mapping = {}
target_to_safe = {}

for entry in sorted(src.iterdir(), key=lambda p: p.name):
    try:
        target = entry.resolve()
    except Exception:
        continue
    if not target.is_file() or not elf(target):
        continue

    key = str(target)
    if key in target_to_safe:
        safe = target_to_safe[key]
    elif entry.name == "ld-linux-aarch64.so.1":
        safe = "libagyld.so"
        target_to_safe[key] = safe
        shutil.copy2(target, dst / safe)
    else:
        safe = "libagyrt_" + re.sub(r"[^A-Za-z0-9]+", "_", target.name).strip("_") + ".so"
        target_to_safe[key] = safe
        if not (dst / safe).exists():
            shutil.copy2(target, dst / safe)

    mapping[entry.name] = safe
    mapping[target.name] = safe

with map_file.open("w", encoding="utf-8") as f:
    f.write("# original-name\tandroid-safe-native-library\n")
    for original, safe in sorted(mapping.items()):
        f.write(original + "\t" + safe + "\n")

if not (dst / "libagyld.so").is_file():
    raise SystemExit("glibc loader was not packaged")
if not (dst / "libagycore.so").is_file():
    raise SystemExit("libagycore.so not found")

print("AGY runtime files:", len(target_to_safe))
print("AGY symlink aliases:", len(mapping))
