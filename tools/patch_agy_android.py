#!/usr/bin/env python3
import struct
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: patch_agy_android.py <input> <output>")

src = Path(sys.argv[1])
dst = Path(sys.argv[2])
data = bytearray(src.read_bytes())

def get32(off):
    return struct.unpack_from("<I", data, off)[0]

def put32(off, word):
    struct.pack_into("<I", data, off, word)

def section_range(name):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return 0, len(data)
    try:
        shoff = struct.unpack_from("<Q", data, 40)[0]
        entsize = struct.unpack_from("<H", data, 58)[0]
        count = struct.unpack_from("<H", data, 60)[0]
        stridx = struct.unpack_from("<H", data, 62)[0]
        strhdr = shoff + stridx * entsize
        stroff = struct.unpack_from("<Q", data, strhdr + 24)[0]
        for i in range(count):
            base = shoff + i * entsize
            nameoff = struct.unpack_from("<I", data, base)[0]
            start = stroff + nameoff
            end = data.index(0, start)
            secname = data[start:end].decode("utf-8", "replace")
            if secname == name:
                off = struct.unpack_from("<Q", data, base + 24)[0]
                size = struct.unpack_from("<Q", data, base + 32)[0]
                return off, off + size
    except Exception:
        pass
    return 0, len(data)

lo, hi = section_range("google_malloc")
counts = {"ubfx": 0, "lsl": 0, "mask": 0, "mmap": 0, "tags": 0, "faccessat2": 0}

# TCMalloc VA48 -> VA39 patches used by Android compatibility builds.
for off in range(lo, hi - 4, 4):
    word = get32(off)
    if (word & 0x7F800000) == 0x53000000:
        immr = (word >> 16) & 0x3F
        imms = (word >> 10) & 0x3F
        if immr == 42 and imms == 44:
            put32(off, (word & ~((0x3F << 16) | (0x3F << 10))) | (35 << 16) | (37 << 10))
            counts["ubfx"] += 1
        elif immr == 22 and imms == 21:
            put32(off, (word & ~((0x3F << 16) | (0x3F << 10))) | (29 << 16) | (28 << 10))
            counts["lsl"] += 1

for off in range(lo, hi - 8, 4):
    if get32(off) == 0x92D3800A and get32(off + 4) == 0xF2E0000A:
        put32(off, 0x9280000A)
        put32(off + 4, 0xD35DFD4A)
        counts["mask"] += 1

for off in range(lo, hi - 4, 4):
    if get32(off) == 0xF2E00029:
        put32(off, 0xD3596129)
        counts["mmap"] += 1

rewrites = {
    0xD2C20009: 0xD2C00409, 0xD2C2000A: 0xD2C0040A,
    0xF2C20008: 0xF2DFF408, 0xF2C20009: 0xF2DFF409,
    0xD2C10009: 0xD2C00209, 0xD2C1000A: 0xD2C0020A,
    0xF2C38008: 0xF2DFF708, 0xF2C38009: 0xF2DFF709,
    0x92560A6C: 0x925D0A6C, 0x92560A6A: 0x925D0A6A,
    0xD2C3000D: 0xD2C0060D, 0xD2C3000C: 0xD2C0060C,
    0xD2C08008: 0xD2C00108,
}
for off in range(lo, hi - 4, 4):
    word = get32(off)
    if word in rewrites:
        put32(off, rewrites[word])
        counts["tags"] += 1

# Go syscall wrapper: faccessat2(439) -> faccessat(48), avoiding Android seccomp SIGSYS.
for off in range(0, len(data) - 16, 4):
    if (
        get32(off) == 0xAA1F03E5
        and get32(off + 4) == 0xAA1F03E6
        and get32(off + 8) == 0xD28036E0
        and (get32(off + 12) & 0xFC000000) == 0x94000000
    ):
        put32(off + 8, 0xD2800600)
        counts["faccessat2"] += 1

dst.write_bytes(data)
dst.chmod(0o755)
print(" ".join(f"{k}={v}" for k, v in counts.items()))
if counts["faccessat2"] == 0:
    raise SystemExit("required faccessat2 patch pattern not found")
