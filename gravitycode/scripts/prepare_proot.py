#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import tempfile
import urllib.request
from pathlib import Path

MIRRORS = (
    "https://packages.termux.dev/apt/termux-main",
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://mirror.iscas.ac.cn/termux/apt/termux-main",
    "https://grimler.se/termux/termux-main",
    "https://ftp.fau.de/termux/termux-main",
)

PACKAGES = (
    (
        "pool/main/p/proot/proot_5.1.107.92_aarch64.deb",
        "1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9",
    ),
    (
        "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb",
        "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
    ),
    (
        "pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb",
        "ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da",
    ),
)

FILES = {
    "bin/proot": "libgravity_proot.so",
    "libexec/proot/loader": "libgravity_proot_loader.so",
    "libexec/proot/loader32": "libgravity_proot_loader32.so",
    "lib/libandroid-shmem.so": "libandroid-shmem.so",
    "lib/libtalloc.so.2.4.3": "libtalloc.so",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(relative: str, expected: str, destination: Path) -> None:
    errors: list[str] = []
    for mirror in MIRRORS:
        url = f"{mirror}/{relative}"
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "GravityCode-Android-Build/0.2"})
            with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as output:
                shutil.copyfileobj(response, output)
            actual = sha256(destination)
            if actual != expected:
                raise RuntimeError(f"SHA256 mismatch: {actual}")
            print(f"Downloaded {relative} from {mirror}")
            return
        except Exception as exc:
            errors.append(f"{mirror}: {exc}")
            destination.unlink(missing_ok=True)
    raise RuntimeError(f"Unable to download {relative}: {'; '.join(errors)}")


def patch_needed(path: Path, old_name: str, new_name: str) -> None:
    old = old_name.encode() + b"\0"
    new = new_name.encode() + b"\0"
    payload = path.read_bytes()
    if old not in payload:
        return
    if len(new) > len(old):
        raise RuntimeError(f"Cannot patch {old_name} -> {new_name}")
    path.write_bytes(payload.replace(old, new + b"\0" * (len(old) - len(new))))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    output = Path(args.output).resolve() / "arm64-v8a"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="gravity-proot-") as tmp_name:
        tmp = Path(tmp_name)
        extracted = tmp / "prefix"
        for index, (relative, expected) in enumerate(PACKAGES):
            package = tmp / f"package-{index}.deb"
            download(relative, expected, package)
            subprocess.run(["dpkg-deb", "-x", str(package), str(extracted)], check=True)

        prefix = extracted / "data/data/com.termux/files/usr"
        for relative, destination_name in FILES.items():
            source = prefix / relative
            if not source.is_file():
                raise FileNotFoundError(f"Required PRoot runtime file missing: {source}")
            destination = output / destination_name
            shutil.copy2(source, destination)
            destination.chmod(0o755)

        patch_needed(output / "libgravity_proot.so", "libtalloc.so.2", "libtalloc.so")

        optional_cpp = prefix / "lib/libc++_shared.so"
        if optional_cpp.is_file():
            shutil.copy2(optional_cpp, output / "libc++_shared.so")

    print("Prepared GravityCode Android PRoot launcher:")
    for item in sorted(output.iterdir()):
        print(f"  {item.name}: {item.stat().st_size} bytes")


if __name__ == "__main__":
    main()
