# PD2 Android 0.1.8 menu-pointer recovery preview

The user reported that 0.1.7 Menu cursor remained unresponsive after Save/Exit. Its new capture confirms the mode was active with Android focus, but lacked pointer output/context evidence. The failure's exact cause remains unconfirmed.

## Changed

- Show the app pointer in active Menu cursor mode even when PD2 supplies an invisible game cursor.
- Send Menu movement, clicks, and navigation keys through the existing Windows input helper; recover the game window and use its current geometry for pointer centering/bounds.
- Use returned Windows cursor position, limit outstanding movement, and cancel queued presses/moves when Menu loses ownership. Release held controls when switching.
- Add input counts/times per mode, output-request and cursor-feedback counters, and 16 bounded, filtered cursor/window contexts.
- Reject stale incremental-build version metadata before packaging, protecting earlier preview files.
- Preserve native gameplay input, the full Mouse / keyboard layout, runtime/prefix, graphics settings and imported game files/saves.

Version 0.1.8/code 9 retains the application/signing identity, min SDK 26 and target SDK 28.

## Device check

Install over the existing app without Prepare runtime/re-import. Confirm Native in a character, Save/Exit, then **L3 + R3 → Menu cursor**. Check visible pointer movement and **A** clicks separately. Re-enter your character and select Native again. Export support logs and report the first failed action. See [Testing](TESTING.md).

Local 0.1.8 verification passed: 86 Android 13/API 33 Robolectric tests across 13 suites, with no failures, errors or skips; 9 real-router scenarios; and 11 menu-pointer scenarios with 145 checks. These include the actual mouse/keyboard datagram layout, queued-action expiry, fresh Windows cursor synchronization, lost-feedback retry, resize/clipping, and balanced controls. ARM64 assembly and APK identity/signature checks passed: `com.pd2.thor`, 0.1.8/code 9, min SDK 26, target SDK 28, unchanged preview certificate. All 35 Android native libraries and 72 runtime assets match the verified 0.1.7 APK byte-for-byte, including the accepted HID module. APK size: 171,307,574 bytes. SHA-256: `53cec30a4ee97ef6bb30453ee694c618631572eb853c86dfcc2944e2e4d4c550`. Packaging drift rejection and correct-version artifact fixtures passed. Published 0.1.8 CI and the physical menu/re-entry test remain pending. See [Handoff](HANDOFF.md) and [evidence](evidence/2026-10-04-menu-pointer.md).
