# Wine 9 Android HID producer

This directory contains the complete source and build inputs for the small
`wine9-hid-1` controller runtime. The app installs its `winebus.so` before starting
Wine, preserving the original module for rollback. The Wine 9.2 PE driver,
17-entry Unix-call interface, XInput DLLs, graphics runtime and existing prefix
remain in place.

The module replaces the three unused SDL backend entries with an Android
loopback producer. It exposes one real gamepad through Wine's existing Winebus,
HIDclass and PlugPlay path. It does not inject window messages or replace game
DLLs. Device arrival, removal and input are processed by the retained Wine
drivers. The intended result is a queryable Raw Input device as well as the
existing XInput state path; PD2 activation on the Android device still requires
an end-to-end test.

## Protocol

Native port `127.0.0.1:7950` exchanges packets with the app on port `7947`.
The legacy Wine 9 XInput connection on port `7949` is separate.

| Direction | Packet | Contents |
| --- | --- | --- |
| Native to Java | 64 bytes, code 8 | Discover/subscribe, repeated every two seconds |
| Java to native | 256 bytes, code 8 | Four 60-byte device records; only slot zero is exposed |
| Java to native | 256 bytes, code 9 | Slot zero; ten button bits, eight-way hat, four signed sticks, two nonnegative triggers |
| Native to Java | 64 bytes, code 10 | Release subscription on shutdown |

The fixed device identity is Microsoft `045E:02A1`, matching the retained Wine 9
XInput capabilities. Its report descriptor has six axes, one hat and ten
buttons. Input reports are 28 bytes including report ID 1. Rumble is not exposed.
The receiver checks the loopback sender port and exact packet size, rejects
invalid slot/hat/trigger metadata, removes the device after six seconds without
a valid reply, and suppresses unchanged reports. Java supplies neutral state
while the mouse/keyboard overlay or a modal owns input.

## Sources and license

The backend and protocol are under LGPL-2.1-or-later. Report construction follows
[Winlator's Wine 10 producer](https://github.com/brunodev85/wine-10.10-custom/blob/2b9afc38d9531d384f038bbaaf3f637e515fb297/dlls/winebus.sys/bus_winlator.c).
The backend is adapted to Wine 9's existing interface and uses a separate port.
It retains none of the third-party PD2 hook DLL code.

`vendor/wine9` contains 55 byte-identical files, including `COPYING.LIB`, from
[`brunodev85/wine-9.2-custom` at `a4ef2bf8963fe4bfab390eebf73336364ce209bc`](https://github.com/brunodev85/wine-9.2-custom/tree/a4ef2bf8963fe4bfab390eebf73336364ce209bc).
`vendor/wine9/SOURCES.json` records each upstream path, size and SHA-256. The
build script generates the three-entry substitution in a temporary copy of
`unixlib.c`; vendored files are unchanged.

## Build and checks

The committed artifact was built on x86-64 Linux with GCC 13.3.0 and GNU
binutils. Rebuild using the exact packaged Wine 9 `x86_64-unix/ntdll.so`:

```sh
python scripts/build-controller-runtime.py --ntdll /path/to/opt/wine/lib/wine/x86_64-unix/ntdll.so
python scripts/build-controller-runtime.py --check
python -m unittest discover -s tests -p test_controller_runtime.py -v
```

The manifest records the link-library hash, compiler version, build-source
hashes, upstream revision and original module hash. Compile-time assertions
check every retained ABI size and offset used at the boundary. Artifact checks
require ELF64 AMD64, exactly 17 Unix entries, no host search paths, and only
`ntdll.so` and `libc.so.6` dependencies. The committed module requires at most
GLIBC 2.17; the packaged runtime supplies 2.39.

Three host tests check manifest rejection and run the production backend with
real UDP packets, Wine's descriptor/report helpers, and AddressSanitizer plus
UndefinedBehaviorSanitizer. They cover malformed packets, the independent HID
descriptor bit count, signed axes/triggers/buttons, neutral release, deduplication,
replug, heartbeat removal, undelivered creation cleanup, and stopping a blocked
wait thread. Alignment checking is excluded for Wine's intentional unaligned
x86 report writes. LeakSanitizer cannot inspect `/proc` in the development
runner, so leak instrumentation is disabled; refcount ownership was reviewed
separately. These host checks do not prove Android/Wine/PD2 integration.
