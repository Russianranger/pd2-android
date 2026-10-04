# Native after Save/Exit: report and foreground-recovery scope

Latest user report: **Menu cursor works; Native does not.** The user suggested that Native might fail to lock onto the game window and requested continued work, a fiery PD2/skull icon, and a dark gear menu. Treat this as acceptance of the 0.1.8 Menu cursor and a continuing Native failure after Save/Exit. It does not establish that Native was tested after character re-entry or that PD2 supports Native in every title/character menu.

The latest supplied matching capture remains the 0.1.7 bundle documented in [menu-pointer evidence](2026-10-04-menu-pointer.md). Preserve its boundaries:

| Observation | What it establishes |
| --- | --- |
| Android focus/input gate reopened during Menu cursor | Android routing was available in that captured interval |
| Native was restored with `inputAvailable=true` and `windowFocus=true`; later motion was handled | Android Native route reopening was observed on 0.1.7 |
| HID discovery/state replies continued without reply/socket failure | The Java/HID transport remained active; PD2 consumption was not demonstrated |
| No captured device removal | Device disconnection is not established as the cause |
| User accepted Menu cursor on 0.1.8 | The new app pointer/helper path is usable by user report |

Source inspection finds that Menu cursor activation already asks the Windows helper to restore/foreground `Game.exe`, while the earlier Native route resumed Android/HID forwarding without the same foreground request. The helper can restore and foreground the matching game process/window. A Window map/resize can also accompany menu/game changes. This supports a narrow foreground-recovery attempt; it does not prove the device failure was caused by foreground ownership.

0.1.9 adds cancellable Game.exe foreground requests when Native regains input. Map/resize events belonging to the mapped game subtree restart a 75 ms debounce; one settling request follows 250 ms later. Each stage permits at most eight helper-readiness checks. Each request selects the current game HWND. Queue guards reject requests after Native loses ownership or a newer transition supersedes them, and Menu cursor's queued focus command now also expires on mode loss. Recovery sends no pointer/key input. The accepted Wine/HID backend and device identity are unchanged, with no replug, prefix migration, aspect-ratio change, or game-DLL patch.

`controller.json.nativeFocusRecovery` adds `requests`, `lastRequestAt`, `lastReason`, and `scope`; reasons are `route`, `window`, and `settle`. This counts requests queued through the existing helper. It does not acknowledge Windows foreground success or PD2 consumption.

The physical comparison must distinguish **Native in title/character menus** from **Native gameplay after re-entry**. Use accepted Menu cursor navigation if necessary, then select Native after the character loads and press a controller button. Repeat and check gear/launcher resume. See [Testing](../TESTING.md). There is no new device-success claim.
