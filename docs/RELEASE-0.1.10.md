# PD2 Android 0.1.10 Native reconnect preview

The 0.1.9 physical check still failed Native input after Save/Exit and after character re-entry; Menu cursor remains usable. This preview requests a brief HID reconnect on every explicit **Native controller** selection, including selecting Native again as a retry. The cause remains unconfirmed and physical recovery needs another check.

## Changed

- Request Java-side HID disconnect/reconnect using the existing absent/present protocol. Legacy XInput remains connected and neutral during an absence interval of at least 600 ms.
- Start the explicit reconnect after Native input is active; modal/lifecycle changes alone do not request reconnects.
- Cancel interrupted/stale reconnect work and record reconnect/state-delivery diagnostics without claiming Windows/PD2 acceptance.
- Preserve Game.exe foreground recovery and the accepted Wine/HID runtime assets, prefix, graphics settings, imported files/saves, and signing identity.
- Add a saved **Hide white cursor** toggle in the gear menu, default Off. It hides the app's white pointer overlay while preserving the game's cursor and pointer input.
- Retain the fiery PD2/skull icon, dark quick menu, and accepted Menu cursor navigation.

Version 0.1.10/code 11 retains `com.pd2.thor`, min SDK 26, and target SDK 28.

## Device check

Install over the existing app without uninstalling, clearing storage, Prepare runtime, or re-import. Keep accepted GameNative-arguments Glide, Stability, and controller notifications enabled.

1. Confirm initial Native gameplay, then Save/Exit and re-enter the same character using Menu cursor if needed.
2. After loading, release all sticks/buttons and explicitly select **Native controller**, even if it already has a checkmark. Wait about one second, press/release a **face button**, then test sticks and normal gameplay actions.
3. If it still fails, select Native again once, wait, and repeat the face-button check. Report this retry separately.
4. If gameplay responds, repeat Save/Exit/re-entry and check gear open/close and **Back to Launcher Menu → Resume client**.
5. Test **Hide white cursor: On**. PD2's cursor should remain when supplied by the game. Turn the option Off if Menu cursor needs its visible app pointer.
6. Export support logs with the exact first failed step and the Native retry/cursor-toggle results.

Local verification passed all 105 unique automated tests, host input/reconnect checks, pinned dependencies, and final ARM64 packaging. APK identity/signature checks passed; all 72 runtime assets and 35 Android native libraries match 0.1.9 byte-for-byte. [Published 0.1.10 CI run 37196226447](https://github.com/Russianranger/pd2-android/actions/runs/37196226447) passed. The subsequent physical report still rejects Native on Save/Quit. See [Testing](TESTING.md), [Handoff](HANDOFF.md), and the [evidence note](evidence/2026-10-04-native-reconnect.md).

Local APK SHA-256: `e2f8ea363b5f2d6c3b332998bad91b612bdd348d12e1c6f920b6af1186ce5440`. An independently built CI APK has its own digest.
