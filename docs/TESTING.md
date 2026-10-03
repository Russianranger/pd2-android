# 0.1.5 controller detection test

The user reached the PD2 title screen with 0.1.4, but the controller was not detected. Launcher startup, runtime preparation, import, and client appearance remain accepted. The 0.1.5 preview changes Java controller discovery/response handling and diagnostics; its device result remains unqualified. It preserves Wine 9.2/rootfs 24 and the accepted prefix.

## Controller retest

1. Install **0.1.5 over 0.1.4** without uninstalling or clearing storage. **Reuse the accepted runtime/prefix/game files. Do not Prepare runtime or re-import.**
2. Keep **Turnip + Zink · Glide (GameNative arguments)** and **CPU mode → Stability (default)**, then press Play. Do not cycle through renderers or repeat Interpreter.
3. Use **Mouse / keyboard layout** only as needed to enter a temporary offline character. Title/menu controller navigation is unverified; check native input inside the game.
4. Select **Native controller**, close the quick menu, and press a controller button. Check left-stick movement and independent right-stick aiming, then buttons/triggers. Avoid mouse movement/clicks while checking native input: PD2 can switch back to mouse/keyboard on mouse input.
5. Open **Controller status** after the attempt. Record the accepted pad count and input/discovery/state-reply counters. Gameplay input is paused while the menu is open; close it before testing the sticks again.
6. Return to the launcher and **Export support logs**, then upload the ZIP whether native input worked or failed. Report whether the title appeared, whether the offline character opened, and which button/stick actions worked.

If the client unexpectedly stops reaching the title, stop the attempt and export its logs. Preserve the working runtime and import while that regression is investigated.

If the whole Android app closes:

1. Reopen the app. A captured Java exception should lead to **PD2 Android recovery**.
2. Choose **Export crash details**, save/share `crash.txt`, and upload it to the chat. Include whether it closed before or after pressing Play.
3. **Retry launcher** lets you try again and retains the report. If the launcher becomes accessible, **Export support logs** provides the full diagnostic ZIP, including that report when present.
4. If recovery never appears or no report can be shared, report that exact behavior. Some process exits cannot be captured by the Java handler. Do not uninstall or clear storage to retry.

Support export retains the current runtime log and up to four archived attempts, and adds:

- XInput/raw-input traces for the controller path; traces and replies do not establish that PD2 accepted input.
- `controller.json` (at most 64 KiB) with Android device/bridge diagnostics for the matching launch, plus `inputMode` in `support.json`. Stale/invalid controller reports are skipped.
- Wine/rootfs/runtime-revision identity for the replacement baseline in support and launch records.
- Selected installation/version/trace registry settings in `launch.json`.
- PE stack reserve/commit sizes and bounded core-file hashes in `installation-files.json`.
- The newest two dated D2 logs and available D2DX/D2GL logs under `game-logs/`, each capped at 256 KiB. Binaries and saves are not included.

The current runtime trace enables XInput/raw-input diagnostics instead of Fog export snooping. The fifth choice remains `-3dfx -dxnocompatmodefix` without `-w` and retains the native wrapper. Keep this accepted startup configuration for the controller test.

## After PD2 reaches the menu

Use your complete, updated **English classic Diablo II + Lord of Destruction + Project Diablo 2** installation. Preserve your working Winlator/GameNative copy and offline-save backup.

Title startup is already accepted. The current step is native controller activation inside an offline game, followed by the controls/save checklist below. Do not prepare or re-import for the 0.1.5 upgrade.

Export support information after the controller result before changing graphics settings. Further comparisons should follow diagnosis of that result.

## Native controller

Use a temporary offline character. PD2's controller mode activates on controller input; after selecting Native and closing the quick menu, press a controller button before checking movement/aiming.

The Wine 9.2 baseline uses its matching legacy controller bridge with one active gamepad. XInput triggers are digital rather than proportional, and rumble is not supported by this donor DLL. Both sticks and normal button/trigger actions still require physical qualification.

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

Local 0.1.5 verification passed all 49 Android 13/API 33 Robolectric tests, the 47 import/11 crash-recovery/26 session-log checks, input-router checks, and the ARM64 APK build. Packaged native libraries/runtime archives match 0.1.4. Published 0.1.5 CI remains pending. Automated checks do not qualify PD2 controller activation on the Thor. Full results and the accepted 0.1.4 title startup are recorded in [Handoff](HANDOFF.md).
