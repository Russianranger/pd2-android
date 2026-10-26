#!/usr/bin/env python3
"""Run the donor and repaired PE32 XInput libraries in the exact packaged Wine9.

Requires x86-64 Linux, working AF_UNIX sockets and Python zstandard. The small
PE32 probe is source/toolchain/hash pinned; --cc optionally rebuilds it exactly.
The existing runtime-fetch/final-hash checks must have prepared the APK assets.
No system Wine, game files, controller devices or existing Wine prefix are used.
"""
from __future__ import annotations

import argparse
import errno
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import queue
import shutil
import signal
import socket
import struct
import subprocess
import tarfile
import tempfile
import threading
import time
import zipfile

import zstandard

ROOT = Path(__file__).resolve().parents[1]
DONOR_SHA = "c8f692e7fe22fb922886026d97bf39549c8eac12629fee3e06f49078b34a60fa"
DLL_PATH = "opt/wine/lib/wine/i386-windows/xinput1_4.dll"
PROBE_SOURCE = "runtime/xinput-reader/test_wine.c"
PROBE_FIXTURE = "tests/fixtures/xinput-reader-probe.exe"
TOOLCHAIN = "runtime/xinput-reader/TOOLCHAIN.json"
PROBE_FLAGS = ("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-s", "-Wl,--no-insert-timestamp")
# The exact donor hardcodes this in server/request.c and ntdll/unix/server.c;
# TMPDIR cannot redirect it. CI creates only this empty, runner-owned parent.
ANDROID_SERVER_TMP = Path("/data/data/com.pd2.thor/files/rootfs/tmp")
PATTERNS = (
    # Buttons and Y axes retain the donor's digital-trigger and inversion ABI.
    ((1 << 0) | (1 << 4) | (1 << 10), 0, 12000, -9000, -14000, 7000),
    ((1 << 1) | (1 << 5) | (1 << 8) | (1 << 11), 5, -17000, 11000, 19000, -15000),
)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_probe_fixture(compiler: str | None) -> Path:
    metadata = json.loads((ROOT / "tests/fixtures/xinput-reader-probe.json").read_text())
    assert metadata["format"] == 1 and metadata["file"] == PROBE_FIXTURE
    assert metadata["source"]["path"] == PROBE_SOURCE
    assert metadata["toolchain"]["path"] == TOOLCHAIN
    assert metadata["source"]["sha256"] == digest((ROOT / PROBE_SOURCE).read_bytes()), "probe C source changed"
    assert metadata["toolchain"]["sha256"] == digest((ROOT / TOOLCHAIN).read_bytes()), "probe toolchain provenance changed"
    assert metadata["rebuild_command"] == ["i686-w64-mingw32-gcc-win32", *PROBE_FLAGS,
                                           PROBE_SOURCE, "-o", PROBE_FIXTURE]
    fixture = ROOT / PROBE_FIXTURE
    data = fixture.read_bytes()
    assert len(data) == metadata["bytes"] and digest(data) == metadata["sha256"], "probe PE fixture changed"
    assert data[:2] == b"MZ"
    pe = struct.unpack_from("<I", data, 0x3c)[0]
    assert data[pe:pe+4] == b"PE\0\0" and struct.unpack_from("<H", data, pe+4)[0] == 0x14c
    assert struct.unpack_from("<I", data, pe+8)[0] == 0, "probe timestamp must be reproducible"
    assert struct.unpack_from("<H", data, pe+24)[0] == 0x10b, "probe must be PE32"
    if compiler:
        version = subprocess.check_output([compiler, "--version"], text=True).splitlines()[0]
        assert version == metadata["toolchain"]["compiler"], (version, metadata["toolchain"]["compiler"])
        with tempfile.TemporaryDirectory(prefix="pd2-xinput-probe-rebuild-") as temporary:
            output = Path(temporary) / fixture.name
            subprocess.run([compiler, *PROBE_FLAGS, PROBE_SOURCE, "-o", str(output)], cwd=ROOT, check=True)
            assert output.read_bytes() == data, "rebuilt PE fixture is not byte-identical"
    print("Verified source/toolchain-pinned PE32 probe: " + metadata["sha256"], flush=True)
    return fixture


def asset_bytes(name: str, apk: Path | None) -> bytes:
    if apk:
        with zipfile.ZipFile(apk) as archive:
            data = archive.read("assets/" + name)
    else:
        data = (ROOT / "app/src/main/assets" / name).read_bytes()
    manifest = json.loads((ROOT / "docs/RUNTIME-DEPENDENCIES.json").read_text())
    entry = manifest["files"]["app/src/main/assets/" + name]
    assert 0 < len(data) <= 100 * 1024 * 1024, (name, len(data))
    assert digest(data) == entry["installed_sha256"], (name, digest(data))
    return data


def extract_archive(data: bytes, destination: Path, wine_only: bool = False) -> None:
    with zstandard.ZstdDecompressor().stream_reader(io.BytesIO(data)) as raw:
        with tarfile.open(fileobj=raw, mode="r|") as archive:
            for member in archive:
                name = member.name.removeprefix("./")
                if wine_only and name != "opt/wine" and not name.startswith("opt/wine/"):
                    continue
                path = PurePosixPath(name)
                assert not path.is_absolute() and ".." not in path.parts, name
                if not wine_only and name.startswith(".wine/dosdevices/"):
                    # Recreate the only two template symlinks explicitly below.
                    if member.issym():
                        assert (name, member.linkname) in {
                            (".wine/dosdevices/c:", "../drive_c"),
                            (".wine/dosdevices/z:", "/"),
                        }, (name, member.linkname)
                        continue
                member.name = name
                archive.extract(member, destination, filter="data")
    if not wine_only:
        devices = destination / ".wine/dosdevices"
        (devices / "c:").symlink_to("../drive_c")
        (devices / "z:").symlink_to("/")


class Peer:
    """The app side of the exact 64-byte legacy protocol, isolated to the test."""
    def __init__(self):
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(("127.0.0.1", 7947))
        self.socket.settimeout(0.025)
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.pattern = 0
        self.silent = False
        self.remote = None
        self.discoveries = 0
        self.states = 0
        self.errors: list[str] = []
        self.thread = threading.Thread(target=self.run, daemon=True)

    def state(self) -> bytes:
        buttons, hat, lx, ly, rx, ry = PATTERNS[self.pattern]
        result = bytearray(64)
        struct.pack_into("<BBihBhhhh", result, 0, 9, 1, 1, buttons, hat, lx, ly, rx, ry)
        return bytes(result)

    def send_state(self) -> None:
        assert self.remote is not None
        try:
            self.socket.sendto(self.state(), self.remote)
            self.states += 1
        except OSError as error:
            if error.errno != errno.ECONNREFUSED:
                raise

    def run(self) -> None:
        try:
            self.serve()
        except Exception as error:
            self.errors.append(type(error).__name__ + ": " + str(error))
            self.stop_event.set()

    def serve(self) -> None:
        last = 0.0
        while not self.stop_event.is_set():
            try:
                request, remote = self.socket.recvfrom(512)
            except socket.timeout:
                request = b""
                remote = None
            except OSError as error:
                if self.stop_event.is_set():
                    break
                if error.errno == errno.ECONNREFUSED:
                    continue  # The repaired receiver deliberately closes/reopens its socket.
                self.errors.append(str(error))
                continue
            with self.lock:
                if request:
                    if remote != ("127.0.0.1", 7949) or len(request) != 64 or request[0] not in (8, 10):
                        self.errors.append("unexpected peer request")
                    elif request[0] == 8:
                        assert request[1:3] == b"\x01\x01"
                        self.remote = remote
                        self.discoveries += 1
                        if not self.silent:
                            device = bytearray(64)
                            struct.pack_into("<BiBi", device, 0, 8, 1, 1, 0)
                            self.socket.sendto(device, remote)
                            self.send_state()
                now = time.monotonic()
                if self.remote and not self.silent and now - last >= 0.025:
                    self.send_state()
                    last = now

    def fault(self) -> None:
        with self.lock:
            assert self.remote
            # The donor's 64-byte recvfrom returns WSAEMSGSIZE and exits forever.
            self.socket.sendto(bytes([0xff]) + bytes(127), self.remote)
            self.pattern = 1

    def pause(self) -> None:
        with self.lock:
            self.silent = True

    def resume(self) -> None:
        with self.lock:
            self.pattern = 0
            self.silent = False

    def close(self) -> None:
        self.stop_event.set()
        self.thread.join(timeout=2)
        self.socket.close()
        assert not self.thread.is_alive(), "peer did not stop"
        assert not self.errors, self.errors


def read_lines(pipe, lines: queue.Queue) -> None:
    try:
        for line in iter(pipe.readline, ""):
            lines.put(line.rstrip("\r\n"))
    finally:
        lines.put(None)


def bounded_text(path: Path, limit: int = 12000) -> str:
    if not path.exists():
        return ""
    size = path.stat().st_size
    with path.open("rb") as source:
        if size <= limit:
            data = source.read(limit)
        else:
            data = source.read(limit // 2)
            source.seek(-limit // 2, os.SEEK_END)
            data += b"\n[diagnostic middle omitted]\n" + source.read(limit // 2)
    return data.decode("utf-8", errors="replace")


def signal_name(code) -> str | None:
    if not isinstance(code, int) or code >= 0:
        return None
    try:
        return signal.Signals(-code).name
    except ValueError:
        return "signal " + str(-code)


def prefix_processes(prefix: Path) -> list[dict]:
    """Describe only this test prefix's processes, without exporting environments."""
    result = []
    marker = ("WINEPREFIX=" + str(prefix)).encode()
    for directory in Path("/proc").iterdir():
        if not directory.name.isdigit():
            continue
        try:
            if marker not in (directory / "environ").read_bytes().split(b"\0"):
                continue
            status = (directory / "status").read_text()
            item = {}
            for line in status.splitlines():
                key, _, value = line.partition(":")
                if key in {"Name", "Pid", "PPid", "Threads", "State"}:
                    item[key] = value.strip()
            result.append(item)
        except (OSError, ValueError):
            continue
    return result[:32]


def startup_diagnostics(wine: Path, prefix: Path, environment: dict, attempt: Path) -> list[dict]:
    """Failure-only PE64/PE32 console markers; this never turns a failure into a pass."""
    diagnostic_env = environment.copy()
    diagnostic_env["WINEDEBUG"] = "-all,err+all,warn+all,+loaddll,+module,+process,+seh"
    results = []
    for bits, folder in ((64, "system32"), (32, "syswow64")):
        marker = "PD2_WINE" + str(bits) + "_READY"
        command = [str(wine / "bin/wine"), "C:\\windows\\" + folder + "\\cmd.exe", "/c", "echo", marker]
        stdout_path, stderr_path = attempt / f"startup-{bits}.out", attempt / f"startup-{bits}.err"
        with stdout_path.open("w") as output, stderr_path.open("w") as errors:
            process = subprocess.Popen(command, env=diagnostic_env, cwd=attempt, stdin=subprocess.DEVNULL,
                                       stdout=output, stderr=errors, start_new_session=True)
            try:
                code = process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=2)
                code = "timeout"
        results.append({"bits": bits, "command": command, "returncode": code,
                        "unix_returncode_hex": hex(code & 0xffffffff) if isinstance(code, int) else None,
                        "signal": signal_name(code),
                        "stdout": bounded_text(stdout_path, 2000), "stderr": bounded_text(stderr_path)})
    return results


def debugger_diagnostics(wine: Path, environment: dict, attempt: Path) -> dict:
    """Capture first/second startup stops without treating first-chance faults as fatal."""
    debugger = shutil.which("gdb")
    if not debugger:
        return {"available": False}
    diagnostics = attempt / "startup-gdb.log"
    command = [debugger, "--nx", "--batch", "--quiet"]
    expressions = [
        "set pagination off", "set confirm off", "set print thread-events off",
        "set disable-randomization off", "set auto-load off", "set follow-fork-mode parent",
        "handle SIGPIPE nostop noprint pass", "handle SIGUSR1 nostop noprint pass",
        "handle SIGUSR2 nostop noprint pass", "handle SIGSEGV stop print pass", "run",
        "echo PD2_GDB_FIRST_STOP\\n", "info program",
        "info registers rip rsp rbp rax rbx rcx rdx rsi rdi r8 r9 r10 r11 r12 r13 r14 r15 eflags cs ss ds es fs gs fs_base gs_base",
        "x/20i $pc-24", "bt", "info sharedlibrary", "echo PD2_GDB_CONTINUE_ONCE\\n", "continue",
        "echo PD2_GDB_SECOND_STOP\\n", "info program",
        "info registers rip rsp rbp rax rbx rcx rdx rsi rdi r8 r9 r10 r11 r12 r13 r14 r15 eflags cs ss ds es fs gs fs_base gs_base",
        "x/20i $pc-24", "bt", "info sharedlibrary",
    ]
    for expression in expressions:
        command.extend(("-ex", expression))
    command.extend(("--args", str(wine / "bin/wine"), "C:\\windows\\system32\\cmd.exe",
                    "/c", "echo", "PD2_WINE64_GDB_READY"))
    with diagnostics.open("w") as output:
        process = subprocess.Popen(command, env=environment, cwd=attempt, stdin=subprocess.DEVNULL,
                                   stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=2)
            code = "timeout"
    return {"available": True, "returncode": code, "output": bounded_text(diagnostics, 22000),
            "scope": "First and second debugger stops; a first-chance Wine fault is not proof of a fatal failure"}


def seed_common_dlls(rootfs: Path, prefix: Path, common: dict) -> None:
    # Match ContainerManager.extractContainerPatternFile/copyCommonDlls: the
    # compressed prefix deliberately omits these copies to avoid duplication.
    for arch, destination in (("x86_64-windows", "system32"), ("i386-windows", "syswow64")):
        names = common[destination]
        assert isinstance(names, list) and len(names) <= 1024
        for name in names:
            path = PurePosixPath(name)
            assert not path.is_absolute() and ".." not in path.parts and "\\" not in name, name
            source = rootfs / "opt/wine/lib/wine" / arch / name
            target = prefix / "drive_c/windows" / destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)


def run_probe(work: Path, rootfs: Path, template: bytes, common: dict, executable: Path, donor: bool) -> dict:
    attempt = work / ("donor" if donor else "repaired")
    attempt.mkdir()
    extract_archive(template, attempt)
    prefix = attempt / ".wine"
    seed_common_dlls(rootfs, prefix, common)
    shutil.copy2(executable, prefix / "drive_c/pd2-xinput-probe.exe")
    wine = rootfs / "opt/wine"
    environment = os.environ.copy()
    environment.update({
        "WINEPREFIX": str(prefix),
        "WINEARCH": "win64",
        "WINELOADER": str(wine / "bin/wine"),
        "WINESERVER": str(wine / "bin/wineserver"),
        "WINEDLLPATH": str(wine / "lib/wine"),
        "WINEDLLOVERRIDES": "xinput1_4=b;winemenubuilder.exe=d",
        "WINEDEBUG": "-all,err+all,warn+all,+loaddll",
        "DISPLAY": "",
    })
    # Prevent a developer's shell overrides from selecting another runtime.
    for name in ("WINELOADERNOEXEC", "WINEPRELOADRESERVE", "LD_PRELOAD", "BOX64_PATH"):
        environment.pop(name, None)
    environment.pop("LD_LIBRARY_PATH", None)
    command = [str(wine / "bin/wine"), "C:\\pd2-xinput-probe.exe"]
    if donor:
        command.append("donor")
    peer = Peer()
    peer.thread.start()
    lines: queue.Queue = queue.Queue()
    output: list[str] = []
    stderr_path = attempt / "wine.log"
    process = None
    try:
        with stderr_path.open("w+") as stderr:
            process = subprocess.Popen(command, env=environment, cwd=attempt, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=stderr, text=True, start_new_session=True)
            reader = threading.Thread(target=read_lines, args=(process.stdout, lines), daemon=True)
            reader.start()
            deadline = time.monotonic() + 55
            completed = False
            held_discovery_start = 0
            while time.monotonic() < deadline:
                assert not peer.errors, "peer worker failed: " + "; ".join(peer.errors)
                try:
                    line = lines.get(timeout=0.2)
                except queue.Empty:
                    if process.poll() is not None and not reader.is_alive():
                        break
                    continue
                if line is None:
                    break
                output.append(line)
                if line.startswith("PROBE INITIAL "):
                    peer.fault()
                    process.stdin.write("fault\n")
                    process.stdin.flush()
                elif line.startswith("PROBE FAULT_RECOVERED "):
                    held_discovery_start = peer.discoveries
                    process.stdin.write("hold\n")
                    process.stdin.flush()
                elif line.startswith("PROBE HELD "):
                    assert peer.discoveries - held_discovery_start >= 3, "continuous receives starved discovery heartbeat"
                    peer.pause()
                    process.stdin.write("stale\n")
                    process.stdin.flush()
                elif line.startswith("PROBE STALE_EXPIRED "):
                    peer.resume()
                    process.stdin.write("resume\n")
                    process.stdin.flush()
                elif line.startswith("PROBE PASS ") or line.startswith("PROBE DONOR_STALE_REPRODUCED "):
                    completed = True
                    break
            if not completed:
                code = process.poll()
                failure = {"command": command, "returncode": code,
                           "unix_returncode_hex": hex(code & 0xffffffff) if isinstance(code, int) else None,
                           "signal": signal_name(code),
                           "stdout_reader_alive": reader.is_alive(), "stdout": output,
                           "stderr": bounded_text(stderr_path), "discoveries": peer.discoveries,
                           "states_sent": peer.states, "prefix_processes": prefix_processes(prefix),
                           "environment": {name: environment.get(name) for name in (
                               "WINEPREFIX", "WINELOADER", "WINESERVER", "WINEDLLPATH", "WINEDEBUG",
                               "WINEARCH", "DISPLAY", "LD_LIBRARY_PATH")},
                           "console_preflights": startup_diagnostics(wine, prefix, environment, attempt),
                           "debugger": debugger_diagnostics(wine, environment, attempt)}
                raise AssertionError("PE probe incomplete: " + json.dumps(failure, sort_keys=True))
            assert process.wait(timeout=5) == 0, "PE probe failed: " + "\n".join(output)
            reader.join(timeout=1)
        wine_log = stderr_path.read_text()
        assert any("loaddll:" in line.lower() and "xinput1_4.dll" in line.lower()
                   and "builtin" in line.lower() for line in wine_log.splitlines()), (
                       "missing same-line xinput1_4 builtin module-load proof")
        if not donor:
            assert "PD2_XINPUT_READER" in wine_log and "wine9-xinput-reader-1" in wine_log, "new DLL telemetry absent"
        print("\n".join(output), flush=True)
        return {"donor": donor, "discoveries": peer.discoveries, "states_sent": peer.states,
                "builtin_load": True, "telemetry": "PD2_XINPUT_READER" in wine_log}
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=3)
        # Exact private WINEPREFIX only; leave every other Wine session alone.
        try:
            subprocess.run([str(wine / "bin/wineserver"), "-k2"], env=environment,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
            subprocess.run([str(wine / "bin/wineserver"), "-w"], env=environment,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
        finally:
            peer.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="Use an already verified APK's matching runtime assets")
    parser.add_argument("--cc", help="Optionally rebuild the pinned probe with its exact recorded MinGW toolchain")
    parser.add_argument("--check-fixture", action="store_true", help="Verify the source/toolchain/PE fixture only")
    parser.add_argument("--dll", type=Path, default=ROOT / "app/src/main/assets/pd2/xinput-reader/xinput1_4.dll")
    args = parser.parse_args()
    executable = verify_probe_fixture(args.cc)
    if args.check_fixture:
        return
    assert os.uname().machine == "x86_64", "exact packaged Wine9 qualification requires x86-64 Linux"
    # A failure here is an environment failure, never a skipped/passed Wine test.
    test_socket = socket.socket(socket.AF_UNIX)
    test_socket.close()
    assert ANDROID_SERVER_TMP.is_dir() and os.access(ANDROID_SERVER_TMP, os.W_OK), (
        "Exact Wine9 hardcodes its server-socket parent; create an empty runner-owned directory first: "
        + str(ANDROID_SERVER_TMP))
    rootfs_bytes = asset_bytes("rootfs.tzst", args.apk)
    template = asset_bytes("container_pattern.tzst", args.apk)
    common = json.loads(asset_bytes("common_dlls.json", args.apk))
    with tempfile.TemporaryDirectory(prefix="pd2-xinput-wine9-") as temporary:
        work = Path(temporary)
        runtime = work / "runtime"
        runtime.mkdir()
        extract_archive(rootfs_bytes, runtime, wine_only=True)
        target = runtime / DLL_PATH
        assert digest(target.read_bytes()) == DONOR_SHA, "runtime donor module hash mismatch"
        version = subprocess.check_output([str(runtime / "opt/wine/bin/wine"), "--version"], text=True).strip()
        assert version == "wine-9.2", version
        donor = run_probe(work, runtime, template, common, executable, donor=True)
        replacement = args.dll.read_bytes()
        assert replacement[64:80] == b"Wine builtin DLL", "replacement builtin marker missing"
        target.write_bytes(replacement)
        repaired = run_probe(work, runtime, template, common, executable, donor=False)
        print(json.dumps({"runtime": version, "donor_failure_reproduced": donor,
                          "repair_passed": repaired}, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
