# PD2 Android 0.1.2 diagnostics and stability preview

The user confirmed that 0.1.1 opens, prepares its runtime, and imports the game on the Thor. All four graphics profiles returned to the launcher when starting PD2. The surviving final-attempt log reaches `Game.exe` and records a Wine stack overflow at `6FF6879A`. **Its underlying cause remains unconfirmed; this is a diagnostic preview, not a qualified gameplay fix.**

## Changes

- Default Box64 CPU profile changes from Conservative to Stability as a compatibility candidate.
- DirectDraw compatibility explicitly uses Wine's built-in `ddraw`; Glide retains the imported native wrapper. The earlier DirectDraw choices still preferred the native wrapper and were not a built-in Wine baseline.
- **Launch settings → CPU mode → Interpreter (diagnostic; very slow)** provides a separate diagnostic CPU comparison.
- Wine exception/module-load errors and warnings, plus Box64 signal details, are captured for the next failure.
- Launch records include the selected settings, client-file inventory, and runtime process exit status.
- Separate runtime logs retain up to four archived attempts alongside the current log. The live log is capped at 8 MiB; archived attempts are capped at 2 MiB each.
- The accepted runtime, imported game files, application ID, and preview signing identity are preserved.

## Next test

1. Install 0.1.2 over the existing app without uninstalling or clearing storage. **Reuse the accepted runtime and installation; do not prepare or import again.**
2. Select **Turnip + Zink · Wine DirectDraw (compatibility)** with CPU **Stability** and press **Play once**.
3. If it returns to the launcher, immediately choose **Export support logs** and upload the ZIP to the chat. Report the last screen visible and whether a game window appeared.
4. If needed, make one second attempt using **Interpreter (diagnostic; very slow)** with the same graphics choice. Allow up to 60 seconds for the title; if unfinished, return to the launcher, **Stop client**, and export another ZIP. Restore **Stability (default)** afterward. This time limit bounds the comparison, not every valid interpreter load.
5. If the whole Android app closes, reopen it and use **Export crash details** if the recovery screen appears.

This first test is for client appearance. Built-in DirectDraw may bypass D2GL/controller features; native dual-stick support still requires separate qualification. No performance improvement or faulting DLL has been established from the existing evidence. Rendering/audio, mode switching, offline-save persistence, and online gameplay remain pending. See [Testing](TESTING.md) for subsequent qualification steps.
