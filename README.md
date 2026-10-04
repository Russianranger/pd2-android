# PD2 Android

A dedicated Android launcher for a user-owned classic Diablo II: Lord of Destruction installation with Project Diablo 2. The first preview targets the AYN Thor's ARM64/Adreno hardware and imports an existing working installation into the app's private storage.

The app includes a Winlator-derived Windows runtime rather than requiring a separate Winlator or GameNative installation. It uses Wine 9.2 (Custom) from official Winlator 10.1, retaining Box64 0.4.4 and the current Android display/input and graphics components. Its application ID is `com.pd2.thor`, so it installs alongside those apps.

**Status: 0.1.11 controller and cursor preview.** Initial Native gameplay and Menu cursor navigation are accepted. Native still fails on Save/Quit by the latest user report; recovery after character re-entry remains unresolved. The reported LT + L3 combination and shoulder tab switching also require device qualification.

This preview corrects independent trigger handling and held Native L3/R3 input, prevents disabled or zero-distance captured mouse forwarding, and expands **Hide white cursor** to the app and Wine/X11 pointer layers. Explicit Native selection now briefly disconnects both HID and legacy XInput together as a recovery experiment. The [research evidence](docs/evidence/2026-10-04-controller-research.md) explains the findings and limits. Save/Quit recovery is not yet proven.

## Next 0.1.11 check

Install 0.1.11 over the existing app. **Reuse the accepted runtime, prefix, and installation. Do not Prepare runtime or re-import.** Keep **Turnip + Zink · Glide (GameNative arguments)**, **Stability (default)**, and controller notifications enabled.

Enter the same offline character and check initial Native gameplay, **hold LT while pressing/releasing L3**, and test each shoulder separately in the in-game tabs. Then Save/Quit and record the Save/Quit screen, title/character menus, and gameplay re-entry separately. Use **L3 + R3 → Menu cursor** to re-enter if needed. After loading, release the controls, explicitly select **Native controller**, wait about one second, and press/release a face button before checking movement and the same combinations. Test **Hide white cursor: On** in the main menu and in-game, then export support logs. [Testing](docs/TESTING.md) gives the focused sequence and [control tables](docs/TESTING.md#menu-cursor-bindings).

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

1. Install the preview APK provided with this build. For an existing accepted installation, use the next-test sequence above.
2. Press **Prepare runtime** if the runtime is not already ready. The first preparation extracts the bundled runtime; leave the app open until it finishes. The managed prefix is created on the first Play.
3. If no accepted installation is present, import the complete **Diablo II** folder, not only `ProjectD2`. A ZIP may contain the installation directly or inside a parent folder. Reuse a previously accepted installation after updating the app.
4. Wait for copying and validation to finish, then press **Play**.

The import must include the base archives `d2data.mpq`, `d2char.mpq`, `d2sfx.mpq`, and `d2exp.mpq`, plus `ProjectD2/Game.exe`, `ProjectD2/ProjectDiablo.dll`, and `ProjectD2/pd2data.mpq`. These are minimum structural checks; importing your complete working English installation is required. They do not verify every game dependency or authenticate the contents. A Diablo II: Resurrected installation is not supported.

Imports are copied into private storage. A ZIP import temporarily needs space for both the ZIP and extracted files. Replacements also retain the previous accepted install, so allow space for that backup and the new staged copy as well as the active install and runtime. The source installation is not edited. Invalid imports leave the previous accepted install in place; importing while the game is running is blocked. The import limits are 12 GiB per ZIP, 16 GiB extracted, and 50,000 entries.

Private game files and saves survive APK updates using the same signing identity. Android's **Clear storage** and uninstall remove private app data. Keep an independent backup of your original install and offline saves.

## Controls and display

**Native controller** forwards the physical controller through the Wine 9.2-compatible bridge. This baseline exposes one active gamepad; its XInput triggers are digital and its Wine DLL does not support rumble. PD2 remains responsible for movement, aiming, and its controller bindings. Each explicit Native selection requests a synchronized HID and legacy XInput disconnect/reconnect with an absence interval of at least 600 ms. Press/release a face button after the reconnect before testing sticks. Immediate Native L3/R3 edges preserve held combinations; the first thumb press can reach the game before pressing the second thumb opens the reserved **L3 + R3** quick menu. Recovery after re-entry, menu support, aiming, triggers, and shoulder tab navigation still need physical checks.

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

[Build instructions](docs/BUILD.md) describe the pinned toolchain and preview signing. [Testing](docs/TESTING.md) contains the controller retest and pending gameplay checklist; [Handoff](docs/HANDOFF.md) records the evidence and remaining gates. [0.1.11 release notes](docs/RELEASE-0.1.11.md) describe the controller/cursor changes, and the [research report](docs/evidence/2026-10-04-controller-research.md) records primary sources and diagnosis limits. Earlier release notes retain the preview history.

Third-party runtime components and their licenses are listed in [Third-party notices](THIRD_PARTY_NOTICES.md). This project is an independent launcher and is not affiliated with Blizzard Entertainment or the Project Diablo 2 team.
