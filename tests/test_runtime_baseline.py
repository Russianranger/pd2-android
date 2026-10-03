#!/usr/bin/env python3
"""Verify Wine baseline replacement and pinned input validation on small fixtures."""
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

import zstandard

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("pd2_runtime_baseline", ROOT / "scripts/runtime_baseline.py")
baseline = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = baseline
SPEC.loader.exec_module(baseline)


def tar_bytes(entries, compression=None):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode="w" + (":" + compression if compression else ""),
                      format=tarfile.GNU_FORMAT) as archive:
        for name, payload in entries:
            member = tarfile.TarInfo(name)
            member.mode = 0o751
            member.uid, member.gid = 1000, 1001
            member.uname, member.gname = "runtime", "android"
            member.mtime = 1700000001
            if isinstance(payload, tuple):
                member.type = tarfile.SYMTYPE
                member.linkname = payload[0]
                archive.addfile(member)
            else:
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
    return data.getvalue()


def contents(path):
    result = []
    with path.open("rb") as source:
        with zstandard.ZstdDecompressor().stream_reader(source) as decoded:
            with tarfile.open(fileobj=decoded, mode="r|") as archive:
                for member in archive:
                    metadata = tuple(getattr(member, name) for name in (
                        "name", "mode", "uid", "gid", "uname", "gname", "mtime", "type", "linkname", "size"))
                    result.append((metadata, archive.extractfile(member).read() if member.isfile() else None))
    return result


class BaselineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pd2-baseline-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "base.tzst"
        self.apk = self.root / "upstream.apk"
        self.output = self.root / "output.tzst"
        self.base.write_bytes(zstandard.ZstdCompressor().compress(tar_bytes([
            ("./usr/lib/audio.so", b"\xffAndroid host audio\0"),
            ("./usr/lib/audio-link.so", ("audio.so",)),
            ("./opt/wine/bin/wine", b"old wine"),
            ("./opt/wine/obsolete.dll", b"must disappear"),
            ("./opt/wine-extra/keep", b"prefix boundary"),
        ])))
        self.asset = tar_bytes([
            ("./usr/lib/audio.so", b"DO NOT INSTALL THIS"),
            ("./opt/wine/bin/wine", b"wine 9.2"),
            ("./opt/wine/lib/wine/x86_64-unix/ntdll.so", b"ntdll 9.2"),
            ("./opt/wine/lib/new.dll", b"new builtin"),
        ], "xz")
        self.make_apk(self.asset)
        self.patch_pins()

    def make_apk(self, asset, duplicate=False):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("assets/rootfs.txz", asset)
            if duplicate:
                archive.writestr("assets/rootfs.txz", asset)

    def patch_pins(self, member_hash=None):
        for name, value in (
            ("APK_BYTES", self.apk.stat().st_size),
            ("APK_SHA256", baseline.digest(self.apk)),
            ("APK_MEMBERS", {"assets/rootfs.txz": {
                "bytes": len(self.asset),
                "sha256": member_hash or hashlib.sha256(self.asset).hexdigest(),
            }}),
        ):
            patcher = mock.patch.object(baseline, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_replaces_complete_wine_tree_and_preserves_other_data_and_metadata(self):
        original_bytes = self.base.read_bytes()
        before = contents(self.base)
        summary = baseline.build_rootfs(self.base, self.apk, self.output)
        after = contents(self.output)
        expected_preserved = [item for item in before if not item[0][0].startswith("./opt/wine/")]
        self.assertEqual(after[:len(expected_preserved)], expected_preserved)
        names = [item[0][0] for item in after]
        self.assertNotIn("./opt/wine/obsolete.dll", names)
        self.assertIn("./opt/wine/lib/new.dll", names)
        self.assertEqual(summary["preserved_members"], 3)
        self.assertEqual(summary["removed_wine_members"], 2)
        self.assertEqual(summary["added_wine_members"], 3)
        self.assertEqual(self.base.read_bytes(), original_bytes)
        first = self.output.read_bytes()
        baseline.build_rootfs(self.base, self.apk, self.output)
        self.assertEqual(self.output.read_bytes(), first)
        frame = zstandard.get_frame_parameters(first)
        self.assertTrue(frame.has_checksum)
        self.assertEqual(frame.content_size, zstandard.CONTENTSIZE_UNKNOWN)

    def test_rejects_modified_apk_without_touching_existing_output(self):
        self.output.write_bytes(b"existing destination")
        self.apk.write_bytes(self.apk.read_bytes() + b"modified")
        with self.assertRaises(baseline.IntegrityError):
            baseline.build_rootfs(self.base, self.apk, self.output)
        self.assertEqual(self.output.read_bytes(), b"existing destination")

    def test_rejects_member_hash_mismatch(self):
        with mock.patch.dict(baseline.APK_MEMBERS["assets/rootfs.txz"], {"sha256": "0" * 64}):
            with self.assertRaises(baseline.IntegrityError):
                baseline.read_apk_member(self.apk, "assets/rootfs.txz")

    def test_rejects_duplicate_member(self):
        with self.assertWarns(UserWarning):
            self.make_apk(self.asset, duplicate=True)
        self.patch_pins()
        with self.assertRaises(baseline.IntegrityError):
            baseline.read_apk_member(self.apk, "assets/rootfs.txz")

    def test_invalid_wine_tree_leaves_destination_and_cleans_temporary_file(self):
        self.asset = tar_bytes([("./opt/wine/bin/wine", b"missing ntdll")], "xz")
        self.make_apk(self.asset)
        self.patch_pins()
        self.output.write_bytes(b"existing destination")
        with self.assertRaises(baseline.IntegrityError):
            baseline.build_rootfs(self.base, self.apk, self.output)
        self.assertEqual(self.output.read_bytes(), b"existing destination")
        self.assertEqual(list(self.root.glob(".pd2-baseline-*")), [])

    def test_rejects_archive_path_traversal(self):
        with self.assertRaises(baseline.IntegrityError):
            baseline._path("./opt/wine/../../outside")


if __name__ == "__main__":
    unittest.main(verbosity=2)
