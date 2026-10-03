# 0.1.4 title startup accepted; native controller not detected

Summary of the device support bundle and user report. No game binaries, saves,
full logs, or private installation paths are included.

Source ZIP SHA-256: `a7e295c4ea976a25073996568b3c84b43f5fc1c6c943369f51d2b6d9f34ca768`.

## Accepted launch

| Item | Recorded result |
| --- | --- |
| APK/device | 0.1.4, AYN Thor, Android 13 |
| Runtime | `wine-9.2-custom`, rootfs 24 |
| Runtime and prefix revision | `wine-9.2-pd2-1` |
| Graphics/CPU | Turnip/Zink, Stability, Interpreter disabled |
| Arguments | `-3dfx -dxnocompatmodefix`, without `-w` |
| User observation | PD2 reaches its title screen; controller is not detected |
| Fog trace | Export 10019 now returns 1 after its call, passing the earlier failing interval |

The Wine 9.2 baseline passed client appearance on this device. That observation
does not isolate the exact Wine 10/Fog failure mechanism or qualify gameplay,
audio, saves, online play, or both native sticks. Preserve the accepted runtime,
prefix, and imported files rather than repeating preparation/import.

## Controller evidence boundary

The trace loads Wine's built-in `XINPUT1_4.dll` before `ProjectDiablo.dll` and
contains a thread named `wine_xinput_controller_read`. That confirms activity
in the Wine XInput path, not that Java discovered the Thor gamepad,
completed a device response, or that PD2 accepted the controller. This bundle
does not contain Android-device/bridge diagnostics sufficient to distinguish
those stages.

Inspection of the donor Wine 9.2 i386 XInput DLL confirms its UDP bridge and
`XInputGetCapabilitiesEx` export. A missing-capability claim is unsupported.
Controller initialization/filtering and PD2's activation behavior remain under
investigation; no device-qualified controller correction is established yet.

The [PD2 team's controller announcement](https://www.reddit.com/r/ProjectDiablo2/comments/1k8p6if/project_diablo_2_season_11_dev_stream_3_recap/)
describes activating controller input by pressing a controller button and
returning to mouse/keyboard with mouse input. It describes twin-stick gameplay;
title/menu controller behavior is not established by that announcement. The
next test should check an offline character, press a controller button after
selecting Native, and avoid mouse input while checking movement/aiming.

## 0.1.5 source correction selected: device result pending

The previous Java queue gated controller responses behind runtime-helper INIT,
and discovery rejected all `uinput` names before checking controller capability.
The new socket-ready queue releases controller responses independently; process
actions retain the INIT gate. Discovery now accepts capability-qualified
`uinput` pads/joysticks, while fingerprint and Android virtual devices remain
excluded. Neither defect is established as the cause on this Thor: its input
device inventory was absent from the 0.1.4 bundle.

New **Controller status** and exported `controller.json` capture device
capabilities, route/socket/INIT status, and aggregate input/request/reply counts.
The file is bounded to 64 KiB/32 devices and excludes current key/axis values
and device descriptors. XInput/raw-input tracing replaces Fog export snooping.
The report's `launchId` must match `launch.json`; prior reports are cleared at
launch start, and stale/invalid reports cannot overwrite or be exported for a
new attempt. These diagnostics should distinguish stages without equating
reply counts with PD2 activation. The 17 focused controller tests and final
49-test Android-framework suite passed, along with the ARM64 build. Packaged
native libraries/runtime archives match 0.1.4; the device retest is still
required.

The 0.1.5 comparison retains Wine 9.2, rootfs 24, the existing managed prefix,
and the exact working graphics/arguments. Runtime preparation and re-import are
unnecessary for the Java controller changes.
