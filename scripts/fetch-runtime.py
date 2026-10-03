#!/usr/bin/env python3
"""Fetch the pinned upstream binary build dependencies with SHA-256 verification.

The downloadable files are build dependencies, not user-imported game files.
Existing files are accepted only if they match their original upstream hash or
the documented relocated hash. Wine 9.2 is composed from a separately pinned
official release APK and the original rootfs. Requires Python 3.11 or newer and
zstandard==0.25.0 for that deterministic archive conversion.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("docs/RUNTIME-DEPENDENCIES.json")
MAX_WORKERS = 8
CHUNK_BYTES = 1024 * 1024
WINE_APK_URL = "https://github.com/brunodev85/winlator/releases/download/v10.1.0/Winlator_10.1.apk"
BASELINE_MEMBERS = {"assets/rootfs.txz", "assets/container_pattern.tzst", "assets/common_dlls.json"}


class IntegrityError(ValueError):
    """An asset differs from its pinned expected bytes."""


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def load_manifest(path: Path) -> dict:
    manifest = json.loads(path.read_text())
    if manifest.get("format") != 1:
        raise ValueError("Unsupported binary dependency inventory")
    if manifest.get("upstream_repository") != "brunodev85/winlator-app":
        raise ValueError("Binary dependencies must use the documented upstream repository")
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("upstream_commit", "")):
        raise ValueError("Binary dependencies require an immutable full commit SHA")
    if not manifest.get("files"):
        raise ValueError("Empty binary dependency inventory")
    for name, entry in manifest["files"].items():
        relative = PurePosixPath(name)
        if not relative.parts or relative.is_absolute() or ".." in relative.parts or "\\" in name or "\x00" in name or relative.parts[0] != "app":
            raise ValueError("Unsafe binary dependency path: " + name)
        for field in ("upstream_sha256", "installed_sha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", entry.get(field, "")):
                raise ValueError("Invalid binary dependency SHA-256: " + name)
        if not isinstance(entry.get("upstream_bytes"), int) or entry["upstream_bytes"] < 1:
            raise ValueError("Invalid binary dependency size: " + name)
        for checksum in entry.get("superseded_sha256", []):
            if not re.fullmatch(r"[0-9a-f]{64}", checksum):
                raise ValueError("Invalid superseded dependency SHA-256: " + name)
        source = entry.get("source")
        if source not in (None, "wine9-rootfs", "winlator10.1-apk"):
            raise ValueError("Unsupported binary dependency source: " + name)
        if source == "winlator10.1-apk" and entry.get("apk_member") not in BASELINE_MEMBERS:
            raise ValueError("Unsupported release APK member: " + name)
    baseline = manifest.get("wine_baseline")
    if baseline:
        if baseline.get("apk", {}).get("url") != WINE_APK_URL:
            raise ValueError("Wine baseline must use the pinned official Winlator release")
        for asset in [baseline["apk"], baseline["rootfs_base"], *baseline["members"].values()]:
            if not re.fullmatch(r"[0-9a-f]{64}", asset.get("sha256", "")) or not isinstance(asset.get("bytes"), int) or asset["bytes"] < 1:
                raise ValueError("Invalid Wine baseline source hash or size")
        if set(baseline["members"]) != BASELINE_MEMBERS:
            raise ValueError("Wine baseline APK member inventory changed")
        if baseline["rootfs_base"].get("path") != "app/src/main/assets/rootfs.tzst":
            raise ValueError("Wine baseline must retain the pinned original rootfs")
    elif any(entry.get("source") for entry in manifest["files"].values()):
        raise ValueError("Wine baseline source metadata is missing")
    return manifest


def destination(root: Path, name: str) -> Path:
    path = root / name
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("Binary dependency path escapes project: " + name)
    return path


def verify_existing(path: Path, entry: dict, *, final: bool = False) -> bool:
    if not path.exists():
        return False
    if not path.is_file():
        raise IntegrityError("Binary dependency is not a regular file: " + str(path))
    acceptable = {entry["installed_sha256"]}
    if not final:
        acceptable.add(entry["upstream_sha256"])
    actual = sha256(path)
    if not final and actual in entry.get("superseded_sha256", []):
        return False
    if actual not in acceptable:
        raise IntegrityError("Binary dependency has unexpected bytes: " + str(path))
    return True


def _write_verified(source, target, entry: dict) -> None:
    checksum = hashlib.sha256()
    count = 0
    while block := source.read(CHUNK_BYTES):
        count += len(block)
        if count > entry["upstream_bytes"]:
            raise IntegrityError("Downloaded binary exceeds its pinned size")
        checksum.update(block)
        target.write(block)
    if count != entry["upstream_bytes"] or checksum.hexdigest() != entry["upstream_sha256"]:
        raise IntegrityError("Downloaded binary does not match its pinned SHA-256 and size")


def verify_source(path: Path, expected: dict) -> None:
    if not path.is_file() or path.stat().st_size != expected["bytes"] or sha256(path) != expected["sha256"]:
        raise IntegrityError("Wine baseline source differs from its pinned bytes: " + str(path))


def download_source(url: str, path: Path, expected: dict, *, attempts: int = 3) -> None:
    """Write a pinned build input; never use a partially downloaded archive."""
    entry = {"upstream_sha256": expected["sha256"], "upstream_bytes": expected["bytes"]}
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "PD2-Android-runtime-bootstrap/0.1"})
            with path.open("wb") as target, urllib.request.urlopen(request, timeout=30) as source:
                _write_verified(source, target, entry)
            return
        except IntegrityError:
            path.unlink(missing_ok=True)
            raise
        except (urllib.error.URLError, TimeoutError, OSError):
            path.unlink(missing_ok=True)
            if attempt + 1 == attempts:
                raise
            time.sleep(min(attempt + 1, 2))


def apk_member(apk: Path, name: str, destination_path: Path, expected: dict) -> None:
    entry = {"upstream_sha256": expected["sha256"], "upstream_bytes": expected["bytes"]}
    with zipfile.ZipFile(apk) as archive:
        infos = [info for info in archive.infolist() if info.filename == name]
        if len(infos) != 1 or infos[0].file_size != expected["bytes"]:
            raise IntegrityError("Wine baseline APK member missing, duplicated or wrong size: " + name)
        with archive.open(infos[0]) as source, destination_path.open("wb") as target:
            _write_verified(source, target, entry)


def fetch_baseline(root: Path, manifest: dict, *, apk_override: Path | None = None,
                   cache_dir: Path | None = None) -> int:
    """Compose Wine 9.2 and its matching prefix without blending Wine DLLs."""
    entries = [(name, entry) for name, entry in manifest["files"].items() if entry.get("source")]
    pending = [(name, entry) for name, entry in entries if not verify_existing(destination(root, name), entry)]
    if not pending:
        return 0
    baseline = manifest["wine_baseline"]
    with tempfile.TemporaryDirectory(prefix="pd2-wine-baseline-") as temporary_dir:
        temporary = Path(temporary_dir)
        apk = apk_override if apk_override else temporary / "Winlator_10.1.apk"
        if apk_override:
            verify_source(apk, baseline["apk"])
        else:
            download_source(baseline["apk"]["url"], apk, baseline["apk"])
        base = temporary / "rootfs-original.tzst"
        if any(entry["source"] == "wine9-rootfs" for _, entry in pending):
            expected = baseline["rootfs_base"]
            if cache_dir:
                cached = destination(cache_dir, expected["path"])
                verify_source(cached, expected)
                base = cached
            else:
                url = "https://raw.githubusercontent.com/{}/{}/{}".format(
                    manifest["upstream_repository"], manifest["upstream_commit"], expected["path"])
                download_source(url, base, expected)
        for name, entry in pending:
            path = destination(root, name)
            path.parent.mkdir(parents=True, exist_ok=True)
            fd, staged_name = tempfile.mkstemp(prefix=".pd2-fetch-", dir=path.parent)
            os.close(fd)
            staged = Path(staged_name)
            try:
                if entry["source"] == "wine9-rootfs":
                    # Import only when conversion is required, after validating both inputs.
                    import importlib.util
                    spec = importlib.util.spec_from_file_location("pd2_runtime_baseline", ROOT / "scripts/runtime_baseline.py")
                    converter = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(converter)
                    converter.build_rootfs(base, apk, staged)
                else:
                    member = entry["apk_member"]
                    apk_member(apk, member, staged, baseline["members"][member])
                if staged.stat().st_size != entry["upstream_bytes"] or sha256(staged) != entry["upstream_sha256"]:
                    raise IntegrityError("Composed Wine baseline differs from the deterministic inventory: " + name)
                os.chmod(staged, 0o644)
                os.replace(staged, path)
            finally:
                staged.unlink(missing_ok=True)
    return len(pending)


def fetch_one(root: Path, manifest: dict, name: str, entry: dict,
              *, cache_dir: Path | None = None, attempts: int = 3) -> bool:
    path = destination(root, name)
    if verify_existing(path, entry):
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    url = "https://raw.githubusercontent.com/{}/{}/{}".format(
        manifest["upstream_repository"], manifest["upstream_commit"],
        urllib.parse.quote(name, safe="/"))
    for attempt in range(attempts):
        fd, temporary_name = tempfile.mkstemp(prefix=".pd2-fetch-", dir=path.parent)
        temporary = Path(temporary_name)
        try:
            with os.fdopen(fd, "wb") as target:
                if cache_dir is not None:
                    with destination(cache_dir, name).open("rb") as source:
                        _write_verified(source, target, entry)
                else:
                    request = urllib.request.Request(url, headers={"User-Agent": "PD2-Android-runtime-bootstrap/0.1"})
                    with urllib.request.urlopen(request, timeout=30) as source:
                        _write_verified(source, target, entry)
                target.flush()
                os.fsync(target.fileno())
            os.chmod(temporary, 0o644)
            # Recheck before replacing in case another process populated it.
            if verify_existing(path, entry):
                return False
            os.replace(temporary, path)
            return True
        except IntegrityError:
            raise
        except (urllib.error.URLError, TimeoutError, OSError):
            if cache_dir is not None or attempt + 1 == attempts:
                raise
            time.sleep(min(attempt + 1, 2))
        finally:
            temporary.unlink(missing_ok=True)
    raise RuntimeError("No download attempts made")


def check(root: Path, manifest: dict, *, final: bool = False) -> None:
    missing = []
    for name, entry in manifest["files"].items():
        if not verify_existing(destination(root, name), entry, final=final):
            missing.append(name)
    if missing:
        raise IntegrityError("Missing binary dependencies: " + ", ".join(missing))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--cache-dir", type=Path, help="Optional local tree containing exact original upstream files")
    parser.add_argument("--wine-apk", type=Path, help="Optional local official Winlator 10.1 APK; pinned hash is still required")
    parser.add_argument("--workers", type=int, default=4)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="Verify existing original or relocated files without downloads")
    mode.add_argument("--check-final", action="store_true", help="Verify all files match their final relocated hashes")
    args = parser.parse_args()
    if not 1 <= args.workers <= MAX_WORKERS:
        parser.error("--workers must be between 1 and " + str(MAX_WORKERS))
    root = args.root.resolve()
    manifest = load_manifest(root / MANIFEST)
    if args.check or args.check_final:
        check(root, manifest, final=args.check_final)
        print("Verified " + str(len(manifest["files"])) + " pinned binary build dependencies")
        return
    cache_dir = args.cache_dir.resolve() if args.cache_dir else None
    items = [(name, entry) for name, entry in manifest["files"].items() if not entry.get("source")]
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        results = list(executor.map(
            lambda item: fetch_one(root, manifest, *item, cache_dir=cache_dir), items))
    baseline_count = fetch_baseline(root, manifest, apk_override=args.wine_apk, cache_dir=cache_dir)
    check(root, manifest)
    print("Fetched " + str(sum(results) + baseline_count) + " and reused " + str(len(manifest["files"]) - sum(results) - baseline_count)
          + " pinned upstream binaries at " + manifest["upstream_commit"])


if __name__ == "__main__":
    main()
