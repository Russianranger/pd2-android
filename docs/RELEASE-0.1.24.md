# PD2 Android 0.1.24 Preview

The 0.1.23 support capture showed zero X Present and Sync wait requests. Those changes did not cover this game's rendering path. This release targets the existing D2GL renderer and the active audio bridge.

- **D2GL frame limit:** 60 fps by default, with 30, Unlimited and Restore game frame settings. The launcher updates only recognized existing JSON fields after verified Wine cleanup and reports applied/skipped status. It disables D2GL V-Sync when selecting a foreground limit, as required by D2GL's own limiter. Configuration is not a measurement of achieved FPS.
- **Lighter effects:** disables existing supported bloom, FXAA and sharpening flags by default. Restore game effects restores launcher-owned values while preserving later user changes. Other config bytes, resolution, shaders, saves and binaries remain unchanged. No vanilla D2DX DLL or 540-line patch is substituted into PD2.
- **Audio:** 32 ms default buffering, with 64 ms and the previous 16 ms available; zero-progress writes cannot spin forever, and shutdown interrupts blocked writes before joining their threads. Diagnostics report real AudioTrack underruns, negotiated rate, actual buffer sizes, partial/error writes and write timing. Optional Eco audio selects a separately built ALSA module constrained to 22.05 kHz; Standard retains the original plugin. Eco can reduce sound fidelity.

Content redraw requests are coalesced with one pending callback, without blocking guest/X request handling. Cursor, menu and geometry updates remain immediate. The previous sleeping X Present limiter is disabled for PD2 to avoid stacking limits. Existing sleeping Sync waits and covered-window culling remain.

Install over the existing app; no Prepare runtime or re-import is needed. Open **Performance & temperature → Graphics & audio** for effects and audio choices. Stop and Play after changing settings. The accepted controller reader, Wine/Box64 baseline, launch arguments, display request and gothic UI are preserved.

## Validation

[CI 40613733110](https://github.com/Russianranger/pd2-android/actions/runs/40613733110) passes 464 Android tests, ARM64 assembly, the audio module/bridge gates and the packaged Wine controller recovery test. The offered APK's identity, signature and unchanged prior runtime assets are independently verified; exact hashes and source identity are recorded in [HANDOFF.md](HANDOFF.md).

A Thor test is still required for audible stutter, sustained frame times and temperature. Compare the same route, brightness, fan and charging conditions. Export support logs before Stop after the comparison; they now include allowlisted D2GL values and actual Android audio counters. Battery temperature is not CPU/GPU temperature.
