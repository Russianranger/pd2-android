#!/usr/bin/env python3
"""Verify package-path relocation without depending on upstream runtime assets.

Run: python3 tests/test_runtime_relocation.py
Requires the same zstandard dependency as scripts/relocate-runtime.py.
These checks cover archive transformation, not Android runtime execution.
"""

import importlib.util
import io
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

import zstandard


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "pd2_runtime_relocation", ROOT / "scripts/relocate-runtime.py"
)
relocation = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = relocation
SPEC.loader.exec_module(relocation)


def synthetic_elf():
    """An ELF64 header and PT_INTERP referencing an absolute Android path."""
    interpreter = (
        b"/data/data/com.winlator/files/imagefs/usr/lib/ld-linux-x86-64.so.2\0"
    )
    socket = b"/data/data/com.winlator/files/imagefs/tmp/.wine-0/server-a/socket\0"
    interpreter_offset = 128
    socket_offset = 256
    data = bytearray(b"\xa5" * 512)
    data[:64] = struct.pack(
        "<16sHHIQQQIHHHHHH",
        b"\x7fELF\x02\x01\x01" + b"\0" * 9,
        2, 62, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0,
    )
    data[64:120] = struct.pack(
        "<IIQQQQQQ",
        3, 4, interpreter_offset, 0, 0, len(interpreter), len(interpreter), 1,
    )
    data[interpreter_offset:interpreter_offset + len(interpreter)] = interpreter
    data[socket_offset:socket_offset + len(socket)] = socket
    return bytes(data), interpreter_offset, interpreter, socket_offset, socket


def write_fixture_archive(path):
    binary, _, _, _, _ = synthetic_elf()
    text = (
        b'ROOT="/data/data/com.winlator/files/imagefs"\n'
        b'SOCKET="/data/data/com.winlator/files/bridge.sock"\n'
    )
    tar_bytes = io.BytesIO()
    with tarfile.open(fileobj=tar_bytes, mode="w", format=tarfile.PAX_FORMAT) as archive:
        directory = tarfile.TarInfo("usr/bin")
        directory.type = tarfile.DIRTYPE
        directory.mode = 0o750
        directory.mtime = 1700000000
        archive.addfile(directory)
        for name, data, mode, mtime in (
            ("usr/bin/loader", binary, 0o755, 1700000001),
            ("etc/runtime.conf", text, 0o640, 1700000002),
            ("usr/share/opaque.bin", b"\0\xff\x80unchanged\0", 0o600, 1700000003),
        ):
            member = tarfile.TarInfo(name)
            member.size = len(data)
            member.mode = mode
            member.mtime = mtime
            member.uid = 1000
            member.gid = 1001
            member.uname = "runtime"
            member.gname = "runtime"
            archive.addfile(member, io.BytesIO(data))
        link = tarfile.TarInfo("usr/lib/runtime-link")
        link.type = tarfile.SYMTYPE
        link.linkname = "/data/data/com.winlator/files/imagefs/usr/lib/runtime.so"
        link.mode = 0o777
        link.mtime = 1700000004
        archive.addfile(link)
    path.write_bytes(zstandard.ZstdCompressor(level=3).compress(tar_bytes.getvalue()))


def read_archive(path):
    members = []
    contents = {}
    with path.open("rb") as compressed:
        with zstandard.ZstdDecompressor().stream_reader(compressed) as decoded:
            with tarfile.open(fileobj=decoded, mode="r|") as archive:
                for member in archive:
                    members.append(member)
                    if member.isfile():
                        contents[member.name] = archive.extractfile(member).read()
    return members, contents


class RuntimeRelocationTests(unittest.TestCase):
    def test_package_identity_has_equal_length(self):
        self.assertEqual(relocation.OLD_PACKAGE, b"com.winlator")
        self.assertEqual(relocation.NEW_PACKAGE, b"com.pd2.thor")
        self.assertEqual(len(relocation.OLD_PACKAGE), len(relocation.NEW_PACKAGE))

    def test_elf_interpreter_and_socket_keep_their_offsets(self):
        original, interp_offset, interpreter, socket_offset, socket = synthetic_elf()
        rewritten, count = relocation.rewrite_member_bytes(original)
        self.assertEqual(count, 2)
        self.assertEqual(len(rewritten), len(original))
        self.assertEqual(rewritten[:120], original[:120], "ELF headers changed")
        self.assertEqual(
            rewritten[interp_offset:interp_offset + len(interpreter)],
            interpreter.replace(b"com.winlator", b"com.pd2.thor"),
        )
        self.assertEqual(
            rewritten[socket_offset:socket_offset + len(socket)],
            socket.replace(b"com.winlator", b"com.pd2.thor"),
        )
        expected_changed_offsets = set()
        start = 0
        while (start := original.find(b"com.winlator", start)) >= 0:
            expected_changed_offsets.update(range(start, start + 12))
            start += 12
        for offset, (before, after) in enumerate(zip(original, rewritten)):
            if offset not in expected_changed_offsets:
                self.assertEqual(before, after, f"Unrelated byte changed at {offset}")
        second, count = relocation.rewrite_member_bytes(rewritten)
        self.assertEqual(second, rewritten)
        self.assertEqual(count, 0)

    def test_rewrite_handles_text_and_non_utf8_bytes(self):
        original = b"\xffROOT=com.winlator\0PACKAGE=com.winlator\n\x80"
        result, count = relocation.rewrite_member_bytes(original)
        self.assertEqual(result, b"\xffROOT=com.pd2.thor\0PACKAGE=com.pd2.thor\n\x80")
        self.assertEqual(count, 2)
        unchanged = b"\xff\0already com.pd2.thor\x80"
        self.assertEqual(relocation.rewrite_member_bytes(unchanged), (unchanged, 0))

    def test_rejects_unequal_package_lengths(self):
        with mock.patch.object(relocation, "NEW_PACKAGE", b"com.pd2.longer"):
            with self.assertRaises(ValueError):
                relocation.rewrite_member_bytes(b"/data/data/com.winlator/files")

    def test_archive_preserves_metadata_lengths_and_relocates_symlink(self):
        with tempfile.TemporaryDirectory(prefix="pd2-relocation-test-") as temporary:
            path = Path(temporary) / "runtime.tzst"
            write_fixture_archive(path)
            before_members, before_contents = read_archive(path)
            self.assertEqual(relocation.inspect_archive(path)["old_package_occurrences"], 5)
            relocation.relocate_archive(path)
            after_members, after_contents = read_archive(path)
            self.assertEqual(relocation.inspect_archive(path)["old_package_occurrences"], 0)
            self.assertEqual(len(before_members), len(after_members))
            for before, after in zip(before_members, after_members):
                for attribute in ("name", "type", "size", "mode", "mtime", "uid", "gid", "uname", "gname"):
                    self.assertEqual(
                        getattr(after, attribute), getattr(before, attribute),
                        f"{before.name}: {attribute} changed",
                    )
                self.assertEqual(
                    after.linkname, before.linkname.replace("com.winlator", "com.pd2.thor")
                )
                if before.isfile():
                    self.assertEqual(
                        after_contents[after.name],
                        before_contents[before.name].replace(b"com.winlator", b"com.pd2.thor"),
                    )
            once = path.read_bytes()
            relocation.relocate_archive(path)
            self.assertEqual(path.read_bytes(), once, "A second relocation modified the archive")


if __name__ == "__main__":
    unittest.main(verbosity=2)
