# 0.1.11 controller combinations and cursor layers

Initial Native gameplay and Menu cursor navigation remain accepted. The latest 0.1.10 report still finds Native unresponsive on Save/Quit, with unreliable LT + L3 and shoulder tab navigation. This preview corrects demonstrated Android input/rendering defects and includes a bounded recovery experiment. **Save/Quit recovery and shoulder-tab behavior still require a physical result.**

## Changes

- Handle LT and RT independently. A digital trigger event no longer disables analog updates for both triggers; digital-only sides survive motion, and a digital full-press release preserves a partial analog hold.
- Send Native L3/R3 press and release immediately so held combinations such as the reported LT + L3 can reach the game. L3 + R3 remains the quick-menu shortcut; its first thumb press can reach gameplay before the second opens the menu. Pointer-layout single-thumb navigation remains deferred.
- Honor the captured-pointer enabled gate, skip movement that rounds to zero, and release only mouse buttons actually held during activity cleanup. Coalesce shared-router releases when multiple sources hold the same control. Invalidate stale delayed touch releases and block disabled touch-up warps. Add pointer-source/emission diagnostics and retain the latest pointer context by mode. Deliberate touch and external mouse remain available in Native.
- On explicit **Native controller** selection, briefly detach both HID and legacy XInput, hold both absent for at least 600 ms, then restore both neutral before normal input resumes. This tests coherent controller lifetime after Save/Quit; it does not establish that PD2 recovers.
- Expand the saved **Hide white cursor** toggle to the app's root arrow, Wine/X11 cursor overlay, and Android pointer icons. The cursor rendered in the game's framebuffer remains visible when supplied by PD2. The option still defaults Off.
- Retain foreground recovery, Menu cursor, the accepted Wine/HID assets, prefix/import/saves, graphics settings, fiery PD2/skull icon, dark menu, and signing identity.

Version **0.1.11/code 12** retains `com.pd2.thor`, min SDK 26, and target SDK 28. No game DLL patch, packaged Wine controller replacement, or runtime/prefix migration is included.

## Focused device check

Install over the existing app and reuse the accepted runtime, prefix, and import. Keep GameNative-arguments Glide, Stability, and controller notifications enabled. Do not Prepare runtime or re-import for this check.

1. Enter the same offline character and verify Native movement and a face-button action. Hold LT, press/release L3, then release LT; repeat once. Test each shoulder separately in the problematic in-game tabs and record the menu/tab names.
2. Save/Quit. Record Native behavior in the Save/Quit screen, title/character menus, and gameplay re-entry separately. Use Menu cursor to re-enter if needed.
3. After loading, release controls and explicitly select **Native controller**, wait about one second, and press/release a face button before checking movement and the same combinations. If needed, repeat this selection once and report the retry separately.
4. Check **Hide white cursor: On** in the main menu and in-game, then after Resume. Turn it Off if Menu cursor needs a visible pointer. Export support logs and identify the first failed stage.

[Testing](TESTING.md) contains the complete sequence and control tables. The [research report](evidence/2026-10-04-controller-research.md) records source defects, latest log counts, primary upstream references, and unresolved limits. Local verification passed **122 tests across 16 suites**, with no failures, errors or skips; all host input/import/recovery/log and runtime checks passed, including the Wine backend sanitizer checks and all 206 final runtime pins. Final ARM64 assembly, package identity, matching preview certificate/V2 signature, ZIP integrity and alignment passed. All 72 runtime assets match the verified 0.1.10 CI APK byte-for-byte. Of 35 Android native libraries, 26 match exactly, eight differ only in build IDs, and VirGL rebuild differences were statically traced to source-path strings and address/relocation adjustments; this is not physical graphics qualification. No Wine/HID binary changes are included.

APK: `PD2-Android-0.1.11-preview.apk`, **174,055,860 bytes**. SHA-256: `0a307e4cde8a88312ec82f4413e76f71cf1f7d935a1597e86557081460e7a89c`. Identity: `com.pd2.thor`, 0.1.11/code 12, min SDK 26, target SDK 28, ARM64 only. [Published 0.1.11 CI run 37206816716](https://github.com/Russianranger/pd2-android/actions/runs/37206816716) passed for code commit `2f5e1a9086c3131a9de265813c636f4c57eda6c0`; physical Save/Quit and shoulder-tab qualification remain pending.
