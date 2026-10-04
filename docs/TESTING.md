# 0.1.8 Save/Exit menu-pointer test

Native input inside a character is accepted. The 0.1.7 manual Menu cursor attempt failed after Save/Exit despite correct Android focus and route ownership. The exact cause remains unconfirmed. This preview changes only temporary menu input and its visibility/diagnostics; it keeps the accepted Wine/HID runtime, prefix, import, and graphics settings.

## Focused test

1. Install **0.1.8 over the existing app**. Do not uninstall, clear storage, Prepare runtime, or re-import. Keep GameNative-arguments Glide, Stability, and controller notifications enabled.
2. Enter your offline character and verify Native controller still responds.
3. Save/Exit. Use **L3 + R3 → Menu cursor**, then close the quick menu. A visible app pointer should appear inside the current game window.
4. Move either stick. Report whether the pointer appears and moves, stays still, or moves outside the game. Move it over **Single Player** and press/release **A** to click. Repeat on the character selection controls.
5. Re-enter the same character, switch to **Native controller**, close the quick menu, and press a controller button. Check saved progress and that no pointer/key is held.
6. Export support logs on success or failure. State the first screen and action that failed, and whether touch could still operate that menu. Do not repeat runtime setup or change resolution for this comparison.

Menu pointer movement, click requests, and returned Windows cursor feedback are recorded separately. Request counts are not proof that PD2 accepted an input. Up to 16 filtered cursor/window contexts and per-mode handled-input counts help locate a failure.

## Menu cursor bindings

| Thor input | Menu input |
| --- | --- |
| Either stick | Mouse cursor; right stick takes priority |
| A | Left click |
| B / Select | Esc / back |
| Start / R3 | Enter / confirm |
| L3 | Tab |
| D-pad | Arrow keys |
| L3 + R3 | Quick menu |

Menu cursor does not send WASD, skills, modifiers, triggers, or right click. It is temporary; choose Native after the character loads. The full Mouse / keyboard layout below remains available separately.

If the client unexpectedly stops reaching the title, export that attempt first. To restore the original controller backend:

1. Stop the client and choose **Launch settings → Controller → Controller notifications disabled**.
2. Force-stop **PD2 Android** in Android app settings, reopen it, and press Play. The verified original Wine backend is restored before launch.
3. Export this new attempt separately. Do not Prepare runtime, re-import, uninstall, or clear storage for rollback.

If the whole Android app closes:

1. Reopen the app. A captured Java exception should lead to **PD2 Android recovery**.
2. Choose **Export crash details**, save/share `crash.txt`, and upload it to the chat. Include whether it closed before or after pressing Play.
3. **Retry launcher** lets you try again and retains the report. If the launcher becomes accessible, **Export support logs** provides the full diagnostic ZIP, including that report when present.
4. If recovery never appears or no report can be shared, report that exact behavior. Some process exits cannot be captured by the Java handler. Do not uninstall or clear storage to retry.

Support export retains the current runtime log and up to four archived attempts, and adds:

- XInput/raw-input traces for the controller path, without the per-report HID trace. The live log continues recording after rotation: at 8 MiB it keeps approximately the first 512 KiB and latest 4 MiB. Export/archive snapshots remain at most 2 MiB each and keep startup/tail sections with capture metadata and omission markers.
- Up to 32 recent controller mode/input-gate transitions with timestamps and last handled input/reply times, alongside aggregate counters. Snapshot flags describe capture time, not the whole session. `support.json` labels the saved mode preference; `controller.json` records the current session mode.
- `controller.json` (at most 64 KiB) with Android device/bridge diagnostics for the matching launch, plus `inputMode` in `support.json`. Stale/invalid controller reports are skipped.
- HID discovery/device/state counters on port 7950 alongside the legacy counters, plus controller backend enable/revision/install status in the launch/support records.
- Wine/rootfs/runtime-revision identity for the replacement baseline in support and launch records.
- Selected installation/version/trace registry settings in `launch.json`.
- PE stack reserve/commit sizes and bounded core-file hashes in `installation-files.json`.
- The newest two dated D2 logs and available D2DX/D2GL logs under `game-logs/`, each capped at 256 KiB. Binaries and saves are not included.

The current runtime trace enables XInput/raw-input diagnostics instead of Fog export snooping. The fifth choice remains `-3dfx -dxnocompatmodefix` without `-w` and retains the native wrapper. Keep this accepted startup configuration for the controller test.

## After PD2 reaches the menu

Use your complete, updated **English classic Diablo II + Lord of Destruction + Project Diablo 2** installation. Preserve your working Winlator/GameNative copy and offline-save backup.

Title startup and native input inside a character are accepted by user report. The current step is Save/Exit menu navigation and re-entry, followed by the detailed controls/save checklist below. Do not prepare or re-import for the 0.1.7 upgrade.

Export support information after the controller result before changing graphics settings. Further comparisons should follow diagnosis of that result.

## Native controller

Use a temporary offline character. PD2's controller mode activates on controller input; after selecting Native and closing the quick menu, press a controller button before checking movement/aiming.

The Wine 9.2 baseline keeps its legacy XInput bridge and adds a HID notification path for the same single selected gamepad. XInput triggers remain digital, and rumble is not supported by this donor DLL. Both sticks and normal button/trigger actions still require physical qualification.

1. Move using the left stick, then aim independently using the right stick.
2. Check face buttons, shoulders, triggers, D-pad, inventory navigation, and a skill assigned through PD2's normal controller settings.
3. Open the quick menu using the gear, then separately using **L3 + R3**. Close it and confirm both sticks still work.
4. Report whether PD2 displays controller prompts and whether the controller is recognized as a Windows/Xbox device. If only mouse/keyboard mode works, native controller qualification remains incomplete.

## Mouse/keyboard fallback and quick menu

Select **Mouse / keyboard layout** in the quick menu. This fallback uses the following fixed layout; its left-stick movement requires PD2's WASD movement setting.

| Thor input | Windows input |
| --- | --- |
| Left stick | W/A/S/D |
| Right stick | Mouse cursor |
| A / B | Left / right click |
| X / Y | Shift / Alt |
| LB / RB | F1 / F2 |
| LT / RT | 1 / 2 |
| D-pad up / right / down / left | F3 / F4 / F5 / F6 |
| Select / Start | Esc / I |
| L3 / R3 | Tab / Enter |
| L3 + R3 | Quick menu |

1. Confirm right-stick cursor movement and A/B clicks. Adjust cursor speed and deadzone in the quick menu.
2. Open the Android keyboard and enter a short character name or chat message; confirm Enter and closing the keyboard work.
3. Switch between Native controller and Mouse/keyboard several times. Repeat while a stick is displaced or a button held, then release it. Confirm no movement, skill, click, or key remains stuck.
4. Use **Back to Launcher Menu**, then **Resume client** on the launcher. Confirm the same character/session remains open.

## Save, background, and online checks

1. In an offline session, save and exit normally, stop the game, and relaunch. Confirm that character can reopen with its saved progress.
2. During a session, switch to another Android app for a minute and return. Check audio, controls, rendering, and the running-session indicator. Screen-lock behavior is a separate test; record whether it was attempted.
3. Once rendering and inputs work, try your normal PD2 online login and enter a game. Record any exact login/server error. Never include passwords in feedback.

## Import checks

These can follow the gameplay checks; do not overwrite a valuable offline save for an import test.

- While the game is running, verify importing another folder/ZIP is blocked.
- Stop the game and try a ZIP containing only `ProjectD2`; confirm it is rejected and the accepted installation remains available.
- Re-import only when deliberately updating/replacing the install. The import copies files; do not assume existing private saves are merged into a replacement.

## What to send back

Use **Export support logs** after a failed attempt, save/share the resulting ZIP, and report:

- Renderer, launch arguments, input mode, and whether this was a first or subsequent launch.
- The last successful step and the exact visible error or screenshot.
- Whether both native sticks worked, whether input switching was clean, and whether Resume kept the session.
- Whether offline saves reopened and online login/game entry succeeded.
- Any black screen, missing texture/audio, crash, unusual heat, or shutdown of background apps.

An import success is not a gameplay success. APK build/CI success is not physical-device qualification. Performance comparisons should use the same character, area, resolution, and settings in this app and the existing working setup.

Support export contains diagnostics and log tails, not game files or offline-save backups.

0.1.6 local verification and [CI run 37167615176](https://github.com/Russianranger/pd2-android/actions/runs/37167615176) passed; its native in-character input was subsequently accepted by the user. Local 0.1.7 verification passed all 78 Robolectric tests (13 suites, zero failures/errors/skips), eight real-Java-router host scenarios, and 749 logging assertions, plus ARM64 assembly, signature/identity, and payload checks. [0.1.7 CI run 37169294205](https://github.com/Russianranger/pd2-android/actions/runs/37169294205) passed; the physical menu test failed as reported by the user. Local 0.1.8 verification passed: 86 Android 13/API 33 Robolectric tests across 13 suites, with no failures, errors or skips; 9 real-router scenarios; and 11 menu-pointer scenarios with 145 checks. These include the actual mouse/keyboard datagram layout, queued-action expiry, fresh Windows cursor synchronization, lost-feedback retry, resize/clipping, and balanced controls. ARM64 assembly and APK identity/signature checks passed: `com.pd2.thor`, 0.1.8/code 9, min SDK 26, target SDK 28, unchanged preview certificate. All 35 Android native libraries and 72 runtime assets match the verified 0.1.7 APK byte-for-byte, including the accepted HID module. APK size: 171,307,574 bytes. SHA-256: `53cec30a4ee97ef6bb30453ee694c618631572eb853c86dfcc2944e2e4d4c550`. Packaging drift rejection and correct-version artifact fixtures passed. Published 0.1.8 CI and the physical menu/re-entry test remain pending. Automated checks do not qualify the physical menu transition, complete controller bindings, saves, or online play. Full results are recorded in [Handoff](HANDOFF.md).
