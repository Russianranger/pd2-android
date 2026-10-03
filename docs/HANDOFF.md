# PD2 Android handoff

## Scope and current state

The user requested the first milestone of a dedicated Project Diablo 2 Android app in `Russianranger/pd2-android`: import an existing working installation, play using PD2's own controller support, and switch to mouse/keyboard input from a quick menu. The primary target is the AYN Thor Max, Android 13, Snapdragon 8 Gen 2/Adreno 740, ARM64, 16 GB RAM.

The initial implementation uses Winlator 11.2's embedded Wine 10.10/Box64 0.4.4 runtime and Android display/input components. Java/JNI keeps its upstream `com.winlator` namespace; the distinct installed application ID is `com.pd2.thor`.

Runtime archives are deterministically relocated from the original `com.winlator` data paths to the equal-length `com.pd2.thor` identity, including embedded binary paths. This is runtime packaging, not a change to imported PD2 binaries. Preserve this relocation when updating the donor runtime; merely changing the Android application ID is insufficient.

This is an initial **0.1.0 preview**. Qualification is pending the user's physical-device results. Do not record the milestone as qualified based on an APK build, header validation, or source review.

## Implemented design

- A dedicated launcher manages a single PD2 prefix, prepares bundled runtime files, and exposes Play/Resume.
- Complete folder and ZIP imports are staged in private storage, validated, then accepted. A failed import preserves the previous installation. The game must be stopped before importing.
- A successful replacement retains `install.previous` until the next accepted replacement. This is recovery storage, not a user-facing save-merge or backup manager; the replacement's supplied saves become its own saves.
- The managed `P:` drive points to the imported installation. The selected `ProjectD2/Game.exe` is launched with its directory as the working directory.
- The dedicated Wine prefix seeds Diablo II's `InstallPath` and `GamePath` registry keys from the validated base/client directories so imported PD2 can find its base installation.
- Structural validation looks for a 32-bit x86 PE `Game.exe` and `ProjectDiablo.dll`, PD2 data, and the required base MPQ archive headers. It is bounded and read-only; it is not full file-integrity or version verification.
- The initial screen size is 1280×720. Runtime options expose Turnip/Zink and Turnip/VirGL; game launch options expose `-3dfx -w` and `-ddraw -w`. Turnip/Zink with Glide is the initial setting.
- Physical-controller input uses the Windows gamepad bridge by default. The mouse/keyboard fallback is a fixed PD2-oriented layout with adjustable cursor speed and stick deadzone.
- The on-screen gear and L3 + R3 open a quick menu for input switching, keyboard access, and returning to the launcher. Held input is released at mode/menu/lifecycle transitions.
- Game DLLs are imported as supplied. No custom PD2/BH patching is part of this milestone.
- **Export support logs** shares a ZIP with device/runtime/installation details, recent Android process-exit information, and bounded log tails. It is diagnostic export, not game/save backup.

## Verification boundary

The initial source checks passed with JDK 17:

- `./scripts/test-import.sh`: 47 checks, including unsafe ZIP rejection, ZIP64, synthetic PE/MPQ structure validation, replacement preservation, and interrupted-promotion recovery.
- `python3 tests/test_input_router.py`: tests the actual fallback router with small Android/X-server stand-ins; pointer/key release, shared input sources, direction reversal, analog/digital triggers, drift, alternate axes, and timer cleanup passed.

APK compilation and packaging are separate build/CI checks. These JVM tests do not execute Android lifecycle behavior, the Wine controller bridge, graphics drivers, or PD2. Synthetic PE/MPQ fixtures do not establish compatibility with a real installation.

The release is ready for a first physical test only after its APK and checksums have been produced. The following remain pending:

| Gate | Required evidence |
| --- | --- |
| First preparation/import | Bundled runtime prepares; full user installation imports without crash or unexplained memory pressure |
| Rendering/audio | PD2 reaches a playable scene with correct textures, UI, and audio |
| Native controller | Left-stick movement, independent right-stick aiming, triggers/buttons, and controller UI work on the Thor |
| Input switching | Gear and L3 + R3 menu work; switching repeatedly leaves no held keys, clicks, or analog input |
| Lifecycle | Return to launcher and Resume preserve the same session; background/foreground behavior is verified |
| Offline saves | Save and exit, stop, relaunch, and reopen the same character successfully |
| Online play | User can authenticate and enter a normal PD2 online game using the unmodified imported install |

The native controller path is the priority qualification step. Keyboard emulation working is not evidence that native movement/aiming works.

## Next work after the first device test

Investigate failures from the exported support bundle and the user's exact renderer/launch/input settings. Preserve accepted tests and the current architecture; fix the failing layer before adding unrelated features.

Once the app matches the existing working setup, capture baseline startup time, frame stability, crowded-combat behavior, input latency, temperature, and power use. Compare configurations one at a time before claiming optimization gains.

PD2 updates, loot-filter management, broader device presets, production signing, and performance tuning are follow-up work. Do not replace the official PD2 DLLs or add these managers as part of first-milestone qualification.
