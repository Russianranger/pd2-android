# PD2 Android 0.1.5 controller detection preview

The user reached PD2's title screen on 0.1.4, but native controller detection did not work. This preview targets Java controller discovery/response handling and adds diagnostics. **The reported controller failure's cause and the correction's device result remain unconfirmed.**

## Changed

- Allow controller replies before the runtime helper's INIT and accept capability-qualified `uinput` gamepads/joysticks. This corrects source behavior without establishing the device failure's cause.
- Add quick-menu **Controller status** with accepted pads and aggregate input/discovery/state-reply counters; gameplay input pauses while the menu is open.
- Include bounded, launch-matched `controller.json` in support exports and `inputMode` in `support.json`; device capabilities and aggregate counters are recorded, without current key/axis values or device descriptors. Stale/invalid reports are skipped.
- Enable XInput/raw-input tracing and stop Fog export snooping after accepted startup.

Version 0.1.5/code 6 retains the application/signing identity, Wine 9.2, rootfs 24, current prefix revision, runtime assets, and imported files/saves. **No Prepare runtime or re-import is needed.** Native twin-stick gameplay and the remaining milestone gates require device qualification.

## One test

1. Install over 0.1.4 without uninstalling or clearing storage. Keep the accepted GameNative-arguments Glide choice and Stability.
2. Enter a temporary offline character using mouse/keyboard fallback if needed.
3. Switch to **Native controller**, close the menu, press a controller button, then check both sticks and buttons without mouse input.
4. Inspect **Controller status**, then export support logs and upload the ZIP whether input works or fails.

Local verification passed all 49 Android 13/API 33 tests, the import/crash/session-log/input-router checks, and the ARM64 APK build. All 35 packaged native libraries and 25 runtime archives match 0.1.4. Published 0.1.5 CI and physical controller activation remain pending. APK SHA-256: `cd9f9ed746cd1c67e4417172034aa89c8b993253be1d48b92dc36dbb3639e02a`. See [Testing](TESTING.md) for the sequence and [Handoff](HANDOFF.md) for accepted steps and evidence limits.
