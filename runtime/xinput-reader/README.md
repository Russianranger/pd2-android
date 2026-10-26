# Opt-in Wine 9 XInput reader repair

Revision `wine9-xinput-reader-1` replaces only the matching **32-bit**
`opt/wine/lib/wine/i386-windows/xinput1_4.dll`. It is a new source-built PE DLL,
not an opcode-only binary backport. It does not replace Wine, Box64, the HID
producer/driver, the prefix, imported game files, saves or controller mappings.
Physical Save/Quit recovery remains a device qualification gate.

## Provenance and license

`vendor/main.c` is byte-exact
`brunodev85/wine-9.2-custom@a4ef2bf8963fe4bfab390eebf73336364ce209bc`,
`dlls/xinput1_3/main.c`, Git blob `01b229084ef65a4fd1bae832982626a0ecdb6183`.
The donor's `xinput1_4/Makefile.in` uses that same source through `PARENTSRC`.
`vendor/xinput1_4.spec` is byte-exact blob
`22c511237ddbaf9099a9371c77cb466db5aa3e76` at the same commit. All donor notices
remain. The donor, lifecycle changes and qualification harness are
LGPL-2.1-or-later; `COPYING.LIB` supplies the license. The builder and all source
needed to reproduce the module are kept beside the launcher source.

The builder verifies the source hash/blob, applies a bounded source transformation,
and inserts `reader_lifecycle.h`. It retains the original controller-state decoder
without changing a line: 64-byte legacy UDP, digital trigger bits, button/hat
mapping, Y inversion, Xbox capabilities identity, and minimum-index behavior.
All ten donor export names and ordinals, including 100 and 108, are verified.
The donor's unused AudioDeviceIds stub is exposed with the same stdcall ABI and
returns `ERROR_NOT_SUPPORTED`. No raw controller values are exported in telemetry.

## Changed behavior

The donor initializes its reader with `INIT_ONCE`; a fatal receive error exits
that reader while leaving `connected` and cached controls intact. This module:

- Keeps one socket-owning worker; an observed socket failure first clears the
  cached connection/state, then closes and reopens its own socket with 250 ms
  retry backoff. Interrupted receives retry, malformed/foreign packets are not
  accepted as controls, and startup failures can retry.
- Requests discovery every two seconds, including under continuous receive
  traffic. Java replies with the current state even when a physical stick is
  held steady. Six seconds without an accepted state makes GetState return
  disconnected; a new valid discovery/state pair restores availability.
- Copies connected/fresh state atomically under the existing controller lock.
  All initial callers share one two-second startup deadline, signaled only by
  valid state or explicit absence; failed startup does not stall every later API.
- Pins its own module before creating the worker. This deliberately keeps its
  code loaded for the process lifetime, preventing `FreeLibrary` from unloading
  live reader code. Pinning occurs outside the controller lock. Shutdown does
  not wait under DllMain's loader lock or close a socket from another thread.
- Emits `PD2_XINPUT_READER` aggregate OutputDebugStringA telemetry at most once
  per two seconds: revision/PID, reader/socket/retry/receive/state counters,
  API success/disconnected/changed-return counts, packet number and age. Existing
  Wine `warn+all` captures it. It proves module use and API freshness categories,
  not PD2 gameplay acceptance. This MinGW module has no Wine-generated relay
  descriptor; the old scalar relay trace is not a substitute for this telemetry.

## Build and deployment

A MinGW i686 GCC Win32 toolchain is required. The qualified local build used
Debian bookworm GCC 12.2.0 and binutils 2.40, with static libgcc. For example:

```sh
MINGW_CC=i686-w64-mingw32-gcc-win32 python3 scripts/build-xinput-reader-runtime.py
python3 scripts/build-xinput-reader-runtime.py --check
python3 tests/test_xinput_reader_runtime.py
```

`--output DIR` stages artifacts elsewhere. The final DLL uses a zero PE timestamp,
a Wine builtin marker at DOS offset 0x40, a corrected PE checksum, and only
kernel32/msvcrt/ws2_32 dependencies. The exact DLL digest, source hashes and
validated export/import metadata live in `manifest.json`. A different compiler
may produce different bytes and must not silently replace that exact pin.

The Android installer must require the exact original **49,152-byte** DLL,
SHA-256 `c8f692e7fe22fb922886026d97bf39549c8eac12629fee3e06f49078b34a60fa`,
verify and retain its backup, stage/fsync/verify/atomically replace the target,
and refuse unknown bytes. Apply only after exact-prefix shutdown succeeds;
select Wine's builtin `xinput1_4` for that launch. Off plus clean Stop/Play restores
the verified original. Installation must not mutate game or prefix files.

The host harness executes the actual production lifecycle and unchanged decoder
with deterministic Win32 transport/scheduler outcomes, including failure/recovery,
shared startup readiness/deadline, one concurrent reader, socket ownership,
shutdown races, fresh-held input and malformed packets. ASan/UBSan run with
alignment checks excluded only because the unchanged donor x86 codec deliberately
uses unaligned accesses. The separate real-Wine qualification checks the marked
PE's loader/ABI/UDP behavior. Neither replaces the physical Thor gameplay test.
