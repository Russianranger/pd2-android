"""Host checks for the production Wine9 HID producer and its committed artifact."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("controller_build", ROOT / "scripts/build-controller-runtime.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class ControllerRuntimeTests(unittest.TestCase):
    def test_manifest_matches_artifact_and_build_sources(self):
        build.validate_vendor()
        build.validate_manifest(json.loads((build.OUTPUT / "manifest.json").read_text()), build.OUTPUT / "winebus.so")

    def test_manifest_rejects_wrong_identity_abi_and_stale_sources(self):
        original = json.loads((build.OUTPUT / "manifest.json").read_text())
        changes = {
            "revision": "different", "base_sha256": "0" * 64, "asset": "wrong.so",
            "vendor_commit": "0" * 40, "license": "unknown", "sha256": "0" * 64,
            "bytes": 1, "validation": {"wine9_abi": {"entries": 18}},
            "source_sha256": {"pd2_bus.c": "0" * 64},
        }
        for field, value in changes.items():
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                manifest = copy.deepcopy(original)
                manifest[field] = value
                build.validate_manifest(manifest, build.OUTPUT / "winebus.so")

    def test_production_udp_hid_and_lifecycle_with_sanitizers(self):
        with tempfile.TemporaryDirectory(prefix="pd2-controller-test-") as temporary:
            directory = Path(temporary)
            (directory / "config.h").write_text(build.CONFIG)
            glue = build.generated_glue(directory)
            sources = [build.SOURCE / "test_hid.c", build.VENDOR / "dlls/winebus.sys/hid.c", glue]
            sources += [build.VENDOR / f"dlls/winebus.sys/{name}.c" for name in ["bus_sdl", "bus_udev", "bus_iohid"]]
            executable = directory / "hid-test"
            subprocess.run(["gcc", *build.compile_flags(directory), "-g", "-fsanitize=address,undefined",
                            "-fno-sanitize=alignment", "-fno-omit-frame-pointer", *map(str, sources),
                            "-pthread", "-o", str(executable)], check=True, capture_output=True, text=True)
            # Wine's pinned HID helper intentionally uses unaligned x86 writes.
            # LeakSanitizer cannot inspect /proc in this runner; ASan/UBSan remain enabled.
            result = subprocess.run([str(executable)], check=True, capture_output=True, text=True,
                                    timeout=20, env={**os.environ, "ASAN_OPTIONS": "detect_leaks=0:halt_on_error=1",
                                                     "UBSAN_OPTIONS": "halt_on_error=1"})
            self.assertIn("replug and shutdown tests passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
