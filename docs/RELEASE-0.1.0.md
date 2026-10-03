# PD2 Android 0.1.0 preview

First test release of a dedicated Android launcher for a user-owned classic Diablo II/LoD + Project Diablo 2 installation. The initial hardware target is the AYN Thor Max.

## Included

- Bundled Winlator 11.2-derived Wine 10.10/Box64 0.4.4 runtime and a private PD2 prefix.
- Full folder or ZIP import with staged copying, bounded structural validation, and preservation of the accepted install when a new import fails.
- Play/Resume, native Windows gamepad forwarding, and a mouse/keyboard controller layout.
- In-game quick menu through the gear or L3 + R3, with input switching, Android keyboard, cursor settings, and return to launcher.
- Turnip/Zink and Turnip/VirGL renderer choices; Glide `-3dfx -w` and DirectDraw `-ddraw -w` launch choices.
- A separate application ID, `com.pd2.thor`, and consistent preview signing for updates without uninstalling.
- Support-log ZIP export for troubleshooting the first device test.

Game files are imported by the user. Diablo II assets and PD2 game DLLs are not bundled or replaced. This preview does not include automatic PD2 updates, a loot-filter manager, or measured performance optimizations.

## Test sequence

1. Install, press **Prepare runtime**, and import the **whole Diablo II folder** containing `ProjectD2`.
2. Play with Turnip/Zink, Glide, and Native controller. Verify display/audio, left-stick movement, and independent right-stick aiming.
3. Use the gear or L3 + R3 to switch to **Mouse / keyboard layout** and back; check for stuck input. Use **Back to Launcher Menu** and **Resume client**.
4. Save an offline character, stop/relaunch, and reopen it. Then test normal PD2 online login and game entry.
5. Use **Export support logs** if any step fails and provide the renderer/launch/input settings and last successful step.

See [the full testing checklist](TESTING.md) for bindings and fallback settings.

**Physical-device qualification is pending.** Compilation/unit checks do not confirm gameplay, controller compatibility, online login, save persistence, or a performance gain. The preview uses a public testing signing key; obtain its APK from the repository's release/Actions links.
