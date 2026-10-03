#!/usr/bin/env python3
"""Build the Wine 9.2 baseline from the pinned upstream Winlator 10.1 APK.

Only opt/wine is replaced. Android host libraries, graphics drivers and all
other rootfs members remain from the original runtime. Package-path relocation
is a separate, subsequent build step. Requires zstandard==0.25.0.
"""
from __future__ import annotations

import copy
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import stat
import tarfile
import tempfile
import zipfile

import zstandard

APK_URL = "https://github.com/brunodev85/winlator/releases/download/v10.1.0/Winlator_10.1.apk"
APK_ASSET_ID = 275027589
APK_BYTES = 147778434
APK_SHA256 = "c46ec3fc96548cecb3716ada8733ebdea4fb25c3c945e0695f2c992c8d3ecf4e"
APK_MEMBERS = {
    "assets/rootfs.txz": {
        "bytes": 55181252,
        "sha256": "60ba2f16340846d314d1512269a7c722522e71f0d7c1679d86d0542fa9f7a38c",
    },
    "assets/container_pattern.tzst": {
        "bytes": 8412021,
        "sha256": "84affc05eded3603ca8eb4f6504790e4c1f4ba1f178e8c422fc2be4f31bb2631",
    },
    "assets/common_dlls.json": {
        "bytes": 20077,
        "sha256": "0b09a3b4bdb5e828a5355678d6cc54e3a6271c0f9d9ab25163d5575048c878d6",
    },
}
class IntegrityError(ValueError):
    """An upstream input does not match the pinned build dependency."""


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify_apk(apk: Path) -> None:
    if apk.stat().st_size != APK_BYTES or digest(apk) != APK_SHA256:
        raise IntegrityError("Winlator 10.1 APK does not match its pinned SHA-256 and size")


def _read_member(archive: zipfile.ZipFile, name: str) -> bytes:
    if name not in APK_MEMBERS:
        raise IntegrityError("APK member is not a pinned runtime dependency: " + name)
    matches = [member for member in archive.infolist() if member.filename == name]
    if len(matches) != 1:
        raise IntegrityError("APK member is missing or duplicated: " + name)
    member, expected = matches[0], APK_MEMBERS[name]
    if member.is_dir() or member.file_size != expected["bytes"]:
        raise IntegrityError("APK member has an unexpected size: " + name)
    with archive.open(member) as source:
        data = source.read(expected["bytes"] + 1)
    if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
        raise IntegrityError("APK member does not match its pinned SHA-256: " + name)
    return data


def read_apk_member(apk: Path, name: str) -> bytes:
    """Read a declared dependency after verifying both the APK and member."""
    verify_apk(apk)
    with zipfile.ZipFile(apk) as archive:
        return _read_member(archive, name)


def _path(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or "\0" in name:
        raise IntegrityError("Unsafe runtime archive member: " + name)
    return path


def _is_wine(member: tarfile.TarInfo) -> bool:
    path = _path(member.name)
    return path == PurePosixPath("opt/wine") or path.is_relative_to("opt/wine")


def _copy_member(source: tarfile.TarFile, destination: tarfile.TarFile,
                 member: tarfile.TarInfo) -> None:
    _path(member.name)
    info = copy.copy(member)
    info.pax_headers = dict(member.pax_headers)
    if member.isfile():
        with source.extractfile(member) as contents:
            destination.addfile(info, contents)
    else:
        destination.addfile(info)


def build_rootfs(base: Path, apk: Path, destination: Path) -> dict:
    """Atomically replace the complete Wine tree in a zstd-compressed rootfs.

    The output uses the same deterministic GNU tar/zstd settings as runtime
    relocation. Input Wine binaries are copied verbatim; the caller subsequently
    runs relocate-runtime.py. The destination can also be the base path.
    """
    base, apk, destination = Path(base), Path(apk), Path(destination)
    verify_apk(apk)
    with zipfile.ZipFile(apk) as archive:
        wine_rootfs = _read_member(archive, "assets/rootfs.txz")
    original_hash = digest(base)
    mode = stat.S_IMODE(destination.stat().st_mode if destination.exists() else base.stat().st_mode)
    preserved = removed = added = wine_bytes = 0
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".pd2-baseline-", dir=destination.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, "wb") as output, base.open("rb") as compressed:
            with zstandard.ZstdDecompressor().stream_reader(compressed) as decoded:
                with zstandard.ZstdCompressor(
                        level=19, threads=0, write_checksum=True,
                        write_content_size=False).stream_writer(output, closefd=False) as encoded:
                    with tarfile.open(fileobj=encoded, mode="w|", format=tarfile.GNU_FORMAT) as target:
                        with tarfile.open(fileobj=decoded, mode="r|") as original:
                            for member in original:
                                if _is_wine(member):
                                    removed += 1
                                    continue
                                _copy_member(original, target, member)
                                preserved += 1
                        if not removed:
                            raise IntegrityError("Original rootfs does not contain opt/wine")
                        with tarfile.open(fileobj=io.BytesIO(wine_rootfs), mode="r|xz") as baseline:
                            seen = set()
                            for member in baseline:
                                if not _is_wine(member):
                                    continue
                                canonical = _path(member.name).as_posix()
                                if canonical in seen:
                                    raise IntegrityError("Duplicate baseline Wine member: " + member.name)
                                seen.add(canonical)
                                _copy_member(baseline, target, member)
                                added += 1
                                if member.isfile():
                                    wine_bytes += member.size
                            if "opt/wine/bin/wine" not in seen or "opt/wine/lib/wine/x86_64-unix/ntdll.so" not in seen:
                                raise IntegrityError("Baseline archive lacks the Wine executable or ntdll")
            output.flush()
            os.fsync(output.fileno())
        output_hash = digest(temporary)
        output_bytes = temporary.stat().st_size
        os.chmod(temporary, mode)
        os.replace(temporary, destination)
        return {
            "base_sha256": original_hash,
            "apk_sha256": APK_SHA256,
            "wine_rootfs_sha256": APK_MEMBERS["assets/rootfs.txz"]["sha256"],
            "sha256": output_hash,
            "bytes": output_bytes,
            "preserved_members": preserved,
            "removed_wine_members": removed,
            "added_wine_members": added,
            "wine_bytes": wine_bytes,
        }
    finally:
        temporary.unlink(missing_ok=True)
