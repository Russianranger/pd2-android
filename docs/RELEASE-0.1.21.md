# 0.1.21 optional XInput reader recovery

The 0.1.20 cursor fix is provisionally accepted on the Thor. Save/Quit still
breaks Native input. The new capture verifies successful XInput calls in the
failed retest, but not the freshness of the returned controls. The previous
SDL preferences did not establish a different consumer.

Wine 9's donor XInput reader can exit on a receive error while leaving its
last state marked connected; its one-time initialization prevents recovery.
The new opt-in **XInput reader repair (experimental)** targets that concrete
source defect. It recovers reception after errors, invalidates expired state,
keeps discovery alive during continuous input, and retains the existing
64-byte transport and accepted controls. Bounded reader diagnostics record
execution and state freshness without exporting raw controls.

Only the verified 32-bit runtime `xinput1_4.dll` changes in this mode, after
successful Wine cleanup. Its original is verified and backed up. Selecting
**Standard Native** restores that original on the next clean Play. Unknown
files are preserved. HID exposure, Wine 9.2 Custom, Box64 0.4.4, rootfs 24,
bindings, graphics/GameNative/Stability, imports/saves, cleanup and cursor
behavior are retained.

Install over the current app. Stop, select **Launch settings → Controller →
Native input path → XInput reader repair (experimental)**, Play, check initial
input and one Save/Quit/re-entry, then export support logs before Stop.
Selecting repair does not arm the separate API relay trace. Recovery remains
unqualified until the physical test passes. Version **0.1.21/code 22**.

See [Testing](TESTING.md), [evidence](evidence/2026-10-26-savequit-xinput-reader.md),
[runtime source](../runtime/xinput-reader/README.md) and [Handoff](HANDOFF.md).
