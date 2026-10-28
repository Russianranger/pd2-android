# PD2 Android 0.1.25 Preview

The user reports continued FPS lag, improved audio with Eco, and a black striped cursor square on 0.1.24. This update restores the prior default frame behavior and adds a reversible cursor fallback. It does not claim a measured FPS improvement.

- **Game frame settings is the default again.** Absent or invalid frame-limit preferences select restoration of launcher-owned values through the existing verified journal. Explicit 30/60/Unlimited choices persist. Game settings also removes the optional Android content redraw delay; the accepted independent 60 Hz display request remains.
- **Classic cursor fallback:** Performance & temperature → Graphics & audio → Game cursor. It disables an existing D2GL HD-cursor flag so the original game cursor can draw. Use game cursor setting restores the owned value. Unknown fields are kept, and last-launch application/failure is visible.
- **Better FPS diagnosis:** support logs now include sampled per-process CPU execution, the actual recognized shader/window/frame-latency/cursor settings, and bounded before/after configuration changes. This adds no timer or per-frame logging.

Eco audio remains the user's saved choice; the capture confirms it negotiated 22.05 kHz. Audio buffering, accepted controller repair, Wine/Box64, game DLLs, saves, resolution and gothic launcher are retained.

Install over the existing app without preparing the runtime or re-importing. First select **Game frame settings**, then **Stop client → Play**. If the square remains, try **Classic cursor** followed by Stop and Play. Export support logs while playing after a comparable route. See [TESTING.md](TESTING.md) and [capture/source evidence](evidence/2026-10-27-cursor-performance-followup.md).

Automated qualification and the exact offered APK identity will be recorded in [HANDOFF.md](HANDOFF.md). Physical cursor recovery, audible quality, FPS and temperature remain device checks.
