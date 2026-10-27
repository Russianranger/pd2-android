# PD2 Android 0.1.22 Preview

The XInput reader repair is now the default after the user's successful 0.1.21 Save/Quit test. Existing settings migrate once; subsequent manual fallback selections remain saved. The accepted cursor fix and exact reader DLL are retained.

## Performance and temperature

- Ordinary Wine sessions now retain errors/warnings and bounded reader telemetry without continuous XInput/raw-input/module/exception tracing. Explicit one-launch input tracing still enables detailed channels until Stop. In the accepted capture, XInput/raw-input trace text occupied about 72% of the retained 2 MiB despite relay tracing being Off.
- PD2 requests a 60 Hz Surface refresh on Android 11+ by default, with a **Device refresh rate** alternative under **Performance & temperature**. The system may choose another rate. This is a display request, not a game FPS limiter.
- Support memory samples add battery temperature and Android thermal status so sustained-play results can be compared. Battery temperature is not CPU/GPU temperature. No physical FPS, power or temperature improvement is claimed before device measurement.

## Launcher

A static dark gothic layout uses near-black stone panels, aged gold borders, crimson accents and licensed Cinzel Decorative headings. Body/settings text remains readable. Touch and controller focus retain access to launch, stop, import, preparation, settings and support actions. No animated background runs during play.

## Preserved baseline

Wine 9.2 Custom, Box64 0.4.4, rootfs 24, Wine HID backend, original HID driver, XInput reader revision/hash, accepted graphics/GameNative arguments, Stability, controller bindings, imports and saves are unchanged. Package identity and preview signer are unchanged. Install over the existing app; no re-import or Prepare runtime is needed.

## Validation

[CI 40425451054](https://github.com/Russianranger/pd2-android/actions/runs/40425451054) passes **372 tests across 42 suites**, zero failures/ignored tests, ARM64 assembly, retained host/runtime checks and the actual packaged Wine API/recovery gate. All seven native launcher previews were inspected, including larger text and controller focus reaching the tools below the fold. The APK retains all 76 prior assets and 35 ARM64 libraries byte-for-byte; the added assets are the font and its OFL. Its package/version/SDKs and unchanged V2 preview signer are independently verified.

APK: **174,199,564 bytes**. SHA-256: `5ffce0621c6b0f318cefb87497683c24ecd3545e95e59baf289b4c571e305cee`. Built from `dc157a90ac49748dd48d269ee1f7fc9494400624`. See [Testing](TESTING.md) for device regression and sustained-play checks and [accepted controller evidence](evidence/2026-10-26-xinput-reader-accepted.md) for the physical result.
