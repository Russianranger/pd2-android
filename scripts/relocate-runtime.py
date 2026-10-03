#!/usr/bin/env python3
"""Relocate Winlator's bundled runtime without changing binary layout.

Wine, glibc, X11 and Box64 embed the original Android package's private path.
The two package names have exactly the same byte length, so replacing those
bytes also repairs ELF PT_INTERP, RUNPATH and ordinary compiled path literals
without moving sections, code, string addresses or symbol tables.

Only launcher/runtime assets are processed. User-imported game files are not
part of this build-time transformation. Requires zstandard==0.25.0.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import tarfile

import zstandard

OLD_PACKAGE = b"com.winlator"
NEW_PACKAGE = b"com.pd2.thor"
ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("docs/RUNTIME-RELOCATION.json")


def rewrite_member_bytes(data: bytes) -> tuple[bytes, int]:
    if len(OLD_PACKAGE) != len(NEW_PACKAGE):
        raise ValueError("Runtime relocation requires equal-length package names")
    count = data.count(OLD_PACKAGE)
    rewritten = data.replace(OLD_PACKAGE, NEW_PACKAGE)
    if len(rewritten) != len(data):
        raise ValueError("Runtime relocation changed binary byte length")
    return rewritten, count


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _text(value: str) -> tuple[str, int]:
    old, new = OLD_PACKAGE.decode("ascii"), NEW_PACKAGE.decode("ascii")
    return value.replace(old, new), value.count(old)


def _metadata(member: tarfile.TarInfo) -> tuple[tarfile.TarInfo, int]:
    result = copy.copy(member)
    count = 0
    result.name, found = _text(member.name)
    count += found
    result.linkname, found = _text(member.linkname)
    count += found
    result.pax_headers = {}
    for key, value in member.pax_headers.items():
        new_key, found = _text(key)
        count += found
        new_value, found = _text(value)
        count += found
        result.pax_headers[new_key] = new_value
    return result, count


def inspect_archive(path: Path) -> dict:
    occurrences = 0
    count = 0
    with path.open("rb") as source:
        with zstandard.ZstdDecompressor().stream_reader(source) as decoded:
            with tarfile.open(fileobj=decoded, mode="r|") as archive:
                for member in archive:
                    count += 1
                    _, found = _metadata(member)
                    occurrences += found
                    if member.isfile():
                        with archive.extractfile(member) as contents:
                            # A small overlap finds package tokens crossing chunks.
                            tail = b""
                            while block := contents.read(1024 * 1024):
                                joined = tail + block
                                cut = max(0, len(joined) - len(OLD_PACKAGE) + 1)
                                offset = 0
                                while True:
                                    position = joined.find(OLD_PACKAGE, offset)
                                    if position < 0 or position >= cut:
                                        break
                                    occurrences += 1
                                    offset = position + len(OLD_PACKAGE)
                                tail = joined[cut:]
                            occurrences += tail.count(OLD_PACKAGE)
    return {"old_package_occurrences": occurrences, "members": count}


def relocate_archive(path: Path) -> dict:
    original = digest(path)
    initial = inspect_archive(path)
    if not initial["old_package_occurrences"]:
        return {"original_sha256": original, "sha256": original, "replacements": 0,
                "members": initial["members"], "changed_members": []}
    mode = stat.S_IMODE(path.stat().st_mode)
    replacement_count = 0
    changed_members = []
    member_count = 0
    fd, name = tempfile.mkstemp(prefix=".pd2-relocate-", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "wb") as destination, path.open("rb") as source:
            with zstandard.ZstdDecompressor().stream_reader(source) as decoded:
                with zstandard.ZstdCompressor(
                        level=19, threads=0, write_checksum=True,
                        write_content_size=False).stream_writer(destination, closefd=False) as encoded:
                    with tarfile.open(fileobj=decoded, mode="r|") as old_tar:
                        with tarfile.open(fileobj=encoded, mode="w|", format=tarfile.GNU_FORMAT) as new_tar:
                            for member in old_tar:
                                member_count += 1
                                rewritten, count = _metadata(member)
                                if member.isfile():
                                    with old_tar.extractfile(member) as contents:
                                        data, found = rewrite_member_bytes(contents.read())
                                    count += found
                                    if len(data) != member.size:
                                        raise ValueError("Runtime member changed size: " + member.name)
                                    new_tar.addfile(rewritten, io.BytesIO(data))
                                else:
                                    new_tar.addfile(rewritten)
                                replacement_count += count
                                if count:
                                    changed_members.append({"name": member.name, "replacements": count,
                                                            "bytes": member.size})
            destination.flush()
            os.fsync(destination.fileno())
        if replacement_count:
            os.chmod(temporary, mode)
            os.replace(temporary, path)
        result = {"original_sha256": original, "sha256": digest(path),
                  "replacements": replacement_count, "members": member_count,
                  "changed_members": changed_members}
        if inspect_archive(path)["old_package_occurrences"]:
            raise ValueError("Unrelocated package path remains in " + str(path))
        return result
    finally:
        temporary.unlink(missing_ok=True)


def relocate_file(path: Path) -> dict:
    original = digest(path)
    data, count = rewrite_member_bytes(path.read_bytes())
    if count:
        mode = stat.S_IMODE(path.stat().st_mode)
        fd, name = tempfile.mkstemp(prefix=".pd2-relocate-", dir=path.parent)
        temporary = Path(name)
        try:
            with os.fdopen(fd, "wb") as target:
                target.write(data)
                target.flush()
                os.fsync(target.fileno())
            os.chmod(temporary, mode)
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
    return {"original_sha256": original, "sha256": digest(path),
            "replacements": count, "bytes": len(data)}


def runtime_assets(root: Path) -> list[Path]:
    return sorted([*(root / "app/src/main/assets").rglob("*.tzst"),
                   *(root / "app/src/main/jniLibs").rglob("*.so")])


def check(root: Path, manifest: dict) -> None:
    if (manifest.get("format"), manifest.get("package")) != (1, NEW_PACKAGE.decode()):
        raise ValueError("Unsupported runtime relocation manifest")
    current = {p.relative_to(root).as_posix(): p for p in runtime_assets(root)}
    if set(current) != set(manifest["files"]):
        raise ValueError("Runtime asset inventory changed; run relocation again")
    for name, path in current.items():
        if digest(path) != manifest["files"][name]["sha256"]:
            raise ValueError("Runtime asset hash changed: " + name)
        remaining = (inspect_archive(path)["old_package_occurrences"]
                     if path.suffix == ".tzst" else path.read_bytes().count(OLD_PACKAGE))
        if remaining:
            raise ValueError("Original Winlator package path remains: " + name)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify inventory and relocated paths without edits")
    parser.add_argument("--root", type=Path, default=ROOT, help="Android project root")
    args = parser.parse_args()
    root = args.root.resolve()
    manifest_path = root / MANIFEST
    previous = json.loads(manifest_path.read_text()) if manifest_path.is_file() else {}
    if args.check:
        check(root, previous)
        print("Verified all relocated runtime asset hashes and package paths")
        return
    if len(OLD_PACKAGE) != len(NEW_PACKAGE):
        raise ValueError("Runtime relocation requires equal-length package names")
    manifest = {"format": 1, "original_package": OLD_PACKAGE.decode(), "package": NEW_PACKAGE.decode(),
                "method": "Equal-length runtime path substitution; regular file byte lengths and ELF offsets unchanged",
                "zstandard_version": zstandard.__version__, "zstd_version": list(zstandard.ZSTD_VERSION),
                "files": {}}
    for path in runtime_assets(root):
        name = path.relative_to(root).as_posix()
        prior = previous.get("files", {}).get(name)
        result = relocate_archive(path) if path.suffix == ".tzst" else relocate_file(path)
        if prior and not result["replacements"] and result["sha256"] == prior.get("sha256"):
            result = prior
        manifest["files"][name] = result
        if result["replacements"]:
            print(name + ": " + str(result["replacements"]) + " recorded package-path substitutions", flush=True)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    check(root, manifest)
    print("Relocated and verified " + str(len(manifest["files"])) + " runtime assets")


if __name__ == "__main__":
    main()
