# 0.1.4 Wine baseline comparison test

Launcher startup, runtime preparation, and import remain accepted. Both 0.1.2 CPU modes and the 0.1.3 matching-arguments attempt failed at the same native Fog stack write. The 0.1.4 preview changes Wine 10.10 to Wine 9.2 (Custom) with matched prefix assets, retaining Box64 0.4.4 and graphics components. This tests a runtime compatibility hypothesis; it does not claim a confirmed fix or reproduce GameNative's Bionic Proton runtime.

## Next launch test

1. Install **0.1.4 over the existing app** without uninstalling or clearing storage. **Reuse the accepted imported game files; do not re-import.**
2. Press **Prepare runtime once after upgrading** and wait for completion. This installs the replacement Wine runtime. The first Play creates a fresh Wine 9.2 prefix; previous prefixes and private game files/saves are retained.
3. In **Launch settings**, select **Turnip + Zink · Glide (GameNative arguments)** and **CPU mode → Stability (default)**. Do not repeat Interpreter or cycle through the other choices.
4. Press **Play once** and allow at most **60 seconds** for the title/menu. Record whether it returns to the launcher, displays the title/menu, or remains unfinished at the limit.
5. If it returns, export immediately. If it remains unfinished, use the quick menu to return to the launcher and press **Stop client**. If the menu appears, record that success, then stop the client without starting a gameplay test.
6. Use **Export support logs**, save/share the new ZIP, and upload it to the chat whether the menu appeared or the attempt failed. Keep Stability selected.

The 60-second interval bounds this diagnostic attempt; it does not establish how long every valid load should take. Do not cycle through the other graphics choices for this test.

If the whole Android app closes:

1. Reopen the app. A captured Java exception should lead to **PD2 Android recovery**.
2. Choose **Export crash details**, save/share `crash.txt`, and upload it to the chat. Include whether it closed before or after pressing Play.
3. **Retry launcher** lets you try again and retains the report. If the launcher becomes accessible, **Export support logs** provides the full diagnostic ZIP, including that report when present.
4. If recovery never appears or no report can be shared, report that exact behavior. Some process exits cannot be captured by the Java handler. Do not uninstall or clear storage to retry.

Support export retains the current runtime log and up to four archived attempts, and adds:

- Filtered Fog native-export call details in the runtime trace; these are not a complete stack or a trace of every internal recursive call.
- Wine/rootfs/runtime-revision identity for the replacement baseline in support and launch records.
- Selected installation/version/trace registry settings in `launch.json`.
- PE stack reserve/commit sizes and bounded core-file hashes in `installation-files.json`.
- The newest two dated D2 logs and available D2DX/D2GL logs under `game-logs/`, each capped at 256 KiB. Binaries and saves are not included.

The fifth choice uses `-3dfx -dxnocompatmodefix` without `-w` and retains the native wrapper. These arguments already failed on the prior Wine 10.10 runtime; the current comparison changes the Wine baseline. The compatibility flag does not establish a cause for the early Fog fault. The goal remains first client appearance and useful evidence, not controller/gameplay qualification.

## After PD2 reaches the menu

Use your complete, updated **English classic Diablo II + Lord of Destruction + Project Diablo 2** installation. Preserve your working Winlator/GameNative copy and offline-save backup.

Report that the menu appeared and export the result before pursuing graphics/controller changes. Do not re-import; runtime preparation is required once for this upgrade's changed baseline. The controls and save checklist below remains the later milestone, after an appropriate PD2/D2GL graphics path is established.

Export support information after this fifth-profile result before changing graphics settings. Further comparisons should follow diagnosis of that result.

## Native controller

Use a temporary offline character for the first gameplay test, enabling PD2's controller support/settings where required.

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

Local 0.1.4 verification passed all 39 Android 13/API 33 Robolectric tests, the 47 import/11 crash-recovery/26 session-log checks, input-router checks, and 24 runtime tests. All 206 dependency hashes and 49 relocated assets verified; the ARM64 APK build passed. Published 0.1.4 CI remains pending. Automated checks do not run PD2 or confirm the Fog failure's cause on the Thor. Full results and the successful historical 0.1.3 CI run are recorded in [Handoff](HANDOFF.md).
