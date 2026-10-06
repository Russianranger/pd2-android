# 0.1.15 gameplay memory diagnostics

The 0.1.14 physical attempt reached character gameplay, then Android recorded
a low-memory termination 34.8 seconds after launch. The old export did not
identify which process died or preserve memory before termination.

0.1.15/code 16 records bounded five-second device/app/process memory checkpoints,
retaining the latest 24 samples. Android exit records include PID, process
name, UID, importance, reason label and sampled PSS/RSS. Controller reports
are refreshed with these checkpoints so abrupt exits do not leave only the
startup snapshot. Reports remain bounded, scoped to their launch, and omit
input values, command arguments, binaries and saves.

Wine 9.2 Custom, Box64 0.4.4, rootfs 24, runtime assets/native code, graphics,
GameNative arguments, Stability, accepted input fixes, container management,
application ID and preview signer remain. Full Wine-session cleanup and
socket startup repairs stay in place.

This diagnostic preview does not claim to fix the Android memory kill. Install
over the existing app, enter the same character, and keep the same settings.
If it closes, reopen the launcher and export **before pressing Play again**.
If it stays open, play for two minutes and export, then continue the retained
Save/Quit versus same-app Stop/Play controller gate. No Prepare runtime or
re-import. See [Testing](TESTING.md), [evidence](evidence/2026-10-06-gameplay-low-memory.md)
and [Handoff](HANDOFF.md) for findings, limits and verification.

Local validation: 184 tests across 23 suites passed with zero failures/errors/skips,
and ARM64 assembly passed. CI and final APK verification are recorded in the handoff
once complete; automated checks do not qualify physical gameplay stability.
