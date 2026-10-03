# PD2 Android 0.1.3 Fog startup diagnostics preview

Both 0.1.2 CPU comparisons failed at the same native Fog stack write. The exact source folder works in the user's GameNative Bionic Proton 9.0/Box64 0.3.7 setup; this app retains Wine 10.10/Box64 0.4.4. **The cause remains unconfirmed. This preview adds a launch comparison and evidence, not a confirmed fix or performance improvement.**

## Added

- Fifth Launch settings choice: **Turnip + Zink · Glide (GameNative arguments)**, using `-3dfx -dxnocompatmodefix` without `-w`, with the native wrapper. The D2DX flag is a later wrapper option and does not explain the captured early Fog fault.
- Filtered native Fog export tracing, plus selected registry settings in `launch.json`.
- PE stack reserve/commit values and SHA-256 for selected core files up to 8 MiB.
- The newest two dated D2 logs and available D2DX/D2GL text logs, each capped at 256 KiB with head/tail preservation. Symlinks, game binaries, and saves are excluded.

Version code is 4. Application/signing identity, SDK levels, runtime archives, and imported game files are preserved. Fog export tracing does not provide a complete stack or establish internal recursion. Native controller and gameplay qualification remain pending.

## One test

1. Install over 0.1.2; **do not uninstall, clear storage, Prepare runtime, or re-import**.
2. Choose **Turnip + Zink · Glide (GameNative arguments)** and **CPU mode → Stability (default)**.
3. Press **Play once**, wait at most 60 seconds for the title/menu, then stop the client if needed.
4. **Export support logs** and upload the ZIP whether the attempt succeeds or fails. Report the last visible screen.

Local verification passed all 20 Android 13/API 33 tests, the 47 import/11 crash-recovery/26 session-log checks, input-router checks, and the ARM64 APK build. Published CI remains pending until inspected. APK SHA-256: `5124f9b7a5560875b2ab5d19d33985da743b26448ccd1ef4005c77fbdd76b03f`. See [Testing](TESTING.md) for the full sequence; accepted startup/setup/import should not be repeated.
