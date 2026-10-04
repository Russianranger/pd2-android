# 0.1.13 startup repair and container management

Install **0.1.13 over the existing app** and keep the accepted Wine 9.2/rootfs 24, imported installation, prefix, Turnip + Zink / Glide (GameNative arguments), Stability and controller notifications. **Do not Prepare runtime, re-import, uninstall or clear storage.**

The 0.1.12 physical report says Play immediately returned to the launcher. No support ZIP was supplied for that attempt. Source and accepted runtime binary inspection found that preflight creates `rootfs/tmp/shm`, the subsequent temporary-file clear deletes it, and cached launch-environment reuse does not recreate it. 0.1.13 restores the directory immediately before guest execution. This fixes that source regression; the exact device failure branch remains unconfirmed until retest/logs.

## First check: Play

1. Open the updated app and press **Play**. Confirm it reaches the PD2 frontend and the same offline character.
2. If Play still returns, record the visible launcher message and **Export support logs immediately**. Do not reset the runtime or delete containers as a workaround. New bounded cleanup helper output and launch-failure fields distinguish preflight refusal from Windows runtime exit.
3. If it opens, continue the controller gate below. LT/thumb and shoulder behavior are accepted; no repeated binding qualification is requested.

## Containers

Open **Advanced runtime settings → Containers**. Each row identifies **Current PD2 · protected**, **Older PD2**, or **Additional container**, with its number and Wine identifier. The current label uses the same ready managed-container selection as Play. Reading the list does not create/migrate a prefix or change the selected preference.

The current/persisted-selected/runtime-selected container is protected. With the client fully stopped and other operations finished, an eligible older container's three-dot menu offers **Delete container…**. Confirmation explains that its private C: drive is removed; back up any personal files kept only in that old container first. Imported PD2 files/saves and mapped-drive targets remain. Links are unlinked without traversal. The worker rechecks current selection, active runtime state and path/inode identity before removal; deletion blocks startup, creation and duplication until it finishes. If the current selection cannot be identified safely, all containers are kept.

- Verify the current row is clearly labeled and its deletion action unavailable.
- An older row may be protected while its prefix remains selected by the runtime; this is shown as **Deletion unavailable**.
- Delete only an older container you no longer need. Confirm it disappears, the current row remains, and Play still opens the same character.
- If deletion reports an error, refresh the list and export support logs. A storage/I/O failure can leave part of the older container; the app does not claim success or remove shared targets.

## Controller gate retained from 0.1.12

1. Fresh app launch → Play → same offline character: check initial Native movement and a face-button action.
2. Save/Quit → use Menu cursor to re-enter → select Native, wait about one second, press/release a face button and check movement.
3. Export the first support ZIP **before Stop**, labeled after Save/Quit.
4. Save/exit normally → Stop client → wait for cleanup → Play again **without force-stopping Android** → enter the same character and test Native.
5. Export the second ZIP labeled after same-app Stop/Play. If cleanup/launch is refused, export immediately.

If full Stop/Play restores Native, accept that restart repair while in-place Save/Quit remains separate. If it fails after clean cleanup, investigate surviving backend/process/device ownership using the records rather than repeating blind reconnect/focus experiments.

The older reference/checklists below preserve accepted behavior and historical boundaries.

---

# 0.1.12 historical cleanup gate

The 0.1.11 device result accepts the LT/thumb combination and shoulder-button behavior. Native still fails after Save/Quit, and remains unavailable after stopping and relaunching Diablo II in the same Android app process. Force-stopping the Android app restores it. [Current evidence](evidence/2026-10-04-wine-session-cleanup.md) separates these two failure scopes.

0.1.12 repairs the confirmed Stop/relaunch teardown defect: the old launcher killed only explorer's PID, leaving Wine services and the controller port owner alive. It now shuts down the exact private Wine prefix, waits for server shutdown, verifies controller ports 7949/7950 are free, and refuses to start a competing client if cleanup is incomplete. Whole-environment cleanup and replacement startup are serialized on a worker; late old callbacks cannot clear a new launch PID. The report records both cleanup phases. Existing input routes, LT/shoulder fixes, runtime, prefix, import, saves and signer are retained.

**This is not a claim that Save/Quit inside the same running Wine session is fixed.** That result remains separately pending. No game DLL or Wine/HID binary is changed.

## Focused test

1. Install **0.1.12 over the existing app**. Keep the accepted runtime, prefix and import, **Turnip + Zink / Glide (GameNative arguments)**, **Stability**, and controller notifications enabled. Do not Prepare runtime, re-import or clear storage.
2. Enter the same offline character and check initial Native movement plus a face-button action. Save/Quit, use **Menu cursor** to re-enter if needed, then choose **Native controller**, wait about one second and press/release a face button. Report whether Native responds after re-entry. The LT/shoulder result is already accepted; no repeated binding qualification is requested.
3. If Native fails, return to the launcher and **Export support logs before stopping**, preserving the first session's controller counters. Save/exit Diablo normally, then **Stop client**. Wait for cleanup and the launcher to return. Keep the Android app open.
4. Press **Play** again in the same app session, enter the same character, select **Native controller** if needed and check movement/face-button response. This is the repaired restart path. **Export support logs** again, with both outcomes labeled. If launch is refused or cleanup fails, export immediately rather than resetting runtime/data.

New evidence: `launch.json.wineSessionCleanup.beforeLaunch` and `.afterStop` contain selected-prefix kill/wait statuses, exclusive controller-port release, elapsed time and passed/error status. UDP send counters alone still do not establish PD2 acceptance. A clean first launch can correctly have kill status 1 (no old wineserver), wait status 0 and free ports.

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

Title startup, initial Native input inside a character, Menu cursor navigation, LT/thumb combinations and shoulder behavior are accepted by user report. Native remains unavailable after Save/Quit and same-app client restart through 0.1.11. Use the focused 0.1.12 sequence above; the remaining sections retain reference bindings and later qualification checks. Do not prepare or re-import for this upgrade.

Export support information after the controller result before changing graphics settings. Further comparisons should follow diagnosis of that result.

## Native controller

Use a temporary offline character. PD2's controller mode activates on controller input; after selecting Native and closing the quick menu, press a controller button before checking movement/aiming.

The Wine 9.2 baseline keeps its legacy XInput bridge and adds a HID notification path for the same single selected gamepad. XInput triggers remain digital, and rumble is not supported by this donor DLL. The broader checklist below is retained for later qualification; LT/thumb and shoulder behavior do not need to be repeated for 0.1.12.

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

Local 0.1.9 verification passed all 93 API 33 tests and host input/focus regression checks, runtime verification, ARM64 assembly, and APK signature/identity checks. [Published 0.1.9 CI run 37191608087](https://github.com/Russianranger/pd2-android/actions/runs/37191608087) passed; the subsequent physical Native recovery failed by user report. Local 0.1.10 verification passed 105 unique automated tests, host checks, pinned dependencies, final ARM64 packaging, and APK identity/signature/payload comparison; its subsequent physical report still rejects Native on Save/Quit. Local 0.1.11 passed all 122 tests across 16 suites, the host/runtime checks, ARM64 assembly, and package identity/signature/integrity/alignment checks. Its physical feedback accepts LT/thumb and shoulders but rejects Native recovery. Local 0.1.12 passed 132 tests across 17 suites, all eight host scripts, runtime/backend checks, ARM64 assembly, packaging and identity/signature checks. All 72 assets still match 0.1.11. The current Save/Quit-versus-Stop/Play physical gate is pending; see [Handoff](HANDOFF.md) for the APK digest and native rebuild comparison.
