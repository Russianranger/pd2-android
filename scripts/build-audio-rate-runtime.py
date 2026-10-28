#!/usr/bin/env python3
"""Reproduce/verify the optional ARM64 ALSA Eco PCM against the pinned runtime.

--check is offline. --rebuild and --test fetch SHA-pinned Ubuntu toolchain
packages into an isolated work directory; no system package installation occurs.
--test runs the shipped ARM64 module, exact runtime ALSA/libc and original PCM
under QEMU with deterministic mock AServer transport. It does not test Android
audio timing or sound quality.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "runtime/audio-rate"
ASSETS = ROOT / "app/src/main/assets/pd2/audio"
MODULE = "libasound_module_pcm_pd2_eco.so"
RUNTIME_FILES = {
    "usr/lib/libasound.so.2.0.0": "933267b185e5762b483ed4f6383e9c082bee3e620011ded93a6b5e19de1cb206",
    "usr/lib/libc.so.6": "3e743395fdd05df3c3af0705c4c6dfefde3b9c6d053a7db0e9beb3efd437dce0",
    "usr/lib/libm.so.6": "53b4fcedc45ff8c2736f232243f903df0902611e8dd6322ad0c406ec94891e2f",
    "usr/lib/ld-linux-aarch64.so.1": "4126690b77527312689e7f1f956e912b89c2724ae09213b3fb6deab19031ad15",
    "usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so": "d2e810f84bcad6fc5dc40e2f5082c668cb2850fb9f678b131ea048af5f992adb",
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(path: Path, expected: str, size: int | None = None) -> None:
    if not path.is_file() or (size is not None and path.stat().st_size != size) or digest(path) != expected:
        raise ValueError("Pinned bytes do not match: " + str(path))


def inspect_elf(path: Path) -> None:
    b = path.read_bytes()
    if b[:6] != b"\x7fELF\x02\x01" or int.from_bytes(b[16:18], "little") != 3 or int.from_bytes(b[18:20], "little") != 183:
        raise ValueError("Eco PCM must be an ARM64 little-endian shared object")
    output = subprocess.check_output(["readelf", "-dWsV", "--wide", str(path)], text=True)
    needed = re.findall(r"\(NEEDED\).*\[(.*?)\]", output)
    if sorted(needed) != ["libasound.so.2", "libc.so.6"]:
        raise ValueError("Unexpected Eco PCM dependencies: " + repr(needed))
    if "(RPATH)" in output or "(RUNPATH)" in output:
        raise ValueError("Eco PCM must not embed a build-machine runtime path")
    for symbol in ("_snd_pcm_pd2_eco_open", "__snd_pcm_pd2_eco_open_dlsym_pcm_001"):
        if not re.search(r"GLOBAL\s+DEFAULT\s+\d+\s+" + re.escape(symbol) + r"\b", output):
            raise ValueError("Missing Eco PCM export: " + symbol)
    versions = set(re.findall(r"\b(?:GLIBC|ALSA)_[0-9A-Za-z_.]+", output))
    if not versions <= {"GLIBC_2.17", "ALSA_0.9", "ALSA_0.9.0rc4"}:
        raise ValueError("Unexpected Eco PCM symbol versions: " + repr(versions))


def check() -> None:
    manifest = json.loads((ASSETS / "manifest.json").read_text())
    verify(ASSETS / MODULE, manifest["sha256"], manifest["bytes"])
    verify(ASSETS / "COPYING.LIB", manifest["licenseSha256"])
    for name, expected in manifest["sourceSha256"].items():
        if name.startswith("/") or ".." in Path(name).parts:
            raise ValueError("Unsafe source manifest path")
        verify(ROOT / name, expected)
    inspect_elf(ASSETS / MODULE)
    installer = (ROOT / "app/src/main/java/com/winlator/pd2/Pd2AudioRuntime.java").read_text()
    if ('MODULE_SHA256 = "' + manifest["sha256"] + '"') not in installer or ('MODULE_BYTES = ' + str(manifest["bytes"]) + ';') not in installer:
        raise ValueError("Java installer pins differ from the verified Eco PCM")
    print("Verified Eco PCM asset, source/license hashes, ARM64 ABI and dependency versions")


def runtime(work: Path) -> Path:
    import zstandard
    target = work / "runtime"
    inventory = json.loads((ROOT / "docs/RUNTIME-DEPENDENCIES.json").read_text())["files"]
    for name in ("rootfs.tzst", "rootfs_patches.tzst"):
        relative = "app/src/main/assets/" + name
        archive = ROOT / relative
        verify(archive, inventory[relative]["installed_sha256"])
        with archive.open("rb") as source, zstandard.ZstdDecompressor().stream_reader(source) as reader:
            with tarfile.open(fileobj=reader, mode="r|") as tar:
                for member in tar:
                    path = member.name.removeprefix("./")
                    if path not in RUNTIME_FILES:
                        continue
                    if not member.isfile():
                        raise ValueError("Expected regular pinned runtime file: " + path)
                    data = tar.extractfile(member).read()
                    if hashlib.sha256(data).hexdigest() != RUNTIME_FILES[path]:
                        raise ValueError("Runtime audio ABI input changed: " + path)
                    out = target / path
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_bytes(data)
                    out.chmod(0o755)
    for name, expected in RUNTIME_FILES.items():
        verify(target / name, expected)
    lib = target / "usr/lib"
    alias = lib / "libasound.so.2"
    if alias.is_symlink():
        if os.readlink(alias) != "libasound.so.2.0.0":
            raise ValueError("Unexpected isolated ALSA alias")
    elif alias.exists():
        raise ValueError("Unexpected isolated ALSA alias")
    else:
        alias.symlink_to("libasound.so.2.0.0")
    return lib


def toolchain(work: Path) -> Path:
    packages = json.loads((SOURCE / "TOOLCHAIN.json").read_text())["packages"]
    cache = work / "packages"
    cache.mkdir(parents=True, exist_ok=True)
    target = work / "toolchain"
    for package in packages:
        path = cache / package["filename"]
        if not path.exists():
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temporary:
                tmp = Path(temporary.name)
                try:
                    with urllib.request.urlopen(package["url"], timeout=60) as response:
                        shutil.copyfileobj(response, temporary)
                    temporary.flush()
                    verify(tmp, package["sha256"], package["bytes"])
                    os.replace(tmp, path)
                finally:
                    tmp.unlink(missing_ok=True)
        verify(path, package["sha256"], package["bytes"])
        subprocess.run(["dpkg-deb", "-x", str(path), str(target)], check=True)
    return target


def compiler(tc: Path) -> tuple[list[str], dict[str, str]]:
    env = os.environ.copy()
    env["LC_ALL"] = "C"
    env["LD_LIBRARY_PATH"] = str(tc / "usr/lib/x86_64-linux-gnu")
    flags = [str(tc / "usr/bin/aarch64-linux-gnu-gcc-13"), "-B" + str(tc / "usr/bin") + "/",
             "-std=gnu11", "-O2", "-Wall", "-Wextra", "-Wno-unused-parameter", "-U_FORTIFY_SOURCE",
             "-fno-stack-protector", "-isystem", str(tc / "usr/include"), "-nostdlib", "-Wl,--build-id=none"]
    return flags, env


def build(work: Path, tc: Path, lib: Path) -> Path:
    output = work / MODULE
    flags, env = compiler(tc)
    subprocess.run(flags + ["-fPIC", "-DPIC", "-shared", "-o", str(output),
                   str(SOURCE / "module_pcm_pd2_eco.c"), str(lib / "libasound.so.2"), str(lib / "libc.so.6"),
                   "-Wl,--no-undefined", "-Wl,-soname," + MODULE], env=env, check=True)
    inspect_elf(output)
    return output


def tests(work: Path, tc: Path, lib: Path) -> None:
    rebuilt = build(work, tc, lib)
    if rebuilt.read_bytes() != (ASSETS / MODULE).read_bytes():
        raise ValueError("Rebuilt Eco PCM differs from the shipped asset")
    flags, env = compiler(tc)
    probe = work / "test_pcm"
    startup = tc / "usr/aarch64-linux-gnu/lib"
    subprocess.run(flags + ["-no-pie", "-Wl,--export-dynamic", "-o", str(probe), str(startup / "crt1.o"),
                   str(startup / "crti.o"), str(SOURCE / "test_pcm.c"), str(lib / "libasound.so.2"),
                   str(lib / "libc.so.6"), str(startup / "crtn.o"), "-Wl,--no-undefined",
                   "-Wl,-rpath-link," + str(lib)], env=env, check=True)
    for name, module, minimum, maximum in [
        ("android_aserver", lib / "alsa-lib/libasound_module_pcm_android_aserver.so", 8000, 48000),
        ("pd2_eco", ASSETS / MODULE, 22050, 22050),
    ]:
        config = work / (name + ".conf")
        config.write_text('pcm_type.' + name + ' { lib "' + str(module) + '" }\npcm.!default { type ' + name + ' }\n')
        for shared_memory in (0, 1):
            for data_type in (1, 3):  # S16_LE and Wine's floating-point mix format.
                run_env = os.environ.copy()
                run_env["ALSA_CONFIG_PATH"] = str(config)
                # A user's stale unrelated rate variable cannot affect this fixed Eco PCM.
                run_env["D2_ALSA_RATE"] = "48000"
                subprocess.run([str(tc / "usr/bin/qemu-aarch64-static"), str(lib / "ld-linux-aarch64.so.1"),
                                "--library-path", str(lib), str(probe), str(minimum), str(maximum), str(shared_memory), str(data_type)],
                               env=run_env, check=True, timeout=30)
    print("PASS eight actual ARM64 ALSA/module scenarios; transport mocked, Android audio unmeasured")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--rebuild", action="store_true")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--work-dir", type=Path, default=ROOT.parent / "qualification/audio-rate")
    args = parser.parse_args()
    if not any((args.check, args.rebuild, args.test)):
        parser.error("choose --check, --rebuild or --test")
    if args.rebuild or args.test:
        work = args.work_dir.resolve()
        work.mkdir(parents=True, exist_ok=True)
        tc, lib = toolchain(work), runtime(work)
        if args.rebuild:
            output = build(work, tc, lib)
            ASSETS.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(output, ASSETS / MODULE)
            shutil.copyfile(SOURCE / "COPYING.LIB", ASSETS / "COPYING.LIB")
            names = ["runtime/audio-rate/" + str(p.relative_to(SOURCE)) for p in sorted(SOURCE.rglob("*")) if p.is_file()]
            names += ["scripts/build-audio-rate-runtime.py"]
            manifest = {"revision": "alsa-eco-1", "bytes": output.stat().st_size, "sha256": digest(output),
                        "sampleRate": 22050, "architecture": "aarch64-linux-gnu", "license": "LGPL-2.1",
                        "licenseSha256": digest(SOURCE / "COPYING.LIB"), "sourceSha256": {n: digest(ROOT / n) for n in names}}
            (ASSETS / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
            print("Rebuilt Eco PCM", manifest["bytes"], manifest["sha256"])
        if args.test:
            tests(work, tc, lib)
    check()


if __name__ == "__main__":
    main()
