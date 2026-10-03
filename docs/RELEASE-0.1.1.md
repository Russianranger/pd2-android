# PD2 Android 0.1.1 startup diagnostics preview

The user reported that 0.1.0 closed immediately when opening the app, before gameplay. This preview corrects a possible foreground-service startup race and adds crash evidence for the next test. **The original crash cause remains unconfirmed, and a Thor fix is not yet qualified.**

## Changes

- Automatic installation checking no longer starts an import/export foreground service.
- Explicit import/export work waits until that service has entered foreground before work can complete and stop it.
- Uncaught Java exceptions are captured in a report capped at 128 KiB, while Android's original crash handling is retained.
- A recovery screen on the next launch offers **Export crash details** and **Retry launcher**. Retry retains the report; the normal support ZIP also includes it when present.
- The application ID and preview signing key remain unchanged for installation over 0.1.0 without removing private files.

## First retest

1. Install 0.1.1 over the existing app; keep the app installed and keep its storage.
2. Open the launcher and leave it visible for **five seconds**. Close it from recent apps, reopen, and wait another **five seconds**.
3. If both opens remain stable, use **Prepare runtime** only if needed. Reuse any accepted installation.
4. If it still closes, reopen and choose **Export crash details** on the recovery screen. Save/share `crash.txt` and upload it to the chat. If recovery never appears, report that too.

Crash capture covers uncaught Java exceptions when a report can be written; it cannot guarantee evidence for every process exit. Automated checks do not confirm startup on the Thor. Rendering, native dual-stick controls, input switching, offline-save persistence, and online gameplay remain pending from the original milestone. See [Testing](TESTING.md) for those later checks.
