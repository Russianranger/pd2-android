#!/usr/bin/env python3
"""Backport the exact upstream HID IRP cancel predicate to pinned Wine 9.2 PE bytes.

This is a binary backport, not a newly compiled Wine driver. All original bytes
except the qualified conditional-jump byte and required PE checksum stay exact.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import tarfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "runtime/hid-read"
OUTPUT = ROOT / "app/src/main/assets/pd2/hid-read"
REVISION = "wine9-hid-cancel-1"
ASSET = "pd2/hid-read/hidclass.sys"
BASE_SHA = "a335d3560f14d5b1e31f90fd765ee6261f43a4d70a1f456fbec805ccf18132bc"
PATCHED_SHA = "def30d1b2b6ada06c0ce04d96a8055c67b2f07f2d10f622960ad259f75a64dcb"
SOURCE_FILES = [SOURCE / "PATCH.json", SOURCE / "vendor/device.c", SOURCE / "COPYING.LIB", Path(__file__)]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def patch_metadata() -> dict:
    metadata = json.loads((SOURCE / "PATCH.json").read_text())
    required = {
        "revision": REVISION, "source_commit": "a4ef2bf8963fe4bfab390eebf73336364ce209bc",
        "source_repository": "brunodev85/wine-9.2-custom", "source_path": "dlls/hidclass.sys/device.c",
        "source_blob": "8fde1fe10a5202f66d861535c5a06c5676aa4e8b",
        "source_sha256": "ffcb22fe98cf04fbb6043b0eb17fc9019d069275ecd5fd83cb81ab5b1ac3c3a7",
        "upstream_fix": "wine-mirror/wine@4ba2f77fb6714d98285d8a07a8b6fbf85c0a9080",
        "old_expression": "irp->Cancel && !IoSetCancelRoutine( irp, NULL )",
        "new_expression": "irp->Cancel && IoSetCancelRoutine( irp, NULL )",
        "base_sha256": BASE_SHA, "sha256": PATCHED_SHA, "machine": "PE32+-AMD64",
        "bytes": 65536, "predicate_rva": 0x25f2, "predicate_file_offset": 0x25f3,
        "old_predicate_byte": 0x84, "new_predicate_byte": 0x85,
        "context_file_offset": 0x25d0,
        "old_context_hex": "4889e9e8a81c0000488d5368488d0d8debffff48870a807b4400740c48873a4885ff0f84b0000000",
        "old_checksum": 0x17c78, "new_checksum": 0x17d78,
        "license": "LGPL-2.1-or-later",
    }
    if any(metadata.get(key) != value for key, value in required.items()):
        raise RuntimeError("HID read backport identity or qualification changed")
    vendor = (SOURCE / "vendor/device.c").read_bytes()
    if sha(vendor) != metadata["source_sha256"]:
        raise RuntimeError("Pinned HIDclass source changed")
    git_blob = hashlib.sha1(b"blob " + str(len(vendor)).encode() + b"\0" + vendor).hexdigest()
    if git_blob != metadata["source_blob"]:
        raise RuntimeError("Pinned HIDclass source is not the retrieved Git blob")
    if vendor.decode().count(metadata["old_expression"]) != 1:
        raise RuntimeError("Pinned cancellation predicate must occur exactly once")
    return metadata


def pe_metadata(data: bytes) -> dict:
    if len(data) < 256 or data[:2] != b"MZ":
        raise RuntimeError("Expected a PE image")
    pe = struct.unpack_from("<I", data, 0x3c)[0]
    if pe + 24 + 112 > len(data) or data[pe:pe + 4] != b"PE\0\0":
        raise RuntimeError("Invalid PE header")
    machine, count = struct.unpack_from("<HH", data, pe + 4)
    optional_size = struct.unpack_from("<H", data, pe + 20)[0]
    optional = pe + 24
    if machine != 0x8664 or struct.unpack_from("<H", data, optional)[0] != 0x20b:
        raise RuntimeError("Expected matching PE32+ AMD64 driver")
    if optional_size != 240 or count != 11:
        raise RuntimeError("Unexpected pinned driver layout")
    checksum_offset = optional + 64
    sections = []
    section_offset = optional + optional_size
    for index in range(count):
        at = section_offset + 40 * index
        if at + 40 > len(data):
            raise RuntimeError("Truncated PE section table")
        name = data[at:at + 8].rstrip(b"\0").decode("ascii")
        virtual_size, rva, raw_size, raw_offset = struct.unpack_from("<IIII", data, at + 8)
        if raw_offset + raw_size > len(data):
            raise RuntimeError("Truncated PE section data")
        sections.append({"name": name, "rva": rva, "virtual_bytes": virtual_size,
                         "file_offset": raw_offset, "bytes": raw_size,
                         "sha256": sha(data[raw_offset:raw_offset + raw_size])})
    def rva_offset(rva: int) -> int:
        for section in sections:
            if section["rva"] <= rva < section["rva"] + section["bytes"]:
                return section["file_offset"] + rva - section["rva"]
        raise RuntimeError("PE RVA is outside file-backed sections")
    directories = []
    for index in range(16):
        rva, size = struct.unpack_from("<II", data, optional + 112 + 8 * index)
        offset = rva_offset(rva) if rva and size else 0
        if offset + size > len(data):
            raise RuntimeError("Truncated PE directory")
        directories.append({"index": index, "rva": rva, "bytes": size,
                            "sha256": sha(data[offset:offset + size])})
    return {"machine": "PE32+-AMD64", "entry_point_rva": struct.unpack_from("<I", data, optional + 16)[0],
            "image_base": struct.unpack_from("<Q", data, optional + 24)[0],
            "image_bytes": struct.unpack_from("<I", data, optional + 56)[0],
            "checksum_offset": checksum_offset,
            "checksum": struct.unpack_from("<I", data, checksum_offset)[0],
            "predicate_file_offset": rva_offset(0x25f2) + 1,
            "sections": sections, "directories": directories}


def checksum(data: bytes, offset: int) -> int:
    image = bytearray(data)
    struct.pack_into("<I", image, offset, 0)
    result = 0
    for at in range(0, len(image), 2):
        result += image[at] | ((image[at + 1] if at + 1 < len(image) else 0) << 8)
        result = (result & 0xffff) + (result >> 16)
    return ((result & 0xffff) + (result >> 16)) + len(image)


def make_artifact(original: bytes) -> bytes:
    metadata = patch_metadata()
    if len(original) != metadata["bytes"] or sha(original) != BASE_SHA:
        raise RuntimeError("Unknown HIDclass input; exact pinned original required")
    layout = pe_metadata(original)
    at = metadata["context_file_offset"]
    context = bytes.fromhex(metadata["old_context_hex"])
    if original[at:at + len(context)] != context or layout["predicate_file_offset"] != 0x25f3:
        raise RuntimeError("Qualified IRP cancel branch context does not match")
    if layout["checksum"] != checksum(original, layout["checksum_offset"]):
        raise RuntimeError("Original driver checksum is invalid")
    updated = bytearray(original)
    updated[0x25f3] = 0x85
    struct.pack_into("<I", updated, layout["checksum_offset"], checksum(updated, layout["checksum_offset"]))
    updated = bytes(updated)
    validate_artifact(updated)
    return updated


def validate_artifact(data: bytes) -> dict:
    metadata = patch_metadata()
    if len(data) != metadata["bytes"] or sha(data) != PATCHED_SHA:
        raise RuntimeError("HID read artifact has unknown bytes")
    layout = pe_metadata(data)
    if layout["checksum"] != checksum(data, layout["checksum_offset"]):
        raise RuntimeError("Backport driver checksum is invalid")
    original = bytearray(data)
    original[0x25f3] = 0x84
    struct.pack_into("<I", original, layout["checksum_offset"], metadata["old_checksum"])
    if sha(original) != BASE_SHA:
        raise RuntimeError("Backport altered bytes outside qualified predicate/checksum")
    original_layout = pe_metadata(original)
    if layout["directories"] != original_layout["directories"]:
        raise RuntimeError("PE export/import/exception/relocation/resource directories changed")
    for current, previous in zip(layout["sections"], original_layout["sections"]):
        if current["name"] != ".text" and current != previous:
            raise RuntimeError("A PE section outside the predicate code changed")
    differences = [i for i, (old, new) in enumerate(zip(original, data)) if old != new]
    if differences != [0xd9, 0x25f3]:
        raise RuntimeError("Unexpected backport byte changes")
    return layout


def source_hashes() -> dict:
    return {str(path.relative_to(ROOT)): sha(path.read_bytes()) for path in SOURCE_FILES}


def manifest_for(data: bytes) -> dict:
    metadata = patch_metadata()
    return {"revision": REVISION, "asset": ASSET, "dllname": "hidclass.sys", "machine": metadata["machine"],
            "base_sha256": BASE_SHA, "sha256": PATCHED_SHA, "bytes": len(data),
            "license": metadata["license"], "method": metadata["method"],
            "source_commit": metadata["source_commit"], "upstream_fix": metadata["upstream_fix"],
            "source_sha256": source_hashes(), "validation": validate_artifact(data)}


def validate_manifest(manifest: dict, artifact: Path) -> None:
    if manifest != manifest_for(artifact.read_bytes()):
        raise RuntimeError("HID read manifest or artifact is stale/incorrect")


def extract_original(rootfs: Path) -> bytes:
    import zstandard
    member = patch_metadata()["rootfs_member"]
    found = []
    with rootfs.open("rb") as compressed, zstandard.ZstdDecompressor().stream_reader(compressed) as reader:
        with tarfile.open(fileobj=reader, mode="r|") as archive:
            for info in archive:
                if info.name == member:
                    if not info.isfile() or info.size != 65536:
                        raise RuntimeError("Pinned rootfs driver is not the expected regular file")
                    found.append(archive.extractfile(info).read())
    if len(found) != 1:
        raise RuntimeError("Pinned rootfs driver is missing or duplicated")
    return found[0]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rootfs", type=Path, default=ROOT / "app/src/main/assets/rootfs.tzst")
    parser.add_argument("--check", action="store_true")
    arguments = parser.parse_args()
    if arguments.check:
        validate_manifest(json.loads((OUTPUT / "manifest.json").read_text()), OUTPUT / "hidclass.sys")
        print("HID cancel source, exact backport bytes and unchanged PE structures passed.")
        return
    data = make_artifact(extract_original(arguments.rootfs))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "hidclass.sys").write_bytes(data)
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest_for(data), indent=2) + "\n")
    print(json.dumps({"revision": REVISION, "sha256": sha(data), "bytes": len(data)}, indent=2))


if __name__ == "__main__":
    main()
