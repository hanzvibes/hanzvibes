#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path

MIRRORS = (
    "https://packages.termux.dev/apt/termux-main",
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://mirror.iscas.ac.cn/termux/apt/termux-main",
    "https://grimler.se/termux/termux-main",
    "https://termux.librehat.com/apt/termux-main",
    "https://ftp.fau.de/termux/termux-main",
)
INDEX_PATH = "dists/stable/main/binary-aarch64/Packages"
IGNORED = {
    "termux-am", "termux-am-socket", "termux-auth", "termux-core", "termux-exec",
    "termux-keyring", "termux-licenses", "termux-tools",
}

@dataclass(frozen=True)
class Package:
    name: str
    filename: str
    sha256: str
    depends: tuple[str, ...]


def hash_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def fetch(relative: str, expected_sha: str | None = None) -> bytes:
    errors: list[str] = []
    for mirror in MIRRORS:
        url = f"{mirror}/{relative}"
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "GravityCode-Android-Build/0.2"})
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = response.read()
            if not payload:
                raise RuntimeError("empty response")
            if expected_sha and hash_bytes(payload) != expected_sha:
                raise RuntimeError(f"SHA256 mismatch: {hash_bytes(payload)}")
            print(f"Fetched {relative} from {mirror}")
            return payload
        except Exception as exc:
            errors.append(f"{mirror}: {exc}")
    raise RuntimeError(f"Unable to fetch {relative}: {'; '.join(errors)}")


def parse_depends(raw: str) -> tuple[str, ...]:
    result: list[str] = []
    for chunk in raw.split(",") if raw else []:
        options = []
        for option in chunk.split("|"):
            name = re.sub(r"\s*\(.*?\)", "", option).strip()
            if name:
                options.append(name)
        selected = next((item for item in options if item not in IGNORED), options[0] if options else "")
        if selected and selected not in IGNORED:
            result.append(selected)
    return tuple(dict.fromkeys(result))


def parse_index(text: str) -> dict[str, Package]:
    records: dict[str, Package] = {}
    for stanza in text.split("\n\n"):
        fields: dict[str, str] = {}
        for line in stanza.splitlines():
            if not line or line[0].isspace() or ":" not in line:
                continue
            key, value = line.split(":", 1)
            fields[key] = value.strip()
        if all(fields.get(key) for key in ("Package", "Filename", "SHA256")):
            records[fields["Package"]] = Package(
                name=fields["Package"],
                filename=fields["Filename"],
                sha256=fields["SHA256"],
                depends=parse_depends(fields.get("Depends", "")),
            )
    return records


def resolve(records: dict[str, Package], root: str = "proot") -> list[Package]:
    pending = [root]
    seen: set[str] = set()
    ordered: list[Package] = []
    while pending:
        name = pending.pop(0)
        if name in seen or name in IGNORED:
            continue
        package = records.get(name)
        if package is None:
            raise KeyError(f"Termux package {name!r} missing from index")
        seen.add(name)
        ordered.append(package)
        pending.extend(dep for dep in package.depends if dep not in seen and dep not in IGNORED)
    return ordered


def patch_needed(path: Path, old_name: str, new_name: str) -> None:
    old = old_name.encode() + b"\0"
    new = new_name.encode() + b"\0"
    payload = path.read_bytes()
    if old not in payload:
        return
    if len(new) > len(old):
        raise RuntimeError(f"Cannot patch {old_name} -> {new_name}")
    path.write_bytes(payload.replace(old, new + b"\0" * (len(old) - len(new))))


def copy_required(prefix: Path, output: Path) -> None:
    mapping = {
        "bin/proot": "libgravity_proot.so",
        "libexec/proot/loader": "libgravity_proot_loader.so",
        "libexec/proot/loader32": "libgravity_proot_loader32.so",
        "lib/libandroid-shmem.so": "libandroid-shmem.so",
    }
    for source_rel, destination_name in mapping.items():
        source = prefix / source_rel
        if not source.is_file():
            raise FileNotFoundError(f"Required PRoot runtime file missing: {source}")
        destination = output / destination_name
        shutil.copy2(source, destination)
        destination.chmod(0o755)

    talloc = next(iter(sorted((prefix / "lib").glob("libtalloc.so.*"))), None)
    if not talloc:
        raise FileNotFoundError("libtalloc runtime library missing")
    shutil.copy2(talloc, output / "libtalloc.so")
    (output / "libtalloc.so").chmod(0o755)

    cpp = prefix / "lib/libc++_shared.so"
    if cpp.is_file():
        shutil.copy2(cpp, output / "libc++_shared.so")
        (output / "libc++_shared.so").chmod(0o755)

    patch_needed(output / "libgravity_proot.so", "libtalloc.so.2", "libtalloc.so")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    output = Path(args.output).resolve() / "arm64-v8a"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)

    print("Resolving current Termux PRoot dependency closure…")
    records = parse_index(fetch(INDEX_PATH).decode("utf-8", "replace"))
    packages = resolve(records)
    print("Packages:", ", ".join(package.name for package in packages))

    with tempfile.TemporaryDirectory(prefix="gravity-proot-") as tmp_name:
        tmp = Path(tmp_name)
        extracted = tmp / "root"
        for index, package in enumerate(packages):
            payload = fetch(package.filename, package.sha256)
            deb = tmp / f"{index}-{package.name}.deb"
            deb.write_bytes(payload)
            subprocess.run(["dpkg-deb", "-x", str(deb), str(extracted)], check=True)

        prefix = extracted / "data/data/com.termux/files/usr"
        copy_required(prefix, output)

    print("Prepared GravityCode Android PRoot launcher:")
    for item in sorted(output.iterdir()):
        print(f"  {item.name}: {item.stat().st_size} bytes")


if __name__ == "__main__":
    main()
