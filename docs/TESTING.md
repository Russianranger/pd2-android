# 0.1.11 controller combinations, cursor, and Save/Quit

Initial Native gameplay and Menu cursor are accepted. The latest 0.1.10 report still rejects Native on Save/Quit and adds LT + L3 and shoulder-tab problems. Earlier 0.1.9 recovery also failed after gameplay re-entry. 0.1.11 corrects demonstrated trigger/thumb-input defects and cursor layers, guards captured mouse input, and tests a synchronized HID + legacy XInput reconnect on explicit Native selection. Physical Save/Quit recovery and exact Native frontend behavior remain unconfirmed. See the [research evidence](evidence/2026-10-04-controller-research.md).

## Focused test

1. Install **0.1.11 over the existing app**. Keep the accepted runtime, prefix, and import; do not uninstall, clear storage, Prepare runtime, or re-import. Keep **Turnip + Zink · Glide (GameNative arguments)**, **Stability (default)**, and controller notifications enabled.
2. Enter the same offline character. Press/release a face button, then verify initial Native movement, aiming, and a normal gameplay action.
3. Check the installed client's Controls menu for its run/walk binding. Reproduce the reported combination by **holding LT, pressing and releasing L3, then releasing LT**. Try it twice and report whether run/walk changes. Test LT and L3 separately if the combination fails. The app now sends immediate Native thumb edges; the exact default PD2 binding is not established by public sources.
4. Open the in-game menu where tabs failed. Press/release **LB once**, then **RB once**, with controls released between presses. Record the menu/tab names and whether a press changes one tab, skips a tab, or does nothing. Test repeated presses separately from holding a shoulder. No shoulder-specific fix is claimed.
5. Save/Quit. Record Native response in the **in-game Save/Quit screen** and then **title/character menus** separately. Use the accepted **L3 + R3 → Menu cursor** to re-enter the same character if needed.
6. After the character loads, release all sticks/buttons. Explicitly choose **Native controller** in the gear, even if it has a checkmark. Wait about **one second**, press/release a **face button**, then check movement, aiming, LT + L3, and the same shoulder/tab presses. The reconnect briefly detaches both controller paths; stick motion alone is not the activation check.
7. If Native still fails, select it again once, wait, and repeat the face-button check. Report the first selection and this retry separately. If it responds, repeat Save/Quit/re-entry once, then check gear open/close and **Back to Launcher Menu → Resume client**.
8. Export support logs on success or failure. Identify the **first failed stage**, exact failed combination/tab action, and whether Menu cursor/touch remains usable. Keep initial gameplay, Save/Quit UI, title/character menus, gameplay re-entry, explicit Native retry, and gear/launcher resume results separate.

## White cursor check

Open the gear and set **Hide white cursor: On**. Check both the **main menu** and **in-game**: the app/root and Wine/X11 pointer overlays should disappear, while PD2's framebuffer cursor, such as its gauntlet, remains when supplied by the game. Confirm movement/clicks still work. Return to the launcher and resume, then relaunch later to check persistence. The option defaults **Off**; turn it Off if Menu cursor needs a visible pointer. Record cursor visibility separately from Native response.

The fiery PD2/skull icon and dark gear menu are retained. Report any clipped or unreadable controls.

Menu movement, click requests, and returned Windows cursor feedback are recorded separately. Request counts do not prove that PD2 accepted an input. `controller.json.nativeFocusRecovery` records queued foreground requests, without acknowledging Windows foreground or PD2 input consumption. Reconnect diagnostics likewise require a physical result; a requested reconnect is not proof of recovery.

`nativeReconnect` records Java-side reconnect phases and packet sends; `nativeStateDelivery` separates successful neutral/non-neutral HID and legacy state sends. Neither confirms PD2 input acceptance. The 0.1.10 trace independently shows Wine PnP detach/attach twice, yet the physical failure remains. 0.1.11 synchronizes legacy XInput lifetime with HID. New pointer-source diagnostics distinguish touch, external mouse, and captured forwarding by mode, and retain the latest context per mode through later Menu navigation; they record emitted requests rather than accepted game actions. If Native selection says **gamepad not ready to reconnect**, include Controller status and that message.

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

Title startup, initial Native input inside a character, and Menu cursor navigation are accepted by user report. Native failed after character re-entry on 0.1.9 and still fails on Save/Quit in the latest 0.1.10 report. The current step checks the corrected combination, shoulder navigation, expanded cursor option, and explicit Native reconnect after re-entry. Do not prepare or re-import for the 0.1.11 upgrade.

Export support information after the controller result before changing graphics settings. Further comparisons should follow diagnosis of that result.

## Native controller

Use a temporary offline character. PD2's controller mode activates on controller input; after selecting Native and closing the quick menu, press a controller button before checking movement/aiming.

The Wine 9.2 baseline keeps its legacy XInput bridge and adds a HID notification path for the same single selected gamepad. XInput triggers remain digital, and rumble is not supported by this donor DLL. Both sticks and normal button/trigger actions still require physical qualification.

1. Move using the left stick, then aim independently using the right stick.
2. Check face buttons, shoulders, triggers, D-pad, inventory navigation, and a skill assigned through PD2's normal controller settings.
3. Open the quick menu using the gear, then separately using **L3 + R3**. Close it and confirm both sticks still work.
4. Report whether PD2 displays controller prompts and whether the controller is recognized as a Windows/Xbox device. If only mouse/keyboard mode works, native controller qualification remains incomplete.

The app reserves L3 + R3. In Native, the first thumb down is sent immediately so LT + L3 and other held combinations can work; that first press may perform a game action before the second thumb opens the quick menu. Opening the menu releases the input. Menu cursor and Mouse / keyboard retain deferred single-thumb navigation so the shortcut does not also send Tab/Enter.

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

Local 0.1.9 verification passed all 93 API 33 tests and host input/focus regression checks, runtime verification, ARM64 assembly, and APK signature/identity checks. [Published 0.1.9 CI run 37191608087](https://github.com/Russianranger/pd2-android/actions/runs/37191608087) passed; the subsequent physical Native recovery failed by user report. Local 0.1.10 verification passed 105 unique automated tests, host checks, pinned dependencies, final ARM64 packaging, and APK identity/signature/payload comparison; its subsequent physical report still rejects Native on Save/Quit. Local 0.1.11 passed all 122 tests across 16 suites, the host/runtime checks, ARM64 assembly, and package identity/signature/integrity/alignment checks. All 72 runtime assets are unchanged. Physical Save/Quit, shoulder-tab and full controller qualification remain pending; see [Handoff](HANDOFF.md) for the APK digest and native rebuild comparison.
