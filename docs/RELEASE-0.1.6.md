# PD2 Android 0.1.6 controller notifications preview

The 0.1.5 bundle confirms Android controller input and legacy bridge replies, while native PD2 input still did not activate. PD2 registered raw-input gamepad/joystick notifications but logged only its initial four XInput state checks. **This preview adds the missing HID producer path; activation on the Thor remains unqualified.**

## Changed

- Add targeted Wine 9.2 Unix HID backend revision `wine9-hid-1` and a matching 256-byte Java producer on port 7950. Keep the legacy 7949 XInput path for the same selected pad.
- Install the verified backend automatically before Play, preserving a verified original backup and using staged atomic replacement.
- Enable controller notifications by default, with a launcher setting to restore the original backend.
- Restore PlugPlay/RpcSs service start values needed by the notification path; other Essential service settings remain unchanged.
- Add HID counters and backend status to diagnostics. Keep Wine 9.2, rootfs 24, prefix revision, graphics, and imported PD2 files/saves. No PD2 DLL changes or runtime preparation/import are required.

Version code is 7; application/signing identity and SDK levels are unchanged.

## One test

1. Install over 0.1.5; do not uninstall, clear storage, Prepare runtime, or re-import.
2. Keep the accepted GameNative-arguments Glide choice, Stability, and **Controller notifications enabled (default)**.
3. Enter a temporary offline character using fallback if needed, select Native, close the menu, press a controller button, then test both sticks/buttons without mouse input.
4. Inspect Controller status and export logs whether input works or fails.

If startup regresses, export first, select **Launch settings → Controller → Controller notifications disabled**, force-stop the app, reopen, and Play to restore the original backend. Export that attempt separately.

Local verification passed all 76 API 33 tests, three native controller tests, the import/crash/session-log/input-router checks, source/artifact/ABI checks, and final APK assembly/signature/payload checks. All 35 Android native libraries and 70 existing assets match 0.1.5; only the backend/manifest assets are added. APK SHA-256: `21a5b1095ab254a7f4d5cf60ef9e6364b848fc604bbec58838226c510f796f8b`. Published 0.1.6 CI and the physical test remain pending. The host harness does not establish complete Wine/HID/PD2 integration. See [Testing](TESTING.md) and [Handoff](HANDOFF.md).
