# PD2 Android

A dedicated Android launcher for a user-owned classic Diablo II: Lord of Destruction installation with Project Diablo 2. The first preview targets the AYN Thor's ARM64/Adreno hardware and imports an existing working installation into the app's private storage.

The app includes a Winlator-derived Windows runtime rather than requiring a separate Winlator or GameNative installation. It uses Wine 9.2 (Custom) from official Winlator 10.1, retaining Box64 0.4.4 and the current Android display/input and graphics components. Its application ID is `com.pd2.thor`, so it installs alongside those apps.

**Status: 0.1.8 menu-pointer recovery preview.** Native controller input inside a character was accepted on 0.1.6. The user reported that the 0.1.7 Menu cursor still did not respond after Save/Exit. The new capture confirms that Menu cursor was selected with Android focus restored; it does not establish an aspect-ratio cause.

This preview gives Menu cursor an app-owned visible pointer and routes its mouse/keyboard commands through the existing Windows input helper. It centers and bounds the pointer using the live game window, and records per-mode input, output requests, cursor feedback, and bounded window geometry. The accepted native HID runtime and the full Mouse / keyboard layout are preserved. Device confirmation remains required.

## Next 0.1.8 check

Install 0.1.8 over the existing app without uninstalling or clearing storage. **Reuse the accepted runtime, prefix, and installation. Do not Prepare runtime or re-import.** Keep **Turnip + Zink · Glide (GameNative arguments)**, **Stability (default)**, and controller notifications enabled.

Enter a character and confirm Native controller still works. Save/Exit, use **L3 + R3 → Menu cursor**, and check whether the visible pointer moves with either stick and whether **A** clicks Single Player/character controls. Re-enter the same character, select **Native controller**, and check input again. Export support logs and distinguish a missing/stationary pointer from a pointer that moves but cannot click. [Testing](docs/TESTING.md) gives the focused sequence.

If the whole Android app closes, reopen it. If **PD2 Android recovery** appears, choose **Export crash details**, save/share `crash.txt`, and upload it to the chat. Java crash capture does not cover every possible process exit.

## First preview

- Prepare the bundled runtime and a managed PD2 Wine prefix.
- Import a complete existing Diablo II/LoD installation containing `ProjectD2`, through an Android folder picker or ZIP.
- Launch and resume PD2 from the app's home screen.
- Use PD2's native controller support by default, or select Menu cursor or a mouse/keyboard controller layout in the quick menu.
- Open the quick menu with the on-screen gear or the **L3 + R3** chord.
- Inspect accepted Android pads and input/bridge counters through **Controller status**; support export includes `controller.json`.
- Select Turnip/Zink or Turnip/VirGL, and choose Glide or DirectDraw launch arguments for compatibility testing.

The app preserves imported game files and official PD2 DLLs. It does not include Diablo II assets, game licenses, account credentials, a PD2 updater, or a loot-filter manager. Update your installation through its normal updater before importing it.

## Install and import

1. Install the preview APK from this repository's release. For an existing accepted installation, use the next-test sequence above.
2. Press **Prepare runtime** if the runtime is not already ready. The first preparation extracts the bundled runtime; leave the app open until it finishes. The managed prefix is created on the first Play.
3. If no accepted installation is present, import the complete **Diablo II** folder, not only `ProjectD2`. A ZIP may contain the installation directly or inside a parent folder. Reuse a previously accepted installation after updating the app.
4. Wait for copying and validation to finish, then press **Play**.

The import must include the base archives `d2data.mpq`, `d2char.mpq`, `d2sfx.mpq`, and `d2exp.mpq`, plus `ProjectD2/Game.exe`, `ProjectD2/ProjectDiablo.dll`, and `ProjectD2/pd2data.mpq`. These are minimum structural checks; importing your complete working English installation is required. They do not verify every game dependency or authenticate the contents. A Diablo II: Resurrected installation is not supported.

Imports are copied into private storage. A ZIP import temporarily needs space for both the ZIP and extracted files. Replacements also retain the previous accepted install, so allow space for that backup and the new staged copy as well as the active install and runtime. The source installation is not edited. Invalid imports leave the previous accepted install in place; importing while the game is running is blocked. The import limits are 12 GiB per ZIP, 16 GiB extracted, and 50,000 entries.

Private game files and saves survive APK updates using the same signing identity. Android's **Clear storage** and uninstall remove private app data. Keep an independent backup of your original install and offline saves.

## Controls and display

**Native controller** forwards the physical controller through the Wine 9.2-compatible bridge. This baseline exposes one active gamepad; its XInput triggers are digital and its Wine DLL does not support rumble. PD2 remains responsible for movement, aiming, and its controller bindings. Native input inside a character is accepted by user report; independent aiming and the complete button/trigger layout still need physical checks.

Controller notifications are enabled by default. To restore the original Wine backend, stop the client, select **Launch settings → Controller → Controller notifications disabled**, force-stop the Android app, reopen it, and press Play. Changing this option takes effect on the next fresh game launch.

**Menu cursor** is a temporary navigation mode: either stick moves the pointer (the right stick takes priority), A clicks, B/Select sends Esc, Start/R3 sends Enter, L3 sends Tab, and the D-pad sends arrow keys. Select Native controller after entering a character. This mode is selected manually and is not retained as the next launch's default.

**Mouse / keyboard layout** turns the controller into mouse and keyboard input. Its left stick sends WASD, so enable PD2's WASD movement if you want to move using that fallback layout. The right stick moves the pointer; A and B provide left and right click. The quick menu also provides the Android keyboard and cursor-speed adjustment. Full bindings and switch tests are in [Testing](docs/TESTING.md).

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

[Build instructions](docs/BUILD.md) describe the pinned toolchain and preview signing. [Testing](docs/TESTING.md) contains the controller retest and pending gameplay checklist; [Handoff](docs/HANDOFF.md) records the evidence and remaining gates. [0.1.8 release notes](docs/RELEASE-0.1.8.md) describe the revised menu pointer and diagnostics. Earlier release notes retain the preview history.

Third-party runtime components and their licenses are listed in [Third-party notices](THIRD_PARTY_NOTICES.md). This project is an independent launcher and is not affiliated with Blizzard Entertainment or the Project Diablo 2 team.
