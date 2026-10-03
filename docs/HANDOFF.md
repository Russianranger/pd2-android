# PD2 Android handoff

## Scope and current state

The user requested the first milestone of a dedicated Project Diablo 2 Android app in `Russianranger/pd2-android`: import an existing working installation, play using PD2's own controller support, and switch to mouse/keyboard input from a quick menu. The primary target is the AYN Thor Max, Android 13, Snapdragon 8 Gen 2/Adreno 740, ARM64, 16 GB RAM.

The initial implementation uses Winlator 11.2's embedded Wine 10.10/Box64 0.4.4 runtime and Android display/input components. Java/JNI keeps its upstream `com.winlator` namespace; the distinct installed application ID is `com.pd2.thor`.

Runtime archives are deterministically relocated from the original `com.winlator` data paths to the equal-length `com.pd2.thor` identity, including embedded binary paths. This is runtime packaging, not a change to imported PD2 binaries. Preserve this relocation when updating the donor runtime; merely changing the Android application ID is insufficient.

The current APK remains **0.1.2, a diagnostics and stability preview**. The user confirmed that 0.1.1 opens, prepares the runtime, and imports their installation on the Thor. Preserve those accepted steps. The 0.1.2 Stability/Wine DirectDraw compatibility attempt also failed. Its new diagnostics locate the first captured game fault in native `Fog.dll`, but the underlying cause remains unconfirmed; gameplay is not qualified. This update records evidence and next steps only, with no new APK, code, version, or CI result.

The next test uses the existing 0.1.2 app: keep **Turnip + Zink · Wine DirectDraw (compatibility)** and select **Launch settings → CPU mode → Interpreter (diagnostic; very slow)**. Press Play once, allow at most 60 seconds for the title, then stop/export the result and restore Stability. Do not uninstall, clear storage, prepare the accepted runtime again, re-import the accepted game files, or repeat the failed Stability attempt.

## 0.1.2 device failure: native Fog stack overflow

The new support ZIP confirms that the intended policy was applied: `cpuPreset: STABILITY`, `interpreter: false`, `BOX64_DYNAREC=1`, and built-in `ddraw=b`, using Turnip/Zink with `-ddraw -w`. This was a genuine compatibility attempt, not the earlier native-first DirectDraw choice.

The game thread loaded native `Fog.dll` at `0x6FF50000`. Its first captured fault is at `0x6FF6879A`, which is `Fog.dll + 0x1879A`: a write to `0x00121FFC` with 32-bit `ESP=0x00122004`. Wine dispatches `EXCEPTION_STACK_OVERFLOW` (`0xC00000FD`) and reports a 32-bit stack range `0x00120000–0x00220000`, totaling 1 MiB. The module trace shows no `ProjectDiablo.dll` load before that fault.

The fault location and stack-overflow classification are established for this captured attempt. The exact Fog function and whether the failure reflects recursion, legitimate stack consumption, or CPU-translation behavior are not established. Earlier RPC exceptions on other threads and directory/status warnings have not been shown to cause this game-thread failure. Do not delete imported files, increase the game stack, replace DLLs, or claim a renderer/translator fix from these observations.

`launch.json` records runtime exit status 0 despite the captured game fault. That outer runtime result does not mean the Windows game started successfully. See the summary-only [device evidence note](evidence/2026-10-03-fog-startup.md).

## 0.1.1 device evidence and 0.1.2 response

The user's support ZIP records Android 13 on an AYN Thor, `runtimePrepared: true`, and a structurally accepted installation with launch path `Diablo2/ProjectD2/Game.exe`. `launcher.log` records all four graphics/argument attempts. The user reports that each returned to the launcher.

Only the final **Turnip/VirGL + DirectDraw** attempt's runtime log survived because the prior logger restarted the same file for every session. It reaches the imported `Game.exe` through Wine's 32-bit/WoW64 path and ends with:

```text
wine: Unhandled stack overflow at address 6FF6879A (thread 00e0), starting debugger...
```

The surviving 0.1.1 log ends before any logged OpenGL-library initialization, and Wine's detailed output was suppressed with `WINEDEBUG=-all`. That earlier evidence did not identify the faulting game DLL, establish that all four attempts had the same exception, or prove a graphics-driver cause. The new 0.1.2 trace above identifies native Fog as the first captured fault location; its underlying cause remains unconfirmed.

All four 0.1.1 choices used `WINEDLLOVERRIDES=ddraw,glide3x=n,b`; the DirectDraw options could still load the imported native D2GL wrapper. They therefore did not establish a built-in Wine DirectDraw baseline.

The 0.1.2 response:

- Uses Box64's **Stability** preset instead of **Conservative**, with **Launch settings → CPU mode** offering **Stability (default)** and **Interpreter (diagnostic; very slow)**.
- Makes both DirectDraw choices use Wine's built-in `ddraw=b` without editing imported files; Glide keeps the native wrapper.
- Captures `WINEDEBUG=-all,err+all,warn+all,+seh,+loaddll` and `BOX64_SHOWSEGV=1` for exception/module-load evidence.
- Exports the latest launch record as `launch.json` and selected-file inventory as `installation-files.json`, with settings and runtime process exit status.
- Retains the latest four attempt logs under `attempts/`, each at most 2 MiB in support export. The live runtime log is capped at 8 MiB.

These changes make failures distinguishable without repeating import/setup or cycling through all renderers before collecting evidence. The first compatibility test qualifies client appearance only; using Wine's built-in DirectDraw may bypass D2GL/controller features, which need their own later test. CPU profiles and wrapper selection are compatibility candidates, not proven remedies or performance optimizations.

Interpreter is now the next diagnostic attempt because the Stability compatibility test failed. Allow up to 60 seconds for the title screen. If it remains unfinished, return to the launcher, stop the client, export that result, and restore Stability. If the menu appears, record that observation, then stop/export before pursuing gameplay. This is a bounded diagnostic interval, not proof that a valid interpreter launch must complete within 60 seconds.

## Startup correction and diagnostics

- Automatic installation validation runs on the existing worker without starting `Pd2WorkService`.
- Explicit import/export jobs wait until the service's `onStartCommand`, after `onCreate` has promoted it to foreground, before work can finish and stop the service. This removes a possible service-start/stop race; it does not identify the original Thor crash cause.
- `Pd2Application` installs Java uncaught-exception capture during `attachBaseContext`, before manifest content providers are created, then delegates the original exception to Android's handler.
- The last Java exception is written to `pd2/logs/crash.txt`, bounded at 128 KiB. A separate pending marker routes the next launch to recovery.
- Platform entry/recovery activities avoid initializing the AppCompat launcher and game runtime on the recovery path. **Export crash details** shares the report; **Retry launcher** removes the pending marker while retaining the report.
- Normal **Export support logs** includes `crash.txt` when present, in addition to its existing device, exit-history, and runtime logs.

Crash capture covers uncaught Java exceptions when the process can write its report. Native crashes, abrupt process kills, and storage failures may leave no recovery report. If the launcher still closes without recovery, obtain the user's exact observation and any support export available before choosing another fix.

## Implemented design

- A dedicated launcher manages a single PD2 prefix, prepares bundled runtime files, and exposes Play/Resume.
- Complete folder and ZIP imports are staged in private storage, validated, then accepted. A failed import preserves the previous installation. The game must be stopped before importing.
- A successful replacement retains `install.previous` until the next accepted replacement. This is recovery storage, not a user-facing save-merge or backup manager; the replacement's supplied saves become its own saves.
- The managed `P:` drive points to the imported installation. The selected `ProjectD2/Game.exe` is launched with its directory as the working directory.
- The dedicated Wine prefix seeds Diablo II's `InstallPath` and `GamePath` registry keys from the validated base/client directories so imported PD2 can find its base installation.
- Structural validation looks for a 32-bit x86 PE `Game.exe` and `ProjectDiablo.dll`, PD2 data, and the required base MPQ archive headers. It is bounded and read-only; it is not full file-integrity or version verification.
- The initial screen size is 1280×720. Runtime options expose Turnip/Zink and Turnip/VirGL; Glide uses `-3dfx -w` with the imported native wrapper, while Wine DirectDraw compatibility uses `-ddraw -w` with built-in `ddraw=b`. The first 0.1.2 test is Turnip/Zink with Wine DirectDraw.
- Physical-controller input uses the Windows gamepad bridge by default. The mouse/keyboard fallback is a fixed PD2-oriented layout with adjustable cursor speed and stick deadzone.
- The on-screen gear and L3 + R3 open a quick menu for input switching, keyboard access, and returning to the launcher. Held input is released at mode/menu/lifecycle transitions.
- Game DLLs are imported as supplied. No custom PD2/BH patching is part of this milestone.
- **Export support logs** shares a ZIP with device/runtime/installation details, recent Android process-exit information, and bounded log tails. It is diagnostic export, not game/save backup.

## Verification boundary

The initial source checks passed with JDK 17:

- `./scripts/test-import.sh`: 47 checks, including unsafe ZIP rejection, ZIP64, synthetic PE/MPQ structure validation, replacement preservation, and interrupted-promotion recovery.
- `python3 tests/test_input_router.py`: tests the actual fallback router with small Android/X-server stand-ins; pointer/key release, shared input sources, direction reversal, analog/digital triggers, drift, alternate axes, and timer cleanup passed.
- `python3 tests/test_crash_recovery.py`: 11 checks passed for the production crash-store/handler code, including bounded valid UTF-8 output, pending-marker acknowledgement, report retention, and delegation when report writing fails. Android metadata is represented by JVM stand-ins.

The 0.1.1 startup pass also verified:

- `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.winlator.pd2.Pd2*Test'`: all six Robolectric 4.14.1 Android 13/API 33 tests passed, with no failures, errors, or skips. They cover the actual manifest/themed launcher, both platform entry routes, recovery fallback and retry report retention, and foreground notification before protected jobs.
- The original published `ebcd311` source was tested in isolation with its actual manifest. Its UI opened, but the startup regression assertion failed because it requested `Pd2WorkService`. This confirms the behavior changed; it does not reproduce Android's foreground-service watchdog or the user's device crash.
- The ARM64 0.1.1 APK build passed with version code 2, the unchanged `com.pd2.thor` application ID, and the same preview signing key.

The completed local 0.1.2 checks are:

- `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.winlator.pd2.Pd2*Test'`: all 13 Android 13/API 33 Robolectric tests passed, with no failures, errors, or skips. The previous six startup/service/recovery tests passed alongside seven new diagnostic checks for launch policy, PE inventory, exit-status reporting, and bounded logs.
- `python3 tests/test_session_logs.py`: 26 host checks passed for attempt-log retention, size bounds, preservation, and cleanup behavior.
- The ARM64 APK build passed in 22 seconds as version 0.1.2/code 3. Application ID, signing certificate, minimum SDK 26, and target SDK 28 are unchanged. Runtime dependency sources/archives and the native libraries are unchanged from 0.1.1.

These record the completed local 0.1.2 results. This evidence-only documentation update does not rebuild the APK or report a new CI result; local checks do not establish a published GitHub Actions result.

Robolectric models Android framework/resources in the JVM; it does not qualify the physical Thor, Android's service watchdog, ARM64 native-library loading, Wine/controller forwarding, graphics drivers, or PD2. The standalone source checks use small stand-ins and do not execute Android lifecycle behavior. Synthetic PE/MPQ fixtures do not establish compatibility with a real installation. The user has now accepted launcher startup, runtime preparation, and import on 0.1.1; the original 0.1.0 startup-crash cause and the current Windows-client stack-overflow cause remain unconfirmed.

The existing 0.1.2 APK contains the next Interpreter diagnostic option. Preserve the accepted steps and continue from the failing client launch:

| Gate | Status | Evidence or next requirement |
| --- | --- | --- |
| Launcher startup | Accepted on 0.1.1 | User opened the launcher and completed setup/import |
| Runtime preparation | Accepted on 0.1.1 | User report and `runtimePrepared: true` in support ZIP |
| Installation import | Accepted on 0.1.1 | User report, import-completed log, and structural validation details |
| Client launch | Failed on 0.1.1 and 0.1.2 Stability compatibility | New trace locates the first game-thread stack overflow in native Fog; Interpreter comparison pending |
| Rendering/audio | Pending | PD2 must reach a playable scene with correct textures, UI, and audio |
| Native controller | Pending | Left-stick movement, independent right-stick aiming, triggers/buttons, and controller UI on the Thor |
| Input switching | Pending | Gear/chord and repeated mode switches leave no held input |
| Lifecycle | Pending | Return to launcher and Resume preserve the same session; background/foreground verified |
| Offline saves | Pending | Save, exit, stop, relaunch, and reopen the same character |
| Online play | Pending | User authenticates and enters a normal PD2 online game |

Reaching the PD2 menu is the immediate qualification step. Native controller forwarding follows once gameplay is accessible. Keyboard emulation working is not evidence that native movement/aiming works. All original rendering, controller, lifecycle, save, and online gates remain pending.

## Next work after the first device test

Investigate failures from the exported support bundle and the user's exact renderer/launch/input settings. Preserve accepted tests and the current architecture; fix the failing layer before adding unrelated features.

Once the app matches the existing working setup, capture baseline startup time, frame stability, crowded-combat behavior, input latency, temperature, and power use. Compare configurations one at a time before claiming optimization gains.

PD2 updates, loot-filter management, broader device presets, production signing, and performance tuning are follow-up work. Do not replace the official PD2 DLLs or add these managers as part of first-milestone qualification.
