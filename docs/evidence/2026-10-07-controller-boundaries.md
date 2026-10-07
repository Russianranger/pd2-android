# 0.1.17 restored-backend failures and 0.1.18 controller boundary diagnostic

## Capture and physical result

The user reports **both Native and Menu cursor failed** after the settings-restoration instructions in [the preceding first-boot report](2026-10-07-first-boot-controller-settings.md). This is a failed physical result. Continuing Android input, UDP delivery, Wine enumeration or API calls must not be reported as functioning gameplay controls.

The locally inspected `pd2-support-20261006-193852.zip` is **328,693 bytes**, SHA-256 `21b3837ad97811d043ab2b47e0d9aecc90343670c173dce3a58d837e15fea646`. It records 0.1.17/code 18, implementation `fde447f8ba4f7d428d4bc5201a6da495ae6dbd93`. Main's preceding documentation checkpoint is `e0adb6732f4e0ceffffb90e4b9e0fe20470d9756`.

The export contains two later launches, plus the earlier notifications-disabled launch and archived accepted 0.1.16 evidence. Archive filenames identify archival time, not necessarily the launch's start. Match `launchId` and `startedAt` before attributing an attempt. Times below are October 7 UTC; the corresponding user-local date is October 6 CDT, five hours earlier.

| Observation | Patched HIDclass launch | Restored original HIDclass launch |
| --- | --- | --- |
| Launch ID | `9b6de987-9038-4ee7-aa7a-84ecf3100f75` | `20b91eb3-afce-4d18-9060-c6ebc4051dd9` |
| Start | 00:35:27.094 UTC / 19:35:27.094 CDT | 00:37:23.799 UTC / 19:37:23.799 CDT |
| Report location | `attempts/launch-attempt-1791333443772.json` | `launch.json` |
| Accepted controller notifications | Enabled, exact accepted Winebus hash | Enabled, exact accepted Winebus hash |
| HID read experiment applied for launch | `true` | `false` |
| AMD64 HIDclass installed SHA-256 | `def30d1b2b6ada06c0ce04d96a8055c67b2f07f2d10f622960ad259f75a64dcb` | `a335d3560f14d5b1e31f90fd765ee6261f43a4d70a1f456fbec805ccf18132bc` |
| Backend Linux PID / socket inode | 31685 / 41220392 | 32156 / 41224349 |
| Producer generation / Android controller ID / HID UID | 1 / 92 / 0 | 2 / 92 / 0 |
| Backend created / removed / started / stopped | 2 / 1 / 2 / 1 | 2 / 1 / 2 / 1 |
| Backend state received / reports queued / invalid packets | 1,595 / 1,252 / 0 | 1,201 / 932 / 0 |
| Successful nonneutral HID / XInput state sends | 1,341 / 1,335 | 966 / 964 |
| Native handled motion / key events | 1,478 / 36 | 1,095 / 30 |
| Menu handled motion / key events | 446 / 12 | 388 / 14 |
| Menu pointer move / button requests | 158 / 12 | 164 / 14 |

Both launches install the accepted Unix Winebus SHA-256 `8f306c818432e94379efd9ae01b37126261c4d6a267beebb3cdad6c8b642af3c`. The first later attempt still has the read experiment active; the second verifies the exact original AMD64 HIDclass and the original backup. The required backend setting is restored in both. The previous disabled-notifications explanation therefore does not explain this new failure, and restoring original HIDclass does not restore the reported controls in the second attempt.

## Cleanup and game lifetime

Android PID 31536 spans both later launches. The first preflight passes in 210 ms. Stop at approximately 00:37:13 UTC passes in **731 ms**: kill/wait statuses 0, all **nine** verified same-prefix clients exit, zero unknown or remaining identities, no residual SIGKILL, and independent exclusive binds confirm ports 7949 and 7950 are free. The next preflight passes in **210 ms**, kill status 1/wait status 0, no prior-prefix clients, both controller ports free. These observations preserve the previously accepted full-session teardown behavior; they do not reproduce cleanup-not-ready.

The second launch's Game.exe is Linux PID 32235, identity `32235:97705598`. Five-second memory samples retain it through 00:38:44.262 UTC, generally running or sleeping with 20 threads after startup. Device low-memory is false in every retained sample. The final quick menu opens at 00:38:47.759 UTC, pointer context changes to explorer at 00:38:47.761, and the 00:38:49.288 memory sample no longer includes Game.exe. Runtime tail contains handled `40010006` OutputDebugString/Storm messages, without a captured fatal exception or SIGSEGV. These are not proof of a crash or the cause of failed input. The accepted older session also changes to explorer near its final exit/Stop sequence.

Current input availability is true while the user tests with the quick menu closed. The inactive final gate, paused Activity and `nativeInputEnabled=false` occur at export and must not be projected backward over the test. The second launch's last successful nonneutral state send is 00:38:31.072 UTC, immediately before its final switch to Menu cursor.

## Enumeration and producer observations do not establish game acceptance

Exactly one physical Android controller is accepted: Xbox Wireless Controller, ID 92, VID 8224/PID 274. The current census records one assigned physical slot, one distinct assigned Android device, zero virtual slots, and one declared legacy XInput client, Windows PID 232. There is no DInput discovery and no additional accepted physical pad. The declared Windows PID is discovery metadata, not an authenticated Linux socket owner.

Wine enumerates the accepted `pd2-xbox-slot0` HID parent. Its IG_00 and XI_00 children describe the same parent, as documented in [the controller census](2026-10-06-controller-census.md). Old UID 1/2 registry paths fail opening with `c0000034`; retained paths do not establish live competing controllers. The create/remove totals include the existing ordinary paired reconnect performed in each later attempt. Two creations and one retirement are not two simultaneously live physical controllers.

The complete current runtime body contains **942 XInputGetState calls**, **1,858 GetRawInputData calls**, and **2,788 GetRawInputDeviceInfo calls**. The patched attempt contains 1,262 / 2,498 / 3,748 respectively. These are call-entry observations. They do not show API return codes, returned state freshness, returned Raw Input sizes or PD2's acceptance. A successful UDP send proves producer output, not receiver consumption; `ERROR_SUCCESS` would still not prove a fresh controller packet. No new always-neutral producer failure is shown: both later launches record substantial successful nonneutral HID and XInput sends with zero state-send failures.

The same archive retains the physically accepted 0.1.16 replacement launch `6563c413-56a2-42a2-9219-b791f4fa2d61`, started October 6 at 18:22:50.183 UTC, in `attempts/*-1791332430264.*`. Its backend PID/socket are 27901/39928244 and its complete runtime body has 1,670 XInputGetState, 3,330 GetRawInputData and 4,995 GetRawInputDeviceInfo calls. Its graphics, arguments and Wine debug categories agree with the later attempts. No apparent duplicate physical controller, dead Java producer or shared cause of both reported failures has been established.

## Menu cursor visibility is a separate demonstrated flaw

During Menu input in the restored-original launch, diagnostics show `forceRoot=true`, `gameCursorVisible=false`, `rootCursorVisible=false`, and `cursorOverlayVisible=false`. Java nevertheless emits 164 move requests, 14 button requests and 164 feedback events, with changing pointer positions. The menu position exists without a visible game/root/app cursor. Hiding the white cursor must leave a visible Menu cursor indicator; these flags demonstrate that visibility gap.

They do not prove a new 0.1.17 routing regression or explain failed clicks. The accepted 0.1.16 replacement records the same hidden cursor flags and the same window topology: focus 20971521 (`game.exe`), point/menu 16777286, grab 8388616. Differences between these window IDs are therefore not sufficient evidence that events target the wrong window. Pointer request counters and positions do not acknowledge Wine's SendInput result or the game's receipt.

## Trace completeness and bounds

The current `runtime.log` snapshot has a 158-byte export header plus the complete **1,427,604-byte** original log body, **1,427,762 bytes** total. The patched-attempt snapshot likewise retains the complete **1,737,682-byte** body, **1,737,840 bytes** total. Neither omits a middle section. The accepted replacement snapshot retains the complete **2,078,535-byte** body, **2,078,693 bytes** total. Its later archive timestamp does not make it a new physical test.

The older first 0.1.16 Save/Quit-failure trace remains a bounded head/tail snapshot of a larger original log; do not generalize its completeness to every archived file. Current call counts refer only to their complete, explicitly identified bodies. The new diagnostic must continue exporting truncation/completeness information so absent lines are not interpreted as proof of an absent API operation.

## 0.1.18 scope and next evidence

The next preview repairs the Menu visibility gap with an app-drawn gold reticle when Menu cursor is selected and the white-cursor hiding option is enabled. This changes visible feedback; it is not a claim that Wine accepted the recorded clicks or that Native recovery is fixed.

The next preview also adds an explicit opt-in **one-next-Play** controller API-return diagnostic. It uses narrowly filtered existing Wine relay entry/return instrumentation for XInputGetState/GetStateEx and pointer/Raw Input APIs, without exporting pointed-to controller state, pressed-button values or axis values, and adds input-phase markers for correlation. It does not add a competing controller probe process or replace any XInput binary.

The diagnostic journals the exact raw previous values and absence of `RelayInclude`, `RelayExclude`, `RelayFromInclude` and `RelayFromExclude` under `Software\\Wine\\Debug`. Installation and restoration occur only after successful cleanup of the exact captured Wine prefix while startup is serialized. Restoration preserves values changed outside the diagnostic, rather than blindly replacing registry strings. The one-launch request must not become a permanent relay setting; the following clean launch restores owned fields and returns to normal logging.

This targets the missing observation at the API boundary. Native gameplay, Save/Quit recovery, fresh returned packet/state, Wine click acknowledgement and physical reticle behavior still require device evidence. Keep full same-app Stop/Play accepted, preserve the accepted runtime/import/prefix/graphics/controller mappings, and do not repeat the failed focus, paired reconnect or fresh-HID-UID experiments as the next recovery recommendation.

## Instrumentation qualification

Primary-source review uses the accepted custom Wine 9.2 revision `a4ef2bf8963fe4bfab390eebf73336364ce209bc`: [relay filtering](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/ntdll/relay.c), [user32 exports](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/user32/user32.spec), [pointer input](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/user32/input.c), [Raw Input](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/win32u/rawinput.c), and [typed registry serialization](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/server/registry.c). Filters initialize once from the registry. Empty RelayFromInclude excludes named callers, so both From fields must be temporarily absent. The relay module comparison also matches older xinput1 family names; do not claim exclusive XInput1_4 coverage.

The accepted relocated rootfs supplies the following unchanged i386 binaries, inspected for actual relay descriptors and exported thunks:

| Binary | Bytes | SHA-256 |
| --- | ---: | --- |
| xinput1_4.dll | 49,152 | `c8f692e7fe22fb922886026d97bf39549c8eac12629fee3e06f49078b34a60fa` |
| ntdll.dll | 700,416 | `e312091fc9238cc2c8d5ba808b50c6a7e6c4daed534bd4079489fb1ca529ca77` |
| user32.dll | 1,671,168 | `7e2b015582fcb0284e852a268ad42da5206889c23be31b911011ab99b8fed9c0` |

XInputGetState ordinal 2 and GetStateEx ordinal 100 have signature `iiI`; GetCursorPos 278 `iI`, GetForegroundWindow 295 `I`, GetRawInputData 363 `iiiiiI`, and SendInput 605 `iiiI`. Lowercase `i` represents scalar/pointer words; uppercase `I` the 32-bit result. No selected signature requests string or controller-buffer dereferencing. A source-derived ASan/UBSan harness passed 18 filter assertions, including all six selected functions and exclusion of GetKeyboardState. A separate four-assertion harness exercised scalar Call/Ret formatting with an invalid output pointer without dereferencing it. These qualify the instrumentation policy and available binary thunks; they do not execute Android/WoW64/PD2 or establish physical trace overhead, packet freshness or working controls.
