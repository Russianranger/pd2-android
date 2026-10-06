"""Exact PE backport qualification and deterministic source cancellation races."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("hid_read_build", ROOT / "scripts/build-hid-read-runtime.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class HidReadRuntimeTests(unittest.TestCase):
    def artifact(self):
        return (build.OUTPUT / "hidclass.sys").read_bytes()

    def original(self):
        data = bytearray(self.artifact())
        data[0x25f3] = 0x84
        struct.pack_into("<I", data, build.pe_metadata(data)["checksum_offset"], 0x17c78)
        return bytes(data)

    def test_artifact_and_manifest_match_exact_source(self):
        build.validate_manifest(json.loads((build.OUTPUT / "manifest.json").read_text()), build.OUTPUT / "hidclass.sys")

    def test_backport_reproduces_exact_artifact_from_original(self):
        original = self.original()
        self.assertEqual(build.sha(original), build.BASE_SHA)
        self.assertEqual(build.make_artifact(original), self.artifact())

    def test_unknown_original_rejected(self):
        for at in [0, 0x25f3, 0x8300, len(self.original()) - 1]:
            original = bytearray(self.original())
            original[at] ^= 1
            with self.subTest(at=at), self.assertRaisesRegex(RuntimeError, "Unknown HIDclass"):
                build.make_artifact(original)
        with self.assertRaises(RuntimeError):
            build.make_artifact(self.original()[:-1])

    def test_unknown_artifact_and_incorrect_header_rejected(self):
        data = bytearray(self.artifact())
        data[0x25f3] ^= 1
        with self.assertRaises(RuntimeError):
            build.validate_artifact(data)
        data = bytearray(self.artifact())
        pe = struct.unpack_from("<I", data, 0x3c)[0]
        struct.pack_into("<H", data, pe + 4, 0x14c)
        with self.assertRaisesRegex(RuntimeError, "AMD64"):
            build.pe_metadata(data)
        with self.assertRaises(RuntimeError):
            build.pe_metadata(b"MZ")

    def test_only_conditional_jump_and_checksum_changed(self):
        original, updated = self.original(), self.artifact()
        self.assertEqual([i for i, (a, b) in enumerate(zip(original, updated)) if a != b], [0xd9, 0x25f3])
        self.assertEqual(updated[0x25f2:0x25f8], bytes.fromhex("0f85b0000000"))
        first, second = build.pe_metadata(original), build.pe_metadata(updated)
        self.assertEqual(first["directories"], second["directories"])
        for a, b in zip(first["sections"], second["sections"]):
            if a["name"] != ".text":
                self.assertEqual(a, b)
        self.assertEqual(build.checksum(updated, second["checksum_offset"]), second["checksum"])

    def test_manifest_rejects_stale_source_hash_and_identity(self):
        original = json.loads((build.OUTPUT / "manifest.json").read_text())
        for field, value in {"revision": "other", "sha256": "0" * 64, "base_sha256": "0" * 64,
                             "bytes": 1, "source_sha256": {}, "validation": {}}.items():
            changed = copy.deepcopy(original)
            changed[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                build.validate_manifest(changed, build.OUTPUT / "hidclass.sys")

    def test_primary_source_irp_cancellation_ownership_with_sanitizers(self):
        metadata = build.patch_metadata()
        source = (build.SOURCE / "vendor/device.c").read_text()
        begin = source.index("static NTSTATUS hid_queue_push_irp(")
        end = source.index("\nstatic void hid_queue_push_report", begin)
        original = source[begin:end]
        fixed = original.replace("hid_queue_push_irp(", "fixed_hid_queue_push_irp(")
        fixed = fixed.replace(metadata["old_expression"], metadata["new_expression"])
        template = (build.SOURCE / "test_cancel_race.c").read_text()
        self.assertEqual(template.count("/* PD2_SOURCE_FUNCTIONS */"), 1)
        generated = template.replace("/* PD2_SOURCE_FUNCTIONS */", original + "\n" + fixed)
        with tempfile.TemporaryDirectory(prefix="pd2-hid-cancel-test-") as temporary:
            path = Path(temporary)
            (path / "race.c").write_text(generated)
            executable = path / "race"
            subprocess.run(["gcc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-g",
                            "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                            str(path / "race.c"), "-o", str(executable)], check=True, capture_output=True, text=True)
            result = subprocess.run([str(executable)], check=False, capture_output=True, text=True, timeout=15,
                                    env={**os.environ, "ASAN_OPTIONS": "detect_leaks=0:halt_on_error=1",
                                         "UBSAN_OPTIONS": "halt_on_error=1"})
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("original scenario 1: status 00000103, cancel callback owner 0, queued 1", result.stdout)
            self.assertIn("fixed scenario 1: status c0000120, cancel callback owner 0, queued 0", result.stdout)
            self.assertIn("original scenario 2: status c0000120, cancel callback owner 1, queued 0", result.stdout)
            self.assertIn("fixed scenario 2: status 00000103, cancel callback owner 1, queued 1", result.stdout)


if __name__ == "__main__":
    unittest.main()
