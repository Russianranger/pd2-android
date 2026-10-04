# 0.1.9 Native failure after character re-entry

The user reports Native controller remains unresponsive after Save/Exit and after re-entering the character; Menu cursor still works. The accompanying screenshot shows the app's white pointer over PD2's gauntlet cursor. This rejects 0.1.9 foreground recovery as a complete physical fix while preserving the accepted Menu cursor path.

Bundle: `pd2-support-20261004-051251.zip`, SHA-256 `1423653fa28db94444d9c5288fa7e3420be963574bf13d9c8d5bf39094cc5637`. App 0.1.9, launch ID `c8596309-54ce-4913-8b01-fc0eed21f522`.

| Observation | Boundary |
| --- | --- |
| User explicitly reports failure after character re-entry | The failure extends beyond title/character-menu navigation |
| Menu cursor remains usable | Retain the accepted pointer/helper navigation path |
| Resumed Native interval contains stick activity but no face-button key attempt | Does not establish a successful PD2 controller activation attempt after returning to Native |
| Foreground recovery requests were available in 0.1.9 | Queued requests do not acknowledge Windows foreground success or PD2 input consumption |
| Screenshot shows white pointer and game gauntlet together | Supports a separate app-overlay visibility setting; does not identify the controller failure cause |

[Published 0.1.9 CI run 37191608087](https://github.com/Russianranger/pd2-android/actions/runs/37191608087) succeeded. The physical failure remains authoritative for device qualification.

0.1.10 attempts a Java-side HID soft reconnect whenever Native is explicitly selected, including a reselect retry. The existing protocol briefly advertises the HID device absent, then present, while the legacy XInput device remains connected and neutral during an absence interval of at least 600 ms. The reconnect begins after Native regains the active input/focus gate; ordinary modal/lifecycle changes alone do not trigger it. Foreground recovery and the accepted Wine/HID assets remain in place. No controller/game DLL patch, prefix migration, or runtime preparation is part of this attempt. A requested reconnect is not proof that PD2 recovers.

Input-gate loss cancels reconnect work, with neutral device-advertisement cleanup if needed; generation guards reject stale queued reports. A 2.5-second timeout bounds an unfinished reconnect. `controller.json.nativeReconnect` records request, successful detach/attach sends, Java-side completion, cancellation, unavailable/failure/timeout, and last phase/time. `nativeStateDelivery` records successful HID/XInput state sends by neutral/non-neutral category. None of these counters acknowledges Windows PnP completion or PD2 consumption.

The 600 ms interval starts after the absence UDP packet sends successfully. Periodic discovery remains absent during that interval; Java-side completion requires successful present and neutral-state sends. Stop cancels without late restore callbacks. The Java producer revision is `java-hid-7950-v2`, while the packaged Wine backend remains `wine9-hid-1`.

The separate saved **Hide white cursor** option controls the app's root white pointer rendering. It defaults Off and preserves the game's cursor and pointer input. If the game supplies no cursor in Menu mode, hiding the app pointer can remove the visible navigation aid; turn the option Off there.

The physical test explicitly selects Native after the character has loaded, waits about one second, then presses/releases a face button before checking sticks. If needed, repeat the explicit Native selection once. Record the first selection and retry separately, export logs, and check the cursor option independently. See [Testing](../TESTING.md). The exact cause and recovery remain unconfirmed.
