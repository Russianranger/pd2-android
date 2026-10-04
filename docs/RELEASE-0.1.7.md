# PD2 Android 0.1.7 menu-control preview

Native controller input inside a character was accepted by the user on 0.1.6. After Save/Exit, the main menu did not respond to the controller. **This preview adds manual Menu cursor navigation and transition diagnostics; it does not establish the menu failure's cause or claim that native menus are fixed.**

## Changed

- Add temporary **Menu cursor** in the quick menu: either stick moves the pointer, A clicks, B/Select sends Esc, Start/R3 Enter, L3 Tab, and the D-pad arrow keys. Return to Native controller after entering a character. The full Mouse / keyboard layout remains separate.
- Keep recording when runtime logs reach 8 MiB, preserving startup and the latest tail instead of stopping capture. Exports include capture metadata and omission markers.
- Retain XInput/raw-input traces, remove per-report HID tracing, and record recent input-mode/gate changes with timestamps.
- Keep the accepted Wine 9.2/HID backend, prefix, graphics configuration, and imported files/saves. No resolution guess, automatic menu detection, or PD2 DLL change.

Version code is 8; application/signing identity and SDK levels are unchanged.

## One test

1. Install over 0.1.6. Do not uninstall, clear storage, Prepare runtime, or re-import. Keep the accepted GameNative-arguments Glide choice, Stability, and notifications enabled.
2. Use **L3 + R3 → Menu cursor** to enter a temporary offline character without touch. Select Native controller after loading, close the quick menu, and press a controller button.
3. Save/Exit. Select Menu cursor, re-enter the same character, then return to Native. Check saved progress and clean input switching.
4. Export support logs on success or failure. Report whether the chord/menu navigation worked and whether native input resumed after re-entry.

Local verification passed all 78 Robolectric tests across 13 suites with zero failures/errors/skips, eight real-Java-router host scenarios, and 749 logging assertions. ARM64 assembly and identity/signature checks passed: `com.pd2.thor`, 0.1.7/code 8, min SDK 26, target SDK 28, with the same certificate. All 35 Android native libraries and 70 baseline assets match the verified 0.1.5 APK; both custom controller assets match tracked 0.1.6 source. The native module is unchanged. APK size: 171,296,970 bytes. SHA-256: `d36d82927bcc7ee745fe7a5ae704545c8cc27d256b15c5a928000fd409c30d1a`. Published 0.1.7 CI and the physical menu-transition test remain pending. The earlier [0.1.6 CI run](https://github.com/Russianranger/pd2-android/actions/runs/37167615176) passed. See [Testing](TESTING.md), [Handoff](HANDOFF.md), and the [evidence note](evidence/2026-10-04-menu-transition.md).
