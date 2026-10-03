# 0.1.1 startup retest and Thor qualification

Use the 0.1.1 startup correction and diagnostics preview. The original 0.1.0 opening crash has not been diagnosed from device logs; this retest comes before runtime preparation or gameplay.

## Launcher startup first

1. Install 0.1.1 **over the existing app**. Keep it installed and keep its storage; the same application ID and signing identity preserve private runtime/game files.
2. Open PD2 Android and leave the launcher visible for **five seconds**. Do not press Prepare runtime or import yet.
3. Close the app from Android's recent apps, reopen it, and leave the launcher visible for another **five seconds**.
4. Report whether both opens remained stable. Continue below only if they did.

If it closes again:

1. Reopen the app. A captured Java exception should lead to **PD2 Android recovery**.
2. Choose **Export crash details**, save/share the `crash.txt` file, and upload it to the chat. Include whether it closed immediately or after the launcher appeared.
3. **Retry launcher** lets you try again and retains the report. If the launcher becomes accessible, **Export support logs** provides the full diagnostic ZIP, including that report when present.
4. If recovery never appears or no report can be shared, report that exact behavior. Some process exits cannot be captured by the Java handler. Do not uninstall or clear storage to retry.

## After startup passes

Use your complete, updated **English classic Diablo II + Lord of Destruction + Project Diablo 2** installation. Preserve your working Winlator/GameNative copy and offline-save backup.

1. Press **Prepare runtime** only if the runtime is not already ready. Wait for completion with the app visible; the first Play creates the Wine prefix.
2. If an accepted installation already exists, reuse it. Otherwise import the complete Diablo II folder through the folder picker, or import a ZIP containing that whole installation. Do not select only `ProjectD2`.
3. Confirm the installation reports ready. In **Launch settings**, start with **Turnip + Zink · D2GL / Glide** (`-3dfx -w`). Input starts in **Native controller**.
4. Press **Play** and note the time to reach the main menu. Confirm the display, sound, and cursor are correct.

If the game exits or rendering fails, export support information before changing settings. Try Turnip/VirGL with the same launch arguments; if needed, try DirectDraw (`-ddraw -w`). Restart the game after changing runtime/launch settings, and record which combination was used. Do not repeat a successful import simply to test another renderer.

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
