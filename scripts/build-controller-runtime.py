#!/usr/bin/env python3
"""Build the Wine9 Unix HID producer with pinned Wine headers, using host GCC."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "runtime/controller"
VENDOR = SOURCE / "vendor/wine9"
OUTPUT = ROOT / "app/src/main/assets/pd2/controller"
BASE_SHA = "4ca5b1dd5f2d56ae9ca3090f356b700f4e5282b6915a195719d0a1ab66128117"
REVISION = "wine9-hid-2"
ASSET = "pd2/controller/winebus.so"
VENDOR_COMMIT = "a4ef2bf8963fe4bfab390eebf73336364ce209bc"
BUILD_SOURCES = [SOURCE / "pd2_bus.c", SOURCE / "pd2_protocol.h", Path(__file__)]
ABI = {"entries": 17, "wchar_bytes": 2, "device_desc_bytes": 1588,
       "device_desc_manufacturer_offset": 28, "bus_event_bytes": 1608,
       "input_report_buffer_offset": 18, "device_create_params_bytes": 1600,
       "device_create_device_offset": 1592}
CONFIG = """#ifndef PD2_WINE9_CONFIG_H
#define PD2_WINE9_CONFIG_H
#define HAVE_STDINT_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_SOCKET_H 1
#define HAVE_PTHREAD_H 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_NANOSLEEP 1
#define HAVE_PTHREAD_MUTEX 1
#define HAVE_PTHREAD_MUTEXATTR_SETKIND_NP 1
#endif
"""

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def validate_vendor() -> dict:
    provenance = json.loads((VENDOR / "SOURCES.json").read_text())
    if provenance.get("commit") != VENDOR_COMMIT:
        raise RuntimeError("Unexpected vendored Wine9 source revision")
    entries = provenance.get("files", {})
    if isinstance(entries, list):
        entries = {item["path"]: item for item in entries}
    for name, metadata in entries.items():
        expected = metadata["sha256"] if isinstance(metadata, dict) else metadata
        if digest(VENDOR / name) != expected:
            raise RuntimeError(f"Vendored Wine source changed: {name}")
    return provenance

def generated_glue(build: Path) -> Path:
    upstream = (VENDOR / "dlls/winebus.sys/unixlib.c").read_text()
    old = "    sdl_bus_init,\n    sdl_bus_wait,\n    sdl_bus_stop,"
    if upstream.count(old) != 1:
        raise RuntimeError("Pinned Wine9 Unix ABI table did not match")
    declarations = "NTSTATUS pd2_bus_init(void *);\nNTSTATUS pd2_bus_wait(void *);\nNTSTATUS pd2_bus_stop(void *);\n"
    upstream = upstream.replace("const unixlib_entry_t __wine_unix_call_funcs[] =", declarations + "const unixlib_entry_t __wine_unix_call_funcs[] =")
    upstream = upstream.replace(old, "    pd2_bus_init,\n    pd2_bus_wait,\n    pd2_bus_stop,")
    upstream += """
C_ASSERT(ARRAYSIZE(__wine_unix_call_funcs) == 17);
C_ASSERT(sizeof(WCHAR) == 2);
C_ASSERT(sizeof(struct device_desc) == 1588);
C_ASSERT(offsetof(struct device_desc, manufacturer) == 28);
C_ASSERT(sizeof(struct bus_event) == 1608);
C_ASSERT(offsetof(struct bus_event, input_report.buffer) == 18);
C_ASSERT(sizeof(struct device_create_params) == 1600);
C_ASSERT(offsetof(struct device_create_params, device) == 1592);
"""
    path = build / "unixlib-pd2.c"
    path.write_text(upstream)
    return path

def compile_flags(build: Path) -> list[str]:
    return ["-std=gnu11", "-Os", "-fPIC", "-fshort-wchar", "-fvisibility=hidden",
            "-D__WINESRC__", "-DWINE_UNIX_LIB", "-D_WIN64", "-D_REENTRANT",
            f"-ffile-prefix-map={ROOT}=/pd2-android", f"-ffile-prefix-map={build}=/pd2-controller-build",
            "-include", str(build / "config.h"), "-I", str(build), "-I", str(SOURCE),
            "-I", str(VENDOR / "include"), "-I", str(VENDOR / "dlls/winebus.sys")]

def run(command: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(command, check=True, **kwargs)

def validate_elf(path: Path) -> dict:
    data = path.read_bytes()
    if data[:6] != b"\x7fELF\x02\x01" or int.from_bytes(data[18:20], "little") != 62:
        raise RuntimeError("Expected ELF64 little-endian AMD64 controller runtime")
    dynamic = run(["readelf", "-d", str(path)], capture_output=True, text=True).stdout
    needed = re.findall(r"\(NEEDED\).*\[([^]]+)\]", dynamic)
    if set(needed) - {"ntdll.so", "libc.so.6"} or "ntdll.so" not in needed:
        raise RuntimeError(f"Unexpected runtime dependencies: {needed}")
    if "RPATH" in dynamic or "RUNPATH" in dynamic:
        raise RuntimeError("Controller artifact must not embed host runtime paths")
    versions = run(["readelf", "--version-info", str(path)], capture_output=True, text=True).stdout
    glibc = sorted(set(re.findall(r"GLIBC_([0-9.]+)", versions)), key=lambda value: tuple(map(int, value.split("."))))
    if glibc and tuple(map(int, glibc[-1].split("."))) > (2, 39):
        raise RuntimeError(f"Requires newer libc than packaged2.39: {glibc[-1]}")
    symbols = run(["nm", "-D", "-S", "--defined-only", str(path)], capture_output=True, text=True).stdout
    exports = [line.split()[-1] for line in symbols.splitlines() if line.strip()]
    if exports != ["__wine_unix_call_funcs"]:
        raise RuntimeError(f"Unexpected public ABI: {exports}")
    table = symbols.splitlines()[0].split()
    if int(table[1], 16) != 17 * 8:
        raise RuntimeError("Wrong Wine9 Unix table byte size")
    return {"machine": "ELF64-AMD64", "needed": needed, "glibc_max": glibc[-1],
            "exports": exports, "wine9_abi": ABI}

def validate_manifest(manifest: dict, artifact: Path) -> None:
    expected = {"revision": REVISION, "asset": ASSET, "base_sha256": BASE_SHA,
                "vendor_commit": VENDOR_COMMIT, "license": "LGPL-2.1-or-later"}
    for name, value in expected.items():
        if manifest.get(name) != value:
            raise RuntimeError(f"Controller manifest {name} mismatch")
    source_hashes = {path.name: digest(path) for path in BUILD_SOURCES}
    if manifest.get("source_sha256") != source_hashes:
        raise RuntimeError("Controller artifact is stale relative to its build sources")
    if digest(artifact) != manifest.get("sha256") or artifact.stat().st_size != manifest.get("bytes"):
        raise RuntimeError("Controller artifact hash/size mismatch")
    if manifest.get("validation") != validate_elf(artifact):
        raise RuntimeError("Controller artifact ELF/Wine9 ABI metadata mismatch")

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ntdll", type=Path, help="Exact packaged Wine9 x86_64-unix/ntdll.so")
    parser.add_argument("--cc", default="gcc")
    parser.add_argument("--check", action="store_true", help="Verify committed artifact and source hashes without compiling")
    args = parser.parse_args()
    provenance = validate_vendor()
    if args.check:
        manifest = json.loads((OUTPUT / "manifest.json").read_text())
        artifact = OUTPUT / "winebus.so"
        validate_manifest(manifest, artifact)
        print("Controller source, artifact and Wine9 ABI checks passed.")
        return
    if not args.ntdll or not args.ntdll.is_file():
        parser.error("--ntdll must point to the exact packaged Wine9 ntdll.so")
    with tempfile.TemporaryDirectory(prefix="pd2-controller-build-") as temporary:
        build = Path(temporary)
        (build / "config.h").write_text(CONFIG)
        glue = generated_glue(build)
        flags = compile_flags(build)
        sources = [SOURCE / "pd2_bus.c", VENDOR / "dlls/winebus.sys/hid.c", glue]
        sources += [VENDOR / f"dlls/winebus.sys/{name}.c" for name in ["bus_sdl", "bus_udev", "bus_iohid"]]
        objects = []
        for index, source in enumerate(sources):
            output = build / f"module{index}.o"
            run([args.cc, *flags, "-c", str(source), "-o", str(output)])
            objects.append(output)
        artifact = build / "winebus.so"
        run([args.cc, "-shared", "-Wl,--no-undefined", "-Wl,-Bsymbolic", "-Wl,--build-id=none",
             "-Wl,-soname,winebus.so", "-o", str(artifact), *map(str, objects),
             "-L", str(args.ntdll.parent), "-l:ntdll.so", "-pthread"])
        run(["strip", "--strip-unneeded", str(artifact)])
        verification = validate_elf(artifact)
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / "winebus.so").write_bytes(artifact.read_bytes())
        (OUTPUT / "winebus.so").chmod(0o755)
        manifest = {"revision": REVISION, "asset": ASSET,
                    "sha256": digest(artifact), "bytes": artifact.stat().st_size,
                    "base_sha256": BASE_SHA, "license": "LGPL-2.1-or-later",
                    "vendor_source": provenance.get("repository", "brunodev85/wine-9.2-custom"),
                    "vendor_commit": VENDOR_COMMIT,
                    "producer_reference": "brunodev85/wine-10.10-custom@2b9afc38d9531d384f038bbaaf3f637e515fb297:dlls/winebus.sys/bus_winlator.c",
                    "source_sha256": {path.name: digest(path) for path in BUILD_SOURCES},
                    "ntdll_link_sha256": digest(args.ntdll), "compiler": run([args.cc, "-dumpfullversion"], capture_output=True, text=True).stdout.strip(),
                    "validation": verification}
        (OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        print(json.dumps({"artifact": str(OUTPUT / "winebus.so"), "sha256": manifest["sha256"], "bytes": manifest["bytes"], "validation": verification}, indent=2))

if __name__ == "__main__":
    main()
