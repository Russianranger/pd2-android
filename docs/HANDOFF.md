# PD2 Android handoff

## Scope and current state

The user requested the first milestone of a dedicated Project Diablo 2 Android app in `Russianranger/pd2-android`: import an existing working installation, play using PD2's own controller support, and switch to mouse/keyboard input from a quick menu. The primary target is the AYN Thor Max, Android 13, Snapdragon 8 Gen 2/Adreno 740, ARM64, 16 GB RAM.

The initial implementation used Winlator 11.2's embedded Wine 10.10/Box64 0.4.4 runtime and Android display/input components. The accepted current baseline is Wine 9.2 (Custom) with Box64 0.4.4. Java/JNI keeps its upstream `com.winlator` namespace; the distinct installed application ID is `com.pd2.thor`.

Runtime archives are deterministically relocated from the original `com.winlator` data paths to the equal-length `com.pd2.thor` identity, including embedded binary paths. This is runtime packaging, not a change to imported PD2 binaries. Preserve this relocation when updating the donor runtime; merely changing the Android application ID is insufficient.

The current implementation is **0.1.9, a native foreground-recovery preview**, version code 10. The user accepted native input inside a character on 0.1.6, rejected the 0.1.7 Menu cursor after Save/Exit, then accepted Menu cursor on 0.1.8 while reporting that Native remained unresponsive after Save/Exit. No new matching 0.1.8 support bundle accompanies that report. Native title/character-menu support and recovery after in-character re-entry must be qualified separately; the root cause remains unconfirmed.

Install over the existing app and reuse Wine 9.2/rootfs 24/prefix/import: **no Prepare runtime or re-import**. Keep GameNative-arguments Glide, Stability, and controller notifications enabled. See [Testing](TESTING.md) and the [foreground-recovery evidence note](evidence/2026-10-04-native-foreground.md).

## 0.1.9 scope

- Restore Game.exe Windows foreground focus when Native input resumes after a mode/menu/lifecycle gate, using the existing Windows helper. Coalesce map/resize events belonging to the mapped game subtree with a 75 ms delay, then request one settling restore 250 ms later. Each stage permits at most eight helper-readiness checks.
- Resolve the current game HWND for each request. Cancel queued focus work when Native loses ownership or a newer game-window transition supersedes it. Menu cursor's existing focus request now also expires when its mode loses ownership. No pointer/key input is injected by Native recovery.
- Add `controller.json.nativeFocusRecovery` with `requests`, `lastRequestAt`, `lastReason`, and `scope`. Reasons are `route`, `window`, and `settle`; these count queued requests, not Windows acceptance or PD2 consumption.
- Keep the same accepted HID backend/device and input forwarding; no device replug or runtime/prefix change is introduced.
- Retain accepted Menu cursor navigation and its pointer diagnostics. Native support in PD2's title/character menus is not established by existing evidence.
- Add the requested fiery PD2 icon with a devilish skull, including an adaptive launcher icon. Apply a dark full-screen session theme and dark gear menu/dialog surfaces.
- Retain Wine 9.2, `wine9-hid-1`, rootfs 24, prefix `wine-9.2-pd2-1`, graphics settings, imported PD2 files/saves, and signing identity.

Local 0.1.9 verification passed:

- All 93 Android 13/API 33 Robolectric tests across 14 suites passed with zero failures, errors, or skips.
- Nine real-router scenarios, 12 menu-pointer/native-focus scenarios with 156 checks, 47 import checks, 11 crash-recovery checks, and 749 session-log checks passed.
- All 24 runtime tests and three native-controller tests passed; 206 final dependency hashes, 49 relocated assets, and controller source/artifact/ABI checks verified.
- ARM64 assembly completed in 1 minute 35 seconds. APK identity is `com.pd2.thor`, 0.1.9/code 10, min SDK 26, target SDK 28. V2 signature verification passed with the same preview certificate as 0.1.8; ZIP integrity and alignment passed.
- All 72 packaged runtime assets match the verified 0.1.8 APK byte-for-byte, including the accepted Wine/HID assets.
- Of 35 Android native libraries, 26 match byte-for-byte; eight rebuilt libraries differ only in their GNU build IDs. VirGL's rebuilt differences are string/address placement after the scratch build path changed: normalized strings and referenced targets match, without opcode/register/control-flow changes. No native source changed.
- APK size: 174,020,117 bytes. SHA-256: `e6b1a0215b3502b3fe6fc57d915bdeb1d66b136db89720cde65162c9db5d9e16`.

Published 0.1.9 CI and physical-device qualification remain pending. The physical check distinguishes Native at the title/character menus from Native gameplay after re-entry, then checks a repeated cycle and gear/launcher resume. See [0.1.9 release notes](RELEASE-0.1.9.md).

## 0.1.8 scope

- Force the app's root cursor image only while Menu cursor owns active input. A hidden game cursor can no longer suppress that menu pointer; native/full fallback rendering is unchanged.
- Route Menu mouse movement, button clicks, and the supported navigation keys through the existing verified Windows helper. Reacquire Game.exe on menu activation; use current mapped game-window geometry for centering and bounds. Windows cursor feedback is authoritative; no duplicate X11 motion is injected.
- Keep at most one outstanding menu movement, use a bounded feedback timeout, and invalidate queued presses/moves on mode or focus loss. Balance held controls on release. Existing unguarded runtime input APIs preserve their behavior.
- Record handled input per mode, menu output request counts/times and returned Windows cursor feedback separately, plus up to 16 filtered geometry/cursor contexts. No key codes, axis values, titles, game binaries or saves are exported in these fields.
- Preserve the accepted Wine/HID backend and all runtime assets. Do not change aspect-ratio math or modify PD2 DLLs.

Local 0.1.8 verification passed: 86 Android 13/API 33 Robolectric tests across 13 suites, with no failures, errors or skips; 9 real-router scenarios; and 11 menu-pointer scenarios with 145 checks. These include the actual mouse/keyboard datagram layout, queued-action expiry, fresh Windows cursor synchronization, lost-feedback retry, resize/clipping, and balanced controls. ARM64 assembly and APK identity/signature checks passed: `com.pd2.thor`, 0.1.8/code 9, min SDK 26, target SDK 28, unchanged preview certificate. All 35 Android native libraries and 72 runtime assets match the verified 0.1.7 APK byte-for-byte, including the accepted HID module. APK size: 171,307,574 bytes. SHA-256: `53cec30a4ee97ef6bb30453ee694c618631572eb853c86dfcc2944e2e4d4c550`. Packaging drift rejection and correct-version artifact fixtures passed. [Published 0.1.8 CI run 37172243169](https://github.com/Russianranger/pd2-android/actions/runs/37172243169) passed. The user subsequently accepted Menu cursor but reported that Native still did not respond after Save/Exit. This does not establish whether Native was also tested after character re-entry.

## 0.1.7 scope

- Add temporary, manually selected **Menu cursor**: either stick moves the pointer (right priority), A clicks, B/Select Esc, Start/R3 Enter, L3 Tab, D-pad arrows. Native forwarding and the full Mouse / keyboard layout remain separate. Return to Native after entering the character; menu mode is not persisted for the next launch.
- Release held inputs on route changes and pause forwarding while the quick menu/fallback dialogs are open. No automatic menu detection, resolution change, or imported DLL patch is introduced.
- Continue live recording after the old cap: compact at 8 MiB, keeping the first approximately 512 KiB and latest 4 MiB under a shared capture lock. Export/archive snapshots remain at most 2 MiB each, with startup/tail sections, capture metadata, and omission markers.
- Remove per-report HID tracing while retaining XInput/raw-input diagnostics. Add up to 32 recent mode/input-gate transitions with timestamps, current focus/pause/menu/drawer gates, and last handled input/reply times. Count pointer-routed input too; `support.json` identifies its input mode as a saved preference, while `controller.json` describes the current session.
- Preserve Wine 9.2, `wine9-hid-1`, rootfs 24, prefix `wine-9.2-pd2-1`, graphics configuration, imported files/saves, and signing identity.

Final local tests, ARM64 assembly, identity/signature, and payload checks passed. [Published 0.1.7 CI](https://github.com/Russianranger/pd2-android/actions/runs/37169294205) passed. The user subsequently rejected the physical menu test; re-entry remains unqualified. See the [menu-transition evidence](evidence/2026-10-04-menu-transition.md).

## 0.1.6 HID notification scope

- Add targeted Wine 9 Unix `winebus.so` revision `wine9-hid-1` using the matching 17-entry Wine 9 ABI, plus Java's 256-byte producer on UDP 7950. Keep the existing 7949/64-byte legacy XInput path. Both expose the first selected pad; HID identity/layout are fixed and cannot be changed by model/mapper preferences.
- Install before Play with a pinned original hash, verified backup, staged verification, and atomic replacement. Unknown runtime bytes are rejected. Notifications default on; disabling restores the verified original on the next fresh launch after force-stop/reopen.
- Restore PlugPlay Start 2 and RpcSs Start 3 while retaining other Essential service settings. Keep private-prefix `Enable SDL=1`; set `DisableHidraw=1` while notifications are enabled and 0 while disabled, resolving the actual CurrentControlSet alias.
- Preserve Wine 9.2, rootfs 24, prefix revision `wine-9.2-pd2-1`, imported PD2 files/saves, and application/signing identity. The change replaces only Wine's Unix controller backend; no PD2 DLL patch or prefix migration is performed.
- Record `nativeBridge=legacy_xinput_7949_and_hid_7950`, `bridgeRevision=java-hid-7950-v1`, separate HID discovery/device/state counters, and backend install/enable/revision status.

Local Java/native/source checks and final ARM64 APK verification passed. [0.1.6 CI run 37167615176](https://github.com/Russianranger/pd2-android/actions/runs/37167615176) succeeded. The user subsequently accepted native input inside a character; the [preceding evidence](evidence/2026-10-04-controller-rawinput.md) records the 0.1.5 failure.

## 0.1.5 device evidence

The matching report accepts Android device 92, `Xbox Wireless Controller`, with 736/736 motion and 16/16 key events handled. It records 58 legacy discovery requests/replies and 859 state replies without reply failures. Wine loads built-in `XINPUT1_4.dll`; PD2 registers raw input for page 1/usages 4 and 5 with flags `0x2100`, then logs four initial GetState calls for indices 0–3 and no later calls. This establishes Java-path activity, not PD2 activation. Final socket/availability flags describe snapshot time and must not replace those accumulated observations.

## 0.1.5 controller scope

- Send controller discovery/state replies and press/release pushes when the socket is ready, independently of `winhandler.exe` INIT. Process/runtime actions still wait for INIT. This removes a source-level controller-response gate without proving it caused the device report.
- Accept capability-qualified `uinput` gamepads/joysticks instead of rejecting their names wholesale. Fingerprint devices (`uinput-fpc`/`goodix_fp`) and Android virtual devices remain excluded. The actual Thor device identity is not present in the 0.1.4 bundle, so filtering is not a verified device cause.
- Add quick-menu **Controller status** for accepted pads, aggregate button/stick events, and discovery/state replies. Opening the menu pauses gameplay input; the display is diagnostic, not proof that PD2 consumed replies.
- Export `controller.json`, bounded to 64 KiB and 32 Android devices, and record `inputMode` in `support.json`. The snapshot records capabilities, selected device, route/availability, socket/INIT status, and aggregate request/reply/input counts; it excludes current keys, axis values, and device descriptors.
- Match its `launchId` to `launch.json`, clear the prior report when a launch begins, and reject stale/invalid reports during saving and export. A previous session's counters must not describe a new launch.
- Replace the Fog `+snoop` channel after accepted startup with `+xinput,+rawinput`, retaining error/warning/exception/module-load logging.
- Keep Wine 9.2, rootfs 24, revision `wine-9.2-pd2-1`, runtime assets, prefix, imported game files/saves, and application/signing identity unchanged. There is no new runtime preparation or prefix migration.

Local 0.1.5 tests/build and [CI run 37162841712](https://github.com/Russianranger/pd2-android/actions/runs/37162841712) passed for commit `f9f0618`. Its subsequent device result establishes Android discovery/input/replies while native activation remains absent. The [earlier controller evidence note](evidence/2026-10-03-controller-detection.md) records the preceding 0.1.4 report.

## 0.1.4 runtime comparison

- Wine 9.2 (Custom), its complete `opt/wine` tree, prefix template, and common DLLs come from the official Winlator 10.1 APK. Box64 0.4.4, current graphics components, and the other rootfs dependencies are retained. This is not GameNative's Bionic Proton 9.0 runtime.
- Rootfs version 24 requires runtime preparation after the upgrade. Managed runtime revision `wine-9.2-pd2-1` creates/selects a fresh prefix on first Play. Previous `xuser-N` prefixes are retained but not selected; the entire private `pd2` tree, including the accepted installation and its saves, is preserved.
- The existing fifth launch choice retains `-3dfx -dxnocompatmodefix` without `-w`. Selected settings from the 0.1.3 attempt persist; explicitly confirm that choice and Stability for the comparison.
- The 0.1.4 support/launch records identified the Wine version, rootfs version, and runtime revision, with Fog export tracing and bounded game/file diagnostics.
- The Wine 9.2 controller DLLs use the older UDP bridge: XInput 7949 and DirectInput 7948 communicate with Java 7947. The matching Java codec is selected only for `wine-9.2-custom` on those ports; other runtimes retain the modern bridge. This baseline exposes one active gamepad, digital XInput triggers, and no rumble. Binary/source protocol verification and focused checks passed; physical Thor controller behavior remains unqualified.

Application/signing identity and SDK levels were unchanged. Local 0.1.4 checks and [CI run 37153029246](https://github.com/Russianranger/pd2-android/actions/runs/37153029246) passed for commit `3537161`; its physical title-startup test was accepted. The runtime baseline is retained for the controller work.

## 0.1.3 device trace

The fifth-profile attempt reached `CALL Fog.10021`, returned `0x17` (23) to `0x0040829B`, then called `Fog.10019` with caller return address `0x004082B1`. No return from 10019 is recorded before the same `Fog.dll + 0x1879A` guard-page write and stack overflow. This narrows the failing initialization interval without establishing its internal call chain or recursion. Matching the arguments alone did not resolve it. See the [evidence note](evidence/2026-10-03-fog-startup.md).

## 0.1.3 diagnostic scope

- The fifth choice uses `-3dfx -dxnocompatmodefix` without `-w`, with the imported native wrapper and Stability. This matches arguments only; the app does not replace its runtime with GameNative's Bionic Proton. The D2DX compatibility flag is a later wrapper option, not an explanation for the early Fog stack fault.
- In 0.1.3, Wine `+snoop` was enabled with a written-and-verified `Fog.*` registry filter. It recorded native export ordinals/arguments and caller return addresses, without establishing internal recursion or a complete stack. Box64 0.4.4's x64-only `SHOWBT` was not used as an x86-game trace. The 0.1.5 policy replaces this with controller tracing.
- `launch.json` includes selected persisted `InstallPath`, `GamePath`, Wine version settings, and `SnoopInclude`; full registry hives are not exported.
- `installation-files.json` adds PE stack reserve/commit sizes and SHA-256 for `Game.exe`, `Fog.dll`, and `PD2_EXT.dll` when at most 8 MiB. Larger files receive a bounded hash error; no binaries or saves are exported.
- `game-logs/` includes the newest two dated D2 logs plus `d2dx_log.txt`/`d2gl.log` when present, each capped at 256 KiB with head/tail preservation. Symlinks are excluded.

For 0.1.3, the application ID, signing identity, SDK levels, runtime archives, and imported installation were preserved. That pass added diagnostics and one launch comparison, with no performance claim or native-controller qualification. Its local tests and APK build passed; that did not establish a physical startup fix.

## 0.1.2 device failure: native Fog stack overflow

The new support ZIP confirms that the intended policy was applied: `cpuPreset: STABILITY`, `interpreter: false`, `BOX64_DYNAREC=1`, and built-in `ddraw=b`, using Turnip/Zink with `-ddraw -w`. This was a genuine compatibility attempt, not the earlier native-first DirectDraw choice.

The game thread loaded native `Fog.dll` at `0x6FF50000`. Its first captured fault is at `0x6FF6879A`, which is `Fog.dll + 0x1879A`: a write to `0x00121FFC` with 32-bit `ESP=0x00122004`. Wine dispatches `EXCEPTION_STACK_OVERFLOW` (`0xC00000FD`) and reports a 32-bit stack range `0x00120000–0x00220000`, totaling 1 MiB. The module trace shows no `ProjectDiablo.dll` load before that fault.

The fault location and stack-overflow classification are established for this captured attempt. The exact Fog function and whether the failure reflects recursion, legitimate stack consumption, or CPU-translation behavior are not established. Earlier RPC exceptions on other threads and directory/status warnings have not been shown to cause this game-thread failure. Do not delete imported files, increase the game stack, replace DLLs, or claim a renderer/translator fix from these observations.

`launch.json` records runtime exit status 0 despite the captured game fault. That outer runtime result does not mean the Windows game started successfully. See the summary-only [device evidence note](evidence/2026-10-03-fog-startup.md).

The subsequent Interpreter bundle confirms `interpreter: true` and `BOX64_DYNAREC=0`, yet fails at `Fog.dll + 0x1879B` with the same write to `0x00121FFC` and 1 MiB stack. It returned in about nine seconds with outer status 0. This does not support attributing the fault solely to the dynamic recompiler. The working GameNative screenshots show Bionic Proton 9.0 x86_64, Box64 0.3.7 Performance, Turnip `25.3.0_R3_Gmem`, WineD3D Vulkan, and `-3dfx -dxnocompatmodefix`. Windows version and environment settings are not visible; do not infer them or an unseen FEX setup.

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

The Interpreter and subsequent 0.1.3 fifth-profile comparisons were completed and failed as recorded above. Preserve their results rather than repeating them on Wine 10.10.

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
- The initial screen size is 1280×720. Runtime options expose Turnip/Zink and Turnip/VirGL; standard Glide uses `-3dfx -w`, and the fifth Glide choice uses `-3dfx -dxnocompatmodefix`, both with the imported native wrapper. Wine DirectDraw compatibility uses `-ddraw -w` with built-in `ddraw=b`.
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

The completed local 0.1.3 checks are:

- All 20 Android 13/API 33 Robolectric tests passed with no failures, errors, or skips. They include PE stack fields, hash bounds, allowed game-log exports and symlink exclusion, and persistence of the fifth launch choice.
- The 47 import checks, 11 crash-recovery checks, 26 session-log checks, and input-router checks passed again.
- The ARM64 APK build passed in 1 minute 51 seconds; all 205 final runtime dependency hashes and 49 relocated assets passed verification. Version 0.1.3/code 4 retains `com.pd2.thor`, min SDK 26, target SDK 28, and the same preview signing certificate.
- APK size: 172,992,519 bytes. SHA-256: `5124f9b7a5560875b2ab5d19d33985da743b26448ccd1ef4005c77fbdd76b03f`.

The 0.1.3 [GitHub Actions run 37151153178](https://github.com/Russianranger/pd2-android/actions/runs/37151153178) succeeded for commit `3fc973`. This does not establish a physical PD2 startup fix.

The completed local 0.1.4 checks are:

- All 39 Android 13/API 33 Robolectric tests passed with no failures, errors, or skips, including fresh-prefix migration/recovery and the Wine 9.2 legacy gamepad protocol.
- The 47 import checks, 11 crash-recovery checks, 26 session-log checks, and input-router checks passed.
- All 24 runtime tests passed: 13 fetch, six composition, and five relocation tests. All 206 final dependency hashes and 49 relocated assets verified.
- The ARM64 APK build passed in 28 seconds. Version 0.1.4/code 5 retains `com.pd2.thor`, min SDK 26, target SDK 28, and the same preview certificate.
- APK size: 171,268,551 bytes. SHA-256: `c1eeb0e0968cfa852f736b62ed4d045d57abeaa336f0973ae768656fdd61e413`.

Published 0.1.4 CI run 37153029246 succeeded. The user subsequently accepted title startup on the Thor; these automated checks do not qualify controller input or identify the exact earlier Fog failure mechanism.

The completed local 0.1.5 checks are:

- All 49 Android 13/API 33 Robolectric tests passed with no failures, errors, or skips, including pre-INIT controller replies, capability discovery, bounded diagnostics, and launch-matched saving/export.
- The 47 import checks, 11 crash-recovery checks, 26 session-log checks, and input-router checks passed.
- The ARM64 APK build passed in 18 seconds. Version 0.1.5/code 6 retains the same application/signing identity and SDK levels; all 35 packaged native libraries and 25 runtime archives match 0.1.4, with no temporary files packaged.
- APK size: 171,401,972 bytes. SHA-256: `cd9f9ed746cd1c67e4417172034aa89c8b993253be1d48b92dc36dbb3639e02a`.

Published 0.1.5 CI run 37162841712 succeeded. Neither framework tests nor payload comparison qualify physical PD2 controller activation.

The completed local 0.1.6 checks are:

- All 76 Android 13/API 33 Robolectric tests passed with no failures, errors, or skips. The 47 import checks, 11 crash-recovery checks, 26 session-log checks, and input-router checks passed.
- Three native controller tests passed, including the production UDP/HID/lifecycle host harness under ASan/UBSan. Alignment checking is excluded for the upstream x86 report writes; LeakSanitizer is unavailable on this host. This does not establish complete Wine/HID/PD2 integration; that host test was blocked.
- The pinned 55 Wine 9 source files and LGPL notice, producer/build source hashes, manifest identity, artifact hash, and exact ABI checks passed. The module exports only `__wine_unix_call_funcs`, with 17 entries and compile-time struct size/offset assertions; it links only `ntdll.so`/`libc.so.6`, requiring at most GLIBC 2.17.
- Final backend: 31,064 bytes, revision `wine9-hid-1`, SHA-256 `541523c1e21059a386cfd60f6f18354c05b28ca2457faf867221911b545c4b0e`.
- ARM64 assembly and signature/version checks passed: `com.pd2.thor`, version 0.1.6/code 7, min SDK 26, target SDK 28, and the same preview certificate.
- All 35 packaged Android native libraries and 70 existing assets match 0.1.5 byte-for-byte. Only the controller backend and manifest assets are added; the final module hash was verified inside the APK.
- APK size: 171,307,488 bytes. SHA-256: `21a5b1095ab254a7f4d5cf60ef9e6364b848fc604bbec58838226c510f796f8b`.

Published 0.1.6 CI run 37167615176 succeeded. Native input inside a character was subsequently accepted by user report. No complete Wine/HID/PD2 integration success is claimed from the host checks alone.

The completed local 0.1.7 checks are:

- All 78 Robolectric tests across 13 suites passed with zero failures, errors, or skips. Eight real-Java-router host scenarios and 749 logging assertions passed.
- ARM64 assembly, identity, and signature checks passed: `com.pd2.thor`, 0.1.7/code 8, min SDK 26, target SDK 28, with the unchanged preview certificate.
- All 35 packaged Android native libraries and 70 baseline assets match the verified 0.1.5 APK byte-for-byte. Both custom controller assets match tracked 0.1.6 source; the `wine9-hid-1` module retains SHA-256 `541523c1e21059a386cfd60f6f18354c05b28ca2457faf867221911b545c4b0e`. No native module change is included.
- APK size: 171,296,970 bytes. SHA-256: `d36d82927bcc7ee745fe7a5ae704545c8cc27d256b15c5a928000fd409c30d1a`.

Published 0.1.7 CI run 37169294205 passed. The user rejected its physical menu test; re-entry remains unqualified.

Robolectric models Android framework/resources in the JVM; it does not qualify the physical Thor, Android's service watchdog, ARM64 native-library loading, Wine/controller forwarding, graphics drivers, or PD2. The standalone source checks use small stand-ins and do not execute Android lifecycle behavior. Synthetic PE/MPQ fixtures do not establish compatibility with a real installation. The user accepted launcher/setup/import, title startup on 0.1.4, and native input inside a character on 0.1.6. The original Android crash, earlier Wine 10/Fog failure mechanism, and current Native failure after Save/Exit remain unconfirmed.

The next physical step tests Native after Save/Exit and character re-entry on the accepted Wine 9.2/HID setup. Runtime preparation and import are not repeated:

| Gate | Status | Evidence or next requirement |
| --- | --- | --- |
| Launcher startup | Accepted on 0.1.1 | User opened the launcher and completed setup/import |
| Runtime preparation | Accepted on 0.1.1 | User report and `runtimePrepared: true` in support ZIP |
| Installation import | Accepted on 0.1.1 | User report, import-completed log, and structural validation details |
| Client launch | Title startup accepted on 0.1.4 | User report; Wine 9.2/current prefix confirmed and Fog 10019 returns |
| Rendering/audio | Pending | PD2 must reach a playable scene with correct textures, UI, and audio |
| Native controller | In-character input accepted on 0.1.6 | User report; detailed independent aiming/buttons/triggers still pending |
| Menu cursor | Accepted on 0.1.8 | User report; retain this navigation path |
| Native after Save/Exit | Unresponsive by latest user report | Test Native title/character menus separately from gameplay after re-entry; cause unconfirmed |
| Input switching | Pending | Gear/chord and repeated mode switches leave no held input |
| Lifecycle | Pending | Return to launcher and Resume preserve the same session; background/foreground verified |
| Offline saves | Pending | Save, exit, stop, relaunch, and reopen the same character |
| Online play | Pending | User authenticates and enters a normal PD2 online game |

The immediate qualification step is Native menu input, then character re-entry using the accepted Menu cursor if necessary, followed by Native gameplay and repeated gear/launcher resume checks. Specific controller actions, rendering/audio, lifecycle, saved progress, and online gates remain pending beyond the accepted in-character input and Menu cursor.

## Next work after the first device test

Investigate failures from the exported support bundle and the user's exact renderer/launch/input settings. Preserve accepted tests and the current architecture; fix the failing layer before adding unrelated features.

Once the app matches the existing working setup, capture baseline startup time, frame stability, crowded-combat behavior, input latency, temperature, and power use. Compare configurations one at a time before claiming optimization gains.

PD2 updates, loot-filter management, broader device presets, production signing, and performance tuning are follow-up work. Do not replace the official PD2 DLLs or add these managers as part of first-milestone qualification.
