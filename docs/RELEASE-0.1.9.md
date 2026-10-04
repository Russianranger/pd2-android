# PD2 Android 0.1.9 native foreground-recovery preview

The user accepted 0.1.8 Menu cursor navigation, but Native remained unresponsive after Save/Exit. This preview restores Game.exe Windows foreground focus when Native input resumes and after game-window transitions. The cause of the reported failure and PD2's native controller support in its title/character menus remain unconfirmed.

## Changed

- Request Game.exe foreground focus when Native regains active input after a mode, quick-menu, or lifecycle change.
- Coalesce game-window map/resize events, wait for helper readiness, and retry once after settling. Cancel stale queued requests when Native loses ownership.
- Resolve the current game window for each request and expire Menu cursor's queued focus request when its mode loses ownership. Native recovery sends no pointer/key input.
- Record Native foreground-request counts and last request time/reason in support diagnostics; requests do not acknowledge Windows/PD2 acceptance.
- Retain the accepted native controller runtime/backend and Menu cursor navigation.
- Add the requested PD2 icon with fire and a devilish skull, including an adaptive launcher icon.
- Use dark surfaces for the full-screen session, gear menu, bindings, controller status, and runtime dialogs, with readable gold actions.

Version 0.1.9/code 10 retains the application/signing identity, min SDK 26, target SDK 28, imported files/saves, prefix, and accepted graphics/runtime settings.

## Device check

Install over the existing app without uninstalling, clearing storage, Prepare runtime, or re-import. Keep accepted GameNative-arguments Glide, Stability, and controller notifications enabled.

1. Confirm Native gameplay in a character, then Save/Exit and test Native at the title/character menus.
2. If Native cannot navigate there, use the accepted **L3 + R3 → Menu cursor** to enter the same character.
3. Once loaded, choose **Native controller**, close the gear menu, press/release a controller button, and test gameplay again.
4. Repeat the cycle once, then check controls after gear open/close and **Back to Launcher Menu → Resume client**.
5. Export support logs. Report Native menu and gameplay results separately, plus the new icon/dark menu appearance.

Local verification passed all 93 API 33 tests, host input/focus regression checks, runtime checks, and ARM64 assembly. Signature/identity, ZIP integrity/alignment, and comparison of all 72 runtime assets passed. [Published 0.1.9 CI run 37191608087](https://github.com/Russianranger/pd2-android/actions/runs/37191608087) passed. The subsequent device check failed Native after Save/Exit and character re-entry; Menu cursor remained usable. See the [0.1.10 evidence note](evidence/2026-10-04-native-reconnect.md). See [Testing](TESTING.md), [Handoff](HANDOFF.md), and the [evidence note](evidence/2026-10-04-native-foreground.md).

Local APK SHA-256: `e6b1a0215b3502b3fe6fc57d915bdeb1d66b136db89720cde65162c9db5d9e16`. An independently built CI APK has its own digest in its accompanying `SHA256SUMS`.
