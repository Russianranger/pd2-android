# 0.1.2 launch diagnostics and Thor qualification

Launcher startup, runtime preparation, and installation import were accepted on 0.1.1. The user reported that all four graphics launch profiles returned to the launcher. The surviving final-attempt log records a Wine stack overflow; its cause is unconfirmed. Use this preview for one controlled launch with improved evidence collection.

## Next launch test

1. Install 0.1.2 **over the existing app**. Keep its storage; the same application ID and signing identity preserve private runtime/game files.
2. Reuse the accepted runtime and game installation. **Do not press Prepare runtime or re-import.**
3. In **Launch settings**, choose **Turnip + Zink · Wine DirectDraw (compatibility)**. Leave **CPU mode → Stability (default)** selected for the first attempt.
4. Press **Play once**. Report whether a game window or PD2 menu appears, the last screen visible, and roughly how long it takes to return if it fails.
5. If it returns to the launcher, immediately use **Export support logs**, save/share the ZIP, and upload it to the chat. Keep this graphics profile; testing all four choices again is unnecessary.

If that attempt also fails, one optional comparison uses **Launch settings → CPU mode → Interpreter (diagnostic; very slow)** with the same Wine DirectDraw profile. Press Play once and allow up to **60 seconds** for the title screen. If it remains unfinished, use the quick menu to return to the launcher and press **Stop client**, then export another support ZIP. Record whether it returned, displayed the title, or was stopped at the limit. Interpreter can take much longer; this interval bounds the diagnostic test and does not establish how long every valid load should take. Return CPU mode to **Stability (default)** afterward.

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

Export support information after any new failure before changing settings. Further renderer comparisons should follow diagnosis of the first 0.1.2 attempt; do not cycle through all four settings before sending that evidence.

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

The 0.1.2 local verification passed 13 Android 13/API 33 Robolectric tests and 26 session-log host checks. The ARM64 APK build also passed with the same application ID and signing certificate. These checks cover launcher/service/recovery behavior, diagnostic policy/reporting, PE inventory, and log limits; they do not run PD2 or confirm a fix on the Thor. Published GitHub Actions verification remains pending until its run is inspected, and the next physical test remains the compatibility launch described above.
