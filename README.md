# PD2 Android

A dedicated Android launcher for a user-owned classic Diablo II: Lord of Destruction installation with Project Diablo 2. The first preview targets the AYN Thor's ARM64/Adreno hardware and imports an existing working installation into the app's private storage.

The app includes a Winlator-derived Windows runtime rather than requiring a separate Winlator or GameNative installation. It uses Wine 10.10 and Box64 0.4.4 from Winlator 11.2, with an Android X server and controller bridge. Its application ID is `com.pd2.thor`, so it installs alongside those apps.

**Status: 0.1.0 preview, awaiting physical-device qualification.** Building the APK and passing automated checks do not establish that PD2 launches, renders, accepts both sticks, or connects online on the Thor. No performance improvement over an existing Winlator/GameNative setup has been measured yet.

## First preview

- Prepare the bundled runtime and a managed PD2 Wine prefix.
- Import a complete existing Diablo II/LoD installation containing `ProjectD2`, through an Android folder picker or ZIP.
- Launch and resume PD2 from the app's home screen.
- Use PD2's native controller support by default, or switch to a mouse/keyboard controller layout in the in-game quick menu.
- Open the quick menu with the on-screen gear or the **L3 + R3** chord.
- Select Turnip/Zink or Turnip/VirGL, and choose Glide or DirectDraw launch arguments for compatibility testing.

The app preserves imported game files and official PD2 DLLs. It does not include Diablo II assets, game licenses, account credentials, a PD2 updater, or a loot-filter manager. Update your installation through its normal updater before importing it.

## Install and import

1. Install the preview APK provided with this source package (or from the repository preview release when published).
2. Open **PD2 Android** and press **Prepare runtime**. The first preparation extracts the bundled runtime; leave the app open until it finishes. The managed prefix is created on the first Play.
3. Import the complete **Diablo II** folder, not only `ProjectD2`. A ZIP may contain the installation directly or inside a parent folder.
4. Wait for copying and validation to finish, then press **Play**.

The import must include the base archives `d2data.mpq`, `d2char.mpq`, `d2sfx.mpq`, and `d2exp.mpq`, plus `ProjectD2/Game.exe`, `ProjectD2/ProjectDiablo.dll`, and `ProjectD2/pd2data.mpq`. These are minimum structural checks; importing your complete working English installation is required. They do not verify every game dependency or authenticate the contents. A Diablo II: Resurrected installation is not supported.

Imports are copied into private storage. A ZIP import temporarily needs space for both the ZIP and extracted files. Replacements also retain the previous accepted install, so allow space for that backup and the new staged copy as well as the active install and runtime. The source installation is not edited. Invalid imports leave the previous accepted install in place; importing while the game is running is blocked. The import limits are 12 GiB per ZIP, 16 GiB extracted, and 50,000 entries.

Private game files and saves survive APK updates using the same signing identity. Android's **Clear storage** and uninstall remove private app data. Keep an independent backup of your original install and offline saves.

## Controls and display

**Native controller** forwards the physical controller to Wine's Windows gamepad interface. PD2 remains responsible for movement, aiming, and its controller bindings. Both sticks and triggers must be confirmed on the physical Thor.

**Mouse / keyboard layout** turns the controller into mouse and keyboard input. Its left stick sends WASD, so enable PD2's WASD movement if you want to move using that fallback layout. The right stick moves the pointer; A and B provide left and right click. The quick menu also provides the Android keyboard and cursor-speed adjustment. Full bindings and switch tests are in [Testing](docs/TESTING.md).

The default screen size is 1280×720, with **Turnip/Zink** and `-3dfx -w`. The fallback choices are **Turnip/VirGL** and `-ddraw -w`. These are candidates for qualification, not proven fastest settings. Preserve the current configuration when reporting results and change one option at a time.

## Development

Source builds fetch 205 binary runtime dependencies from a pinned Winlator commit,
verify their SHA-256 values, and relocate their package paths before compiling.
The APK includes these dependencies and launches independently of Winlator or
GameNative once your game files are imported.

[Build instructions](docs/BUILD.md) describe the pinned toolchain and preview signing. [Testing](docs/TESTING.md) contains the first device checklist; [Handoff](docs/HANDOFF.md) records the implemented scope and remaining gates. [Release notes](docs/RELEASE-0.1.0.md) describe the preview.

Third-party runtime components and their licenses are listed in [Third-party notices](THIRD_PARTY_NOTICES.md). This project is an independent launcher and is not affiliated with Blizzard Entertainment or the Project Diablo 2 team.
