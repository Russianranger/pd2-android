# 0.1.10 Native reconnect after Save/Exit

Native initially works inside a character, and Menu cursor is accepted. The user rejected 0.1.9 Native recovery both after Save/Exit and after character re-entry. This preview requests a brief HID reconnect whenever **Native controller** is explicitly selected, including selecting it again as a retry. Foreground recovery remains active. The exact cause and Native support in title/character menus remain unconfirmed.

## Focused test

1. Install **0.1.10 over the existing app**. Do not uninstall, clear storage, Prepare runtime, or re-import. Keep **Turnip + Zink · Glide (GameNative arguments)**, **Stability (default)**, and controller notifications enabled.
2. Enter the same offline character. Press/release a controller face button, then verify initial Native movement and normal gameplay actions.
3. Save/Exit. Record whether Native responds at the title/character menus, then use the accepted **L3 + R3 → Menu cursor** to re-enter the same character if needed.
4. After the character loads, release all sticks/buttons. Open the gear and explicitly choose **Native controller**, even if Native already has a checkmark. The menu closes and requests a brief reconnect. Wait about **one second**, press/release a **face button**, then check left-stick movement, aiming, and gameplay actions. Stick motion alone is not the activation check.
5. If Native still does not respond, select **Native controller** again once, wait one second, and press/release a face button before retesting. Report whether the first selection or this retry worked.
6. If gameplay responds, repeat Save/Exit and character re-entry once. Then check controls after opening/closing the gear and after **Back to Launcher Menu → Resume client**. If needed, explicitly select Native again and report that recovery separately.
7. Export support logs on success or failure. Report **initial Native gameplay**, **Native at menus**, **Native after re-entry**, **explicit Native retry**, and **gear/launcher resume** separately. Include the first failed action and whether Menu cursor/touch remains usable. Keep the accepted runtime and graphics settings for this comparison.

## White cursor check

Open the gear and set **Hide white cursor: On**. The app's white pointer should disappear while PD2's own cursor, such as its gauntlet, remains visible when the game supplies it. Confirm movement/clicks still work. Return to the launcher and resume, then relaunch later to check that the choice persists. The option defaults to **Off**; if Menu cursor loses its visible pointer, turn it Off for navigation.

The fiery PD2/skull icon and dark gear menu are retained. Report any clipped or unreadable controls.

Menu movement, click requests, and returned Windows cursor feedback are recorded separately. Request counts do not prove that PD2 accepted an input. `controller.json.nativeFocusRecovery` records queued foreground requests, without acknowledging Windows foreground or PD2 input consumption. Reconnect diagnostics likewise require a physical result; a requested reconnect is not proof of recovery.

`nativeReconnect` records Java-side reconnect phases and packet sends; `nativeStateDelivery` separates successful neutral/non-neutral HID and legacy state sends. Neither confirms Windows PnP completion or PD2 input acceptance. If the Native selection says **gamepad not ready to reconnect**, check Controller status and include that message in the report.

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

Title startup, initial Native input inside a character, and Menu cursor navigation are accepted by user report. Native failed after character re-entry on 0.1.9. The current step tests the explicit Native reconnect and a face-button activation before checking sticks. Do not prepare or re-import for the 0.1.10 upgrade.

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

Earlier local checks and published CI passed for 0.1.6, 0.1.7, and 0.1.8. The user accepted native in-character input on 0.1.6, rejected Menu cursor on 0.1.7, then accepted Menu cursor on 0.1.8 while reporting that Native remained unresponsive after Save/Exit. [0.1.8 CI run 37172243169](https://github.com/Russianranger/pd2-android/actions/runs/37172243169) succeeded. Automated checks do not qualify Native title-menu support, physical recovery after re-entry, complete controller bindings, saves, or online play. Full evidence and current validation are recorded in [Handoff](HANDOFF.md).

Local 0.1.9 verification passed all 93 API 33 tests and host input/focus regression checks, runtime verification, ARM64 assembly, and APK signature/identity checks. [Published 0.1.9 CI run 37191608087](https://github.com/Russianranger/pd2-android/actions/runs/37191608087) passed; the subsequent physical Native recovery failed by user report. Local 0.1.10 verification passed 105 unique automated tests and host input/reconnect checks, pinned dependencies, final ARM64 packaging, and APK identity/signature/payload comparison. Published 0.1.10 source CI and physical reconnect qualification remain pending; see [Handoff](HANDOFF.md) for exact results.
