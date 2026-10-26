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

Android 13 tests, actual launcher PNG previews, retained host regressions and the packaged Wine API/recovery check are required before offering the APK. See [Testing](TESTING.md) for device regression and sustained-play checks and [accepted controller evidence](evidence/2026-10-26-xinput-reader-accepted.md) for the physical result.
