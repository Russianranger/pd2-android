# Project Diablo 2 Android launcher

Current preview: **0.1.25**. This pass addresses the reported black-square game cursor regression and improves performance diagnosis. Game frame settings are again the default: on the next clean Play, launcher-owned frame changes are restored from the verified journal. Explicit 30/60/Unlimited choices remain persistent. A reversible **Classic cursor** option bypasses D2GL's HD cursor. Physical cursor recovery and FPS gains are not yet confirmed.

Install over the existing app and reuse its runtime, prefix, import and saves. For the reported cursor problem, choose **Performance & temperature → Game frame settings**, then Stop client and Play. If the square persists, select **Graphics & audio → Game cursor → Classic cursor** and restart the client. Both choices report their last-launch status. The independent 60 Hz display request, accepted controller reader, cursor movement fix and gothic UI are preserved. Game frame settings also disables the optional Android content redraw delay.

The user reports improved audio with **Eco audio**, and the latest capture confirms 22.05 kHz negotiation. That explicit selection is retained. Buffering stays 32 ms by default, with 16/64 ms available. Existing lighter-effects choices also persist. Only seven supported settings in the app-private `ProjectD2/d2gl.json` may be changed, with verified backups and preservation of later user edits; resolution, shader selection, game DLLs and saves are preserved. Support logs now include bounded process CPU measurements and read-only renderer/cursor settings. See [Testing](docs/TESTING.md), [release notes](docs/RELEASE-0.1.25.md), [capture evidence](docs/evidence/2026-10-27-cursor-performance-followup.md) and [handoff](docs/HANDOFF.md).

## First preview

- Prepare the bundled runtime and a managed PD2 Wine prefix.
- Import a complete existing Diablo II/LoD installation containing `ProjectD2`, through an Android folder picker or ZIP.
- Launch and resume PD2 from the app's home screen.
- Use PD2's native controller support by default, or select Menu cursor or a mouse/keyboard controller layout in the quick menu.
- Open the quick menu with the on-screen gear or the **L3 + R3** chord.
- Inspect accepted Android pads and input/bridge counters through **Controller status**; support export includes `controller.json`.
- Select Turnip/Zink or Turnip/VirGL, and choose Glide or DirectDraw launch arguments for compatibility testing.

The app preserves game binaries, official PD2 DLLs and saves. Performance controls can update supported D2GL settings in its private imported copy, with reversible backups; the source installation is not edited. It does not include Diablo II assets, game licenses, account credentials, a PD2 updater, or a loot-filter manager. Update your installation through its normal updater before importing it.

## Install and import

1. Install the preview APK provided with this build. For an existing accepted installation, use the next-test sequence above.
2. Press **Prepare runtime** if the runtime is not already ready. The first preparation extracts the bundled runtime; leave the app open until it finishes. The managed prefix is created on the first Play.
3. If no accepted installation is present, import the complete **Diablo II** folder, not only `ProjectD2`. A ZIP may contain the installation directly or inside a parent folder. Reuse a previously accepted installation after updating the app.
4. Wait for copying and validation to finish, then press **Play**.

The import must include the base archives `d2data.mpq`, `d2char.mpq`, `d2sfx.mpq`, and `d2exp.mpq`, plus `ProjectD2/Game.exe`, `ProjectD2/ProjectDiablo.dll`, and `ProjectD2/pd2data.mpq`. These are minimum structural checks; importing your complete working English installation is required. They do not verify every game dependency or authenticate the contents. A Diablo II: Resurrected installation is not supported.

Imports are copied into private storage. A ZIP import temporarily needs space for both the ZIP and extracted files. Replacements also retain the previous accepted install, so allow space for that backup and the new staged copy as well as the active install and runtime. The source installation is not edited. Invalid imports leave the previous accepted install in place; importing while the game is running is blocked. The import limits are 12 GiB per ZIP, 16 GiB extracted, and 50,000 entries.

Private game files and saves survive APK updates using the same signing identity. Android's **Clear storage** and uninstall remove private app data. Keep an independent backup of your original install and offline saves.

## Controls and display

**Native controller** forwards the physical controller through the Wine 9.2-compatible bridge. This baseline exposes one active gamepad; its XInput triggers are digital and its Wine DLL does not support rumble. PD2 remains responsible for movement, aiming, and its controller bindings. Each explicit Native selection retains the synchronized HID and legacy XInput reconnect; previous reconnect/identity experiments failed Save/Quit recovery. Immediate Native L3/R3 edges preserve held combinations; the first thumb press can reach the game before pressing the second thumb opens the reserved **L3 + R3** quick menu. Menu cursor, LT/thumb combinations and shoulder behavior are accepted. The 0.1.21 repaired XInput reader is accepted for initial input and Save/Quit recovery on the Thor and is now the default. The original module remains available as a fallback.

**Recover Native controller (experimental)** changes the Windows device identity after acknowledged old-device removal. It leaves the accepted default identity unchanged until explicitly selected. Wait for its message, up to eight seconds, then test movement and a face button. A Wine device-start confirmation is not a gameplay-success claim. Export before and after the experiment.

Controller notifications are enabled by default. To restore the original Wine backend, stop the client, select **Launch settings → Controller → Controller notifications disabled**, force-stop the Android app, reopen it, and press Play. Changing this option takes effect on the next fresh game launch.

**Menu cursor** is a temporary navigation mode: either stick moves the pointer (the right stick takes priority), A clicks, B/Select sends Esc, Start/R3 sends Enter, L3 sends Tab, and the D-pad sends arrow keys. Select Native controller after entering a character. This mode is selected manually and is not retained as the next launch's default.

**Mouse / keyboard layout** turns the controller into mouse and keyboard input. Its left stick sends WASD, so enable PD2's WASD movement if you want to move using that fallback layout. The right stick moves the pointer; A and B provide left and right click. The quick menu also provides the Android keyboard and cursor-speed adjustment. Full bindings and switch tests are in [Testing](docs/TESTING.md).

**Hide white cursor** in the gear menu hides the app's root pointer, Wine/X11 cursor overlay, and Android pointer icon while keeping the cursor drawn inside the game's framebuffer. It defaults to **Off** and is saved for future sessions. If Menu cursor has no visible pointer with this option On, turn it Off for navigation. The option controls rendering; it does not activate Native input.

The screen size is 1280×720. Standard Glide uses `-3dfx -w`; the fifth Glide choice uses `-3dfx -dxnocompatmodefix` without `-w`, matching the supplied GameNative arguments. Both preserve the imported native wrapper. `-dxnocompatmodefix` is a D2DX wrapper option and does not explain the already captured early Fog fault. Wine DirectDraw compatibility (`-ddraw -w`) explicitly uses built-in `ddraw`. The menu-transition test keeps this accepted display configuration; the reported widescreen/4:3 change is an unverified hypothesis.

Under **Launch settings → CPU mode**, **Stability (default)** remains the CPU default. The completed **Interpreter (diagnostic; very slow)** comparison failed at the same Fog stack write; do not repeat it for the next test. These profiles and the Turnip/Zink or Turnip/VirGL choices are candidates for qualification, not proven fastest settings.

## Development

Source builds fetch pinned binary runtime dependencies, verify their SHA-256
values, and relocate their package paths before compiling. The Wine 9.2 baseline
uses matched assets from the official Winlator 10.1 APK; other runtime components
retain their existing pinned sources.
The APK includes these dependencies and launches independently of Winlator or
GameNative once your game files are imported.
The targeted Wine 9 HID backend is built from the included source and pinned
Wine 9 headers; [Build instructions](docs/BUILD.md) describe its artifact/ABI checks.

[Build instructions](docs/BUILD.md) describe the pinned toolchain and preview signing. [Testing](docs/TESTING.md) contains the current device check; [Handoff](docs/HANDOFF.md) records evidence and remaining gates. [0.1.25 notes](docs/RELEASE-0.1.25.md) describe the current changes; [accepted reader evidence](docs/evidence/2026-10-26-xinput-reader-accepted.md) records the accepted controller result and its diagnostic limits. Earlier release notes retain the driver experiment, accepted restart, memory diagnostics, socket repair, identity experiment and container-management history.

Third-party runtime components and their licenses are listed in [Third-party notices](THIRD_PARTY_NOTICES.md). This project is an independent launcher and is not affiliated with Blizzard Entertainment or the Project Diablo 2 team.
