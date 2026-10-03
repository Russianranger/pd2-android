#!/usr/bin/env python3
"""Exercise binary fetch integrity, retry behavior and atomic placement."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
import zipfile
from unittest import mock

SPEC = importlib.util.spec_from_file_location(
    "pd2_runtime_fetch", Path(__file__).resolve().parents[1] / "scripts/fetch-runtime.py")
fetch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetch)


def inventory(data, installed=None):
    name = "app/src/main/assets/runtime.tzst"
    entry = {"upstream_sha256": hashlib.sha256(data).hexdigest(),
             "installed_sha256": hashlib.sha256(installed or data).hexdigest(),
             "upstream_bytes": len(data)}
    return name, entry, {"format": 1, "upstream_repository": "brunodev85/winlator-app",
                         "upstream_commit": "a" * 40, "files": {name: entry}}


class FetchTests(unittest.TestCase):
    def test_known_superseded_runtime_is_replaced_and_final_check_rejects_it(self):
        name, entry, manifest = inventory(b"new runtime")
        old = b"old Wine runtime"
        entry["superseded_sha256"] = [hashlib.sha256(old).hexdigest()]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / name).parent.mkdir(parents=True)
            (root / name).write_bytes(old)
            self.assertFalse(fetch.verify_existing(root / name, entry))
            with self.assertRaises(fetch.IntegrityError):
                fetch.check(root, manifest, final=True)
            with mock.patch.object(fetch.urllib.request, "urlopen", return_value=io.BytesIO(b"new runtime")):
                self.assertTrue(fetch.fetch_one(root, manifest, name, entry))
            self.assertEqual((root / name).read_bytes(), b"new runtime")

    def test_network_download_is_streamed_verified_and_placed(self):
        data = b"a" * (fetch.CHUNK_BYTES + 73)
        name, entry, manifest = inventory(data)
        reads = []

        class Stream(io.BytesIO):
            def read(self, count=-1):
                reads.append(count)
                return super().read(count)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with mock.patch.object(fetch.urllib.request, "urlopen", return_value=Stream(data)) as open_url:
                self.assertTrue(fetch.fetch_one(root, manifest, name, entry))
            self.assertEqual((root / name).read_bytes(), data)
            self.assertTrue(all(count == fetch.CHUNK_BYTES for count in reads))
            self.assertEqual(open_url.call_args.args[0].full_url,
                             "https://raw.githubusercontent.com/brunodev85/winlator-app/"
                             + "a" * 40 + "/" + name)
            self.assertEqual(list(root.rglob(".pd2-fetch-*")), [])

    def test_existing_relocated_file_is_reused_without_network(self):
        name, entry, manifest = inventory(b"original", b"relocated")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / name).parent.mkdir(parents=True)
            (root / name).write_bytes(b"relocated")
            with mock.patch.object(fetch.urllib.request, "urlopen") as open_url:
                self.assertFalse(fetch.fetch_one(root, manifest, name, entry))
                open_url.assert_not_called()

    def test_unexpected_existing_file_is_preserved(self):
        name, entry, manifest = inventory(b"original")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / name).parent.mkdir(parents=True)
            (root / name).write_bytes(b"local change")
            with self.assertRaises(fetch.IntegrityError):
                fetch.fetch_one(root, manifest, name, entry)
            self.assertEqual((root / name).read_bytes(), b"local change")

    def test_wrong_checksum_never_installs_binary(self):
        name, entry, manifest = inventory(b"original")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with mock.patch.object(fetch.urllib.request, "urlopen", return_value=io.BytesIO(b"tampered")):
                with self.assertRaises(fetch.IntegrityError):
                    fetch.fetch_one(root, manifest, name, entry)
            self.assertFalse((root / name).exists())
            self.assertEqual(list(root.rglob(".pd2-fetch-*")), [])

    def test_network_error_retries_and_cleans_temporary_files(self):
        data = b"original"
        name, entry, manifest = inventory(data)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with mock.patch.object(fetch.urllib.request, "urlopen", side_effect=[
                    urllib.error.URLError("temporary network failure"), io.BytesIO(data)]) as open_url:
                with mock.patch.object(fetch.time, "sleep"):
                    self.assertTrue(fetch.fetch_one(root, manifest, name, entry))
            self.assertEqual(open_url.call_count, 2)
            self.assertEqual((root / name).read_bytes(), data)
            self.assertEqual(list(root.rglob(".pd2-fetch-*")), [])

    def test_interrupted_stream_never_leaves_partial_destination(self):
        name, entry, manifest = inventory(b"original")

        class BrokenStream(io.BytesIO):
            def read(self, count=-1):
                raise TimeoutError("connection interrupted")

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with mock.patch.object(fetch.urllib.request, "urlopen", return_value=BrokenStream()):
                with self.assertRaises(TimeoutError):
                    fetch.fetch_one(root, manifest, name, entry, attempts=1)
            self.assertFalse((root / name).exists())
            self.assertEqual(list(root.rglob(".pd2-fetch-*")), [])

    def test_local_cache_is_verified_before_copy(self):
        name, entry, manifest = inventory(b"original")
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as cached:
            root, cache = Path(tmp), Path(cached)
            (cache / name).parent.mkdir(parents=True)
            (cache / name).write_bytes(b"original")
            with mock.patch.object(fetch.urllib.request, "urlopen") as open_url:
                self.assertTrue(fetch.fetch_one(root, manifest, name, entry, cache_dir=cache))
                open_url.assert_not_called()
            self.assertEqual((root / name).read_bytes(), b"original")

    def test_final_check_rejects_an_unrelocated_original(self):
        name, entry, manifest = inventory(b"original", b"relocated")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / name).parent.mkdir(parents=True)
            (root / name).write_bytes(b"original")
            fetch.check(root, manifest)
            with self.assertRaises(fetch.IntegrityError):
                fetch.check(root, manifest, final=True)

    def test_inventory_rejects_paths_outside_project(self):
        _, entry, manifest = inventory(b"original")
        manifest["files"] = {"../outside": entry}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "inventory.json"
            path.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                fetch.load_manifest(path)

    def test_apk_member_hash_and_duplicate_entries_are_checked(self):
        payload = b"matched Wine prefix"
        expected = {"sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload)}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk, target = root / "release.apk", root / "prefix.tzst"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/container_pattern.tzst", payload)
            fetch.apk_member(apk, "assets/container_pattern.tzst", target, expected)
            self.assertEqual(target.read_bytes(), payload)
            expected["sha256"] = "0" * 64
            with self.assertRaises(fetch.IntegrityError):
                fetch.apk_member(apk, "assets/container_pattern.tzst", target, expected)
            with zipfile.ZipFile(apk, "a") as archive:
                with self.assertWarns(UserWarning):
                    archive.writestr("assets/container_pattern.tzst", payload)
            with self.assertRaises(fetch.IntegrityError):
                fetch.apk_member(apk, "assets/container_pattern.tzst", target, expected)

    def test_baseline_release_is_verified_and_reused_without_network(self):
        members = {"assets/rootfs.txz": b"older Wine rootfs", "assets/container_pattern.tzst": b"matched prefix",
                   "assets/common_dlls.json": b"{}"}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / "release.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name, content in members.items():
                    archive.writestr(name, content)
            name, entry, manifest = inventory(members["assets/container_pattern.tzst"])
            entry.update({"source": "winlator10.1-apk", "apk_member": "assets/container_pattern.tzst"})
            manifest["wine_baseline"] = {
                "apk": {"url": fetch.WINE_APK_URL, "sha256": fetch.sha256(apk), "bytes": apk.stat().st_size},
                "rootfs_base": {"path": "app/src/main/assets/rootfs.tzst", "sha256": "a" * 64, "bytes": 1},
                "members": {member: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
                            for member, data in members.items()}}
            inventory_path = root / "manifest.json"
            inventory_path.write_text(json.dumps(manifest))
            fetch.load_manifest(inventory_path)
            with mock.patch.object(fetch.urllib.request, "urlopen") as network:
                self.assertEqual(fetch.fetch_baseline(root, manifest, apk_override=apk), 1)
                self.assertEqual(fetch.fetch_baseline(root, manifest, apk_override=apk), 0)
                network.assert_not_called()
            self.assertEqual((root / name).read_bytes(), members["assets/container_pattern.tzst"])
            self.assertEqual(list(root.rglob(".pd2-fetch-*")), [])
            (root / name).unlink()
            apk.write_bytes(b"untrusted release")
            with self.assertRaises(fetch.IntegrityError):
                fetch.fetch_baseline(root, manifest, apk_override=apk)
            self.assertFalse((root / name).exists())

    def test_baseline_inventory_rejects_unpinned_release_url(self):
        _, _, manifest = inventory(b"binary")
        manifest["wine_baseline"] = {"apk": {"url": "https://example.com/latest.apk"}}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "manifest.json"
            path.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                fetch.load_manifest(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
