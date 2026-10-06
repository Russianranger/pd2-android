# 0.1.16 controller identity and session cleanup preview

This follows the 0.1.15 trial that stayed open, still lost Native input after Save/Quit, and refused same-app Play with cleanup not ready. The new logs show successful wineserver wait with six surviving Wine clients and occupied controller ports. [Evidence and research](evidence/2026-10-06-controller-session-recovery.md) distinguish the teardown defect from the still-unproven controller recovery hypothesis.

- Full Stop now uses Wine's immediate SIGINT shutdown path before its bounded server wait. Verified remaining clients of the captured private prefix are retired using UID, executable, canonical prefix and PID/start-time checks. Replacement startup still refuses unknown or occupied controller ownership.
- The gear menu adds **Recover Native controller (experimental)**. After Save/Quit failure, this creates a fresh Windows HID identity, instead of reusing the identity from earlier unsuccessful reconnects. Old-device removal and stop acknowledgments precede attach; retries, neutral input and cancellation are bounded.
- Backend reports include session/UID, PID, socket inode and aggregate lifecycle/state/report counters. A device-start message means Wine's Unix callback ran; PD2 movement still needs physical verification.
- Controller/process workers end with their tasks. Old process streams cannot be routed into a newer launch's callbacks, and PD2 stop no longer falls back to an unverified stored PID.
- Support export retains matching launch/controller/memory reports for the last four runtime attempts, so a refused Play cannot overwrite all gameplay evidence.
- The exact previous controller module upgrades only with its verified original backup. Unknown modules are preserved; disabling notifications restores the original.

Version 0.1.16/code 17 retains Wine 9.2 Custom, Box64 0.4.4, rootfs 24, the existing PE HID/XInput drivers, application/signing identity, graphics, GameNative arguments, Stability, prefix, import, saves, LT/thumb and shoulder fixes, cursor options and container protections. The dedicated matching Unix winebus module advances to `wine9-hid-2`; this is a controller experiment, not runtime preparation or a new runtime stack.

Install over the existing app. **No Prepare runtime, re-import, uninstall or clear storage.** Follow [Testing](TESTING.md): capture the failed Save/Quit state, try the explicit experiment once, export, then fully Stop/Play in the same Android process and export again. Automated checks do not qualify Native recovery or gameplay stability; completed validation and APK identity are recorded in [Handoff](HANDOFF.md).
