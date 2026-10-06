# Wine 9 Android HID producer

This directory contains the complete source and build inputs for the small
`wine9-hid-2` controller runtime. The app installs its `winebus.so` before starting
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

## Opt-in fresh Windows identity experiment

Ordinary startup retains the accepted slot-zero device, serial
`pd2-xbox-slot0`, UID zero, descriptor, report values and unchanged-report
suppression. Only the explicit recovery action changes the Wine device UID.
It first advertises the existing HID/legacy connection absent, waits for
matching removal-queued and Unix stop-callback observations, then presents
the higher UID after the bounded gap. The receiver refuses a higher UID while
the old pad is still present or its exact stop callback has not been observed,
and queues the removal before a new creation.
Repeated discovery of the current UID cannot create another pad.

Wine 9.2's [pinned PE Winebus implementation](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/winebus.sys/main.c)
formats the instance ID from version, serial, UID, index and gamepad flag.
Changing UID therefore changes the actual Windows device instance rather than
only its Raw Input handle. [Microsoft's instance-ID documentation](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/instance-ids)
describes this bus identity's role in Plug and Play. The experiment tests stale
device identity; it does not establish that PD2 caches that identity or that
Save/Quit recovery works.

Dedicated HID discovery and state frames carry this extension in their unused
suffix. Other modern/legacy controller protocols remain unchanged.

| Offset | Field |
| --- | --- |
| 241–244 | Little-endian magic `0x32524450` (`PDR2`) |
| 245 | Version 1 |
| 246–249 | Little-endian device UID, 0 to `INT32_MAX` |
| 250–253 | Little-endian session token, 1 to `INT32_MAX` |
| 254–255 | Reserved, zero |

An entirely zero suffix remains compatible with the preceding producer until
the first tagged UID-zero discovery adopts a session token. Thereafter the
backend rejects old tokens, lower UIDs and untagged frames; stale packets cannot
replay controls into the new device. A new Wine bus starts at UID/token zero.

Tagged sessions receive fixed **64-byte** observations on `7950 → 7947`, code
`0x50`. They contain no button identities or axis values. Heartbeats occur every
two seconds, with additional observations at discovery, queued creation/removal
and Unix device start/stop callbacks.

| Offset | Field |
| --- | --- |
| 0–3 | Code `0x50`, version 1, connected/started flag bits 0/1, exact last-removal stop-observed flag bit 2, stage |
| 4–7 | `PDR2` magic |
| 8–19 | Session token, device UID, Linux backend PID (three unsigned 32-bit values) |
| 20–47 | Saturating 32-bit creation/removal/start/stop/state-received/report-queued/rejected counters |
| 48–55 | Unsigned 64-bit socket inode; zero if unavailable |
| 56–63 | Unsigned 64-bit monotonic timestamp in milliseconds |

Stop counts include callbacks for the currently owned or exact retiring object.
Earlier ordinary reconnects can share the same UID; their delayed callbacks
cannot confirm a later object's removal. Flag bit 2 confirms the exact last
queued removal while no current pad is present, resets on each new removal or
new identity/creation, and survives heartbeat/duplicate absence. Both Java and
native require this proof before advancing UID; a historical stop count alone
cannot authorize the change.

Stages are 0 heartbeat, 1 discovery, 2 creation queued, 3 removal queued,
4 Unix device-start observed and 5 Unix device-stop observed. A queued event is
not a completed PnP operation. Start only proves Wine called the Unix start
callback; PE descriptor parsing and HID enumeration occur afterward. Neither
an observation nor a successful UDP send proves PD2 accepted input. The saved
device report and physical recovery result remain the test authority.

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
`ntdll.so` and `libc.so.6` dependencies. The revised module requires at most
GLIBC 2.33; the packaged runtime supplies 2.39.

Three host tests check manifest rejection and run the production backend with
real UDP packets, Wine's descriptor/report helpers, and AddressSanitizer plus
UndefinedBehaviorSanitizer. They cover malformed packets, the independent HID
descriptor bit count, signed axes/triggers/buttons, neutral release, deduplication,
replug, heartbeat removal, undelivered creation cleanup, and stopping a blocked
wait thread. Additional checks cover fresh UID ordering/idempotence, stale and
cross-session rejection, delayed old-device callbacks before/after a same-UID
current removal, loss-tolerant exact-retirement proof, observation privacy and
session/socket replacement. Alignment checking is excluded for Wine's intentional unaligned
x86 report writes. LeakSanitizer cannot inspect `/proc` in the development
runner, so leak instrumentation is disabled; refcount ownership was reviewed
separately. These host checks do not prove Android/Wine/PD2 integration.

The revised module links against the accepted relocated Wine 9 `ntdll.so`
(SHA-256 `06fc058c06b6540c43e3be3f33f961cb97fe3e86e4f6479483e8328d942a25a1`).
Socket inode capture uses `fstat`, raising its maximum imported GLIBC version
to 2.33; the accepted runtime supplies 2.39. The manifest records the exact
artifact, link-library and build-source hashes. No PE driver, XInput DLL or
imported game file is rebuilt or replaced.

The same accepted `ntdll.so` already imports `fstat@GLIBC_2.33` (and `stat` /
`lstat` at that version). Box64 0.4.4's
[libc wrapper table](https://github.com/ptitSeb/box64/blob/v0.4.4/src/wrapped/wrappedlibc_private.h)
registers `fstat`, and its
[implementation](https://github.com/ptitSeb/box64/blob/v0.4.4/src/wrapped/wrappedlibc.c)
converts the host `stat` structure with `UnalignStat64`. This checks the source
and symbol compatibility boundary, not physical Android execution.
