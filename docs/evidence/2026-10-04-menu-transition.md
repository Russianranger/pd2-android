# 0.1.6 native input accepted; Save/Exit menu transition unresolved

Summary of `pd2-support-20261003-202610.zip`, SHA-256
`3e46ed4d373464ec45ae30129fccaa3fbccd2a1a85035c2b398e0e7f961f8b47`.
No full logs, game binaries, saves, or private installation paths are included.

The user confirms native controller input works inside a character after 0.1.6,
then reports that the main menu does not respond after Save/Exit. The user
suggested a widescreen/4:3 transition. Neither that cause nor official PD2 native
menu support is established. Detailed twin-stick bindings, saved progress, and
character re-entry are not yet qualified by this report.

| Stage | Recorded evidence |
| --- | --- |
| Accepted runtime | Wine 9.2, rootfs 24, `wine-9.2-pd2-1`, enabled `wine9-hid-1`; accepted GameNative arguments and Stability |
| Android pad | Accepted Xbox Wireless Controller, device 92 |
| Android input | 5,905/5,905 motion events and 110/157 key events handled |
| HID bridge | 119 device replies and 6,144 state replies |
| Legacy/combined bridge | 105 legacy discovery requests; 12,274 total state replies across both paths |
| Transport errors | No recorded reply, socket, or invalid-packet errors |
| Retained Wine trace | Raw-input handle 5 and continuing XInputGetState activity |
| Capture boundary | Session begins 20:22:08; old 8 MiB log cap reached 20:24:29; export at 20:26:10 |

These counters and retained calls establish bridge activity. Final availability,
socket, and mode flags describe snapshot time; they cannot establish their state
throughout Save/Exit. The cap means later menu-transition events may be absent.
The bundle does not prove a controller disconnect or an aspect-ratio failure.

## 0.1.7 comparison

Use manually selected **Menu cursor** for title/character screens, then return to
Native after loading. Test Save/Exit, Menu cursor re-entry, and Native again
without touch. Preserve the accepted runtime, prefix, imported files, and
renderer; no Prepare runtime or re-import is needed. Rolling startup/tail logs
and timestamped mode/gate history are intended to retain the next transition.
The physical result remains pending.
