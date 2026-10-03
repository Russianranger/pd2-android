# 0.1.2 launch diagnostics and Thor qualification

Launcher startup, runtime preparation, and installation import were accepted on 0.1.1. The new 0.1.2 Stability/Wine DirectDraw attempt also failed. Its trace locates the first captured game fault in native `Fog.dll + 0x1879A`, with Wine reporting a stack overflow; the underlying cause is unconfirmed. The existing 0.1.2 APK already contains the next Interpreter diagnostic option. This documentation update does not introduce a new APK or claim a fix.

## Next launch test

1. Keep the installed **0.1.2** app and its accepted runtime/game files. **Do not prepare, import, reinstall, or repeat the failed Stability attempt.**
2. Keep **Launch settings → Turnip + Zink · Wine DirectDraw (compatibility)** selected.
3. Select **Launch settings → CPU mode → Interpreter (diagnostic; very slow)**.
4. Press **Play once** and allow at most **60 seconds** for the title screen. Record whether it returns to the launcher, displays the title/menu, or remains unfinished at the limit.
5. If it returns, export immediately. If it remains unfinished, use the quick menu to return to the launcher and press **Stop client**. If the menu appears, record that success, then stop the client without starting a gameplay test.
6. Use **Export support logs**, save/share the new ZIP, and upload it to the chat. Restore **CPU mode → Stability (default)** afterward.

Interpreter can take much longer; the 60-second interval bounds this diagnostic comparison and does not establish how long every valid load should take. Do not cycle through the other graphics choices for this test.

If the whole Android app closes:

1. Reopen the app. A captured Java exception should lead to **PD2 Android recovery**.
2. Choose **Export crash details**, save/share `crash.txt`, and upload it to the chat. Include whether it closed before or after pressing Play.
3. **Retry launcher** lets you try again and retains the report. If the launcher becomes accessible, **Export support logs** provides the full diagnostic ZIP, including that report when present.
4. If recovery never appears or no report can be shared, report that exact behavior. Some process exits cannot be captured by the Java handler. Do not uninstall or clear storage to retry.

The preview uses the Box64 Stability preset and captures Wine exception/module-load output. Support export includes `launch.json`, `installation-files.json`, the current `runtime.log`, and up to four archived attempts under `attempts/`. These record settings, client-file inventory, and runtime process exit status. The active log is bounded at 8 MiB and each archived attempt at 2 MiB. This is diagnostic evidence, not proof that the stack overflow has been corrected.

The compatibility profile explicitly uses Wine's built-in DirectDraw. The old 0.1.1 DirectDraw profiles could still use the imported D2GL wrapper. This first test is for client display; native controller features may depend on D2GL and are not qualified by a built-in DirectDraw success.

## After PD2 reaches the menu

Use your complete, updated **English classic Diablo II + Lord of Destruction + Project Diablo 2** installation. Preserve your working Winlator/GameNative copy and offline-save backup.

Report that the menu appeared and export the result before pursuing graphics/controller changes. Runtime preparation and import have already passed; this launch qualification does not require repeating them. The controls and save checklist below remains the later milestone, after an appropriate PD2/D2GL graphics path is established.

Export support information after the Interpreter result before changing graphics settings. Further renderer comparisons should follow diagnosis of that result; do not cycle through all four settings before sending the evidence.

## Native controller

Use a temporary offline character for the first gameplay test, enabling PD2's controller support/settings where required.

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

The 0.1.2 local verification passed 13 Android 13/API 33 Robolectric tests and 26 session-log host checks. The ARM64 APK build also passed with the same application ID and signing certificate. These existing results cover launcher/service/recovery behavior, diagnostic policy/reporting, PE inventory, and log limits; they do not run PD2 or confirm a fix on the Thor. This evidence-only documentation update introduces no new build or CI result. The next physical test is the Interpreter comparison above.
