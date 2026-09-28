#!/usr/bin/env python3
import re
import shutil
import subprocess
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: package_agy_glibc.py <glibc-lib-dir> <jni-dir>")

src = Path(sys.argv[1])
dst = Path(sys.argv[2])
mapping = {}
outputs = []

def elf(path: Path) -> bool:
    try:
        with path.open("rb") as f:
            return f.read(4) == b"\x7fELF"
    except Exception:
        return False

if not src.is_dir():
    raise SystemExit(f"glibc lib directory not found: {src}")
dst.mkdir(parents=True, exist_ok=True)

for entry in src.iterdir():
    try:
        target = entry.resolve()
    except Exception:
        continue
    if not target.is_file() or not elf(target):
        continue
    safe = "libagyld.so" if entry.name == "ld-linux-aarch64.so.1" else (
        "libagyrt_" + re.sub(r"[^A-Za-z0-9]+", "_", entry.name).strip("_") + ".so"
    )
    mapping[entry.name] = safe
    out = dst / safe
    if not out.exists():
        shutil.copy2(target, out)
    outputs.append(out)

core = dst / "libagycore.so"
if not core.is_file():
    raise SystemExit("libagycore.so not found")
outputs.append(core)

for f in outputs:
    if f.name == "libagyld.so":
        continue
    subprocess.run(
        ["patchelf", "--set-soname", f.name, str(f)],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

for f in outputs:
    try:
        needed = subprocess.check_output(["patchelf", "--print-needed", str(f)], text=True).splitlines()
    except Exception:
        continue
    for old in needed:
        if old in mapping:
            subprocess.run(["patchelf", "--replace-needed", old, mapping[old], str(f)], check=True)

loader = dst / "libagyld.so"
if not loader.is_file():
    raise SystemExit("glibc loader was not packaged")

needed_core = subprocess.check_output(["patchelf", "--print-needed", str(core)], text=True).splitlines()
names = {p.name for p in dst.iterdir()}
missing = [name for name in needed_core if name not in names]
if missing:
    raise SystemExit("unresolved AGY core dependencies: " + ", ".join(missing))

print(f"AGY runtime aliases: {len(mapping)}")
print("AGY core needs:", ", ".join(needed_core))
