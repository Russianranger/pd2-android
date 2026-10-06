# 0.1.16 physical controller census and restart acceptance

## Capture and attribution

The user reports that full Stop/Play within the same Android process now restores Native gameplay, but Save/Quit and character re-entry still lose Native. Both explicit fresh-identity recoveries failed to restore gameplay. These are separate physical results: accept full-session recovery and reject the identity experiment; do not repeat previous focus or reconnect trials.

The locally inspected `pd2-support-20261006-132517.zip` is 241,940 bytes, SHA-256 `a2731b06967154370ddfd3e18a1aa432378aa6c951fe8f02d503d8d8b388c580`. Live main was `4e95e21e92185c45737373a6afd833372d397e14`, implementation `802ddf622764c0956ac8d407630268223326d9fc`, version 0.1.16/code 17. The older refused 0.1.15 attempt in this export must not be attributed to the successful 0.1.16 replacement.

## How many controllers are exposed?

| Layer | Established by this capture |
| --- | --- |
| Android | Eleven InputDevices; exactly one accepted gamepad, ID 92, named Xbox Wireless Controller, VID 8224/PID 274. Virtual keyboard ID -1 and ODIN virtual mouse ID 11 are rejected. |
| Dedicated Wine HID bus, port 7950 | One active gamepad parent at a time, initially UID 0, then UID 1 and UID 2 during recovery. |
| Wine child interfaces | `IG_00` visible gamepad and `XI_00` hidden XInput children share the same parent and report buffer. They are two API representations of one pad. |
| Legacy XInput, port 7949 | An independent UDP representation of the same selected Android pad, in slot zero. Four-slot API capacity and probes of indices 1–3 do not prove additional connected pads. |
| DInput | No DInput discovery requests in the matching controller reports. |
| Old Wine identities | UID 1/2 registry paths remain visible after replacement but fail opening with `0xc0000034`; they are not demonstrated live devices. |

The pinned [winexinput driver](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/winexinput.sys/main.c) explicitly describes these paired child objects. The retained failed-session trace contains six sequential parent allocations. Each of the five retired parents receives SURPRISE_REMOVAL and REMOVE_DEVICE before the next captured allocation. Raw handles progress `0x5`, `0x7`, `0x9`, `0xb`, `0xd`, `0xf`; these are not six simultaneously live controllers. The failed trace is an exported head/tail snapshot of a larger log, so its omitted middle is not complete evidence of every event.

There is no evidence of a second physical pad overriding the selected controller. Independent HID and legacy XInput consumers remain a possible API ownership/correlation issue. Existing traces show calls rather than return values or accepted input, and discovery packets previously did not retain client process identity. Do not equate a successful UDP bind, reply, or queued HID report with fresh state returned to Game.exe.

## Failed in-session experiment

Launch `55a0defb-4127-4a5d-aae3-09c48cb4d5ab` starts at 18:19:27.343 UTC (13:19:27.343 CDT). Both identity operations reach exact detach, new backend and device-start observations. The last reaches `deviceStartObserved` at 18:22:01.998 UTC. Three ordinary reconnects also occur.

The final backend retains PID 26708 and socket inode 39927940 with UID 2, flags 3, created 6/removed 5/started 6/stopped 5, states received 3,361/reports queued 2,598, invalid 0. Java still handles Android motion/key events and sends non-neutral states. After the first UID 2 creation, the retained trace has 152 XInputGetState calls, 288 GetRawInputData calls and 433 device-info calls for raw handle `0xd`. The later same-UID recreation has another 50/92/139 calls for handle `0xf`.

This demonstrates actual identity replacement and subsequent consumer API activity, while the physical result rejects gameplay recovery. It weakens stale HID identity alone as the explanation. It does not establish returned XInput status, packet freshness, read cancellation or the game's internal controller state.

## Accepted same-app replacement

Android PID 26433 remains unchanged across both launches. Stop at 18:22:43 UTC passes in 726 ms: all nine verified private-prefix clients exit, zero unknown identities remain, both ports are exclusively bindable, and no residual SIGKILL is required.

Replacement launch `6563c413-56a2-42a2-9219-b791f4fa2d61` starts at 18:22:50.183 UTC. Preflight passes in 208 ms. Producer generation changes from 1 to 2; backend PID becomes 27901, socket inode 39928244, UID returns to baseline 0, and lifecycle counts reset to created 1/removed 0/started 1/stopped 0. User-reported Native gameplay works. Final Stop at 18:25:13 UTC passes in 628 ms and releases all nine verified clients and both ports.

Accept this complete same-app restart result independently of in-place Save/Quit. Preserve the 0.1.16 cleanup and accepted controller mappings while pursuing a different in-session hypothesis.

## Next source-level candidates

The matching [HIDclass device source](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/hidclass.sys/device.c) contains the inverted early-cancellation predicate corrected by upstream [4ba2f77f](https://github.com/wine-mirror/wine/commit/4ba2f77fb6714d98285d8a07a8b6fbf85c0a9080). Handle close/cancel is a different hypothesis from replacing the Wine device. The same source removes a closing queue once under its device list lock and again during destruction; interleaved handle creation can make that second unlink remove another queue. Neither condition is proven to occur in this physical trace.

A matching-source, bounded and reversible driver experiment needs separate binary/ABI qualification before inclusion. A HID-backed XInput source is another distinct possibility, but changing both drivers and XInput simultaneously would confound the physical result. Legacy-only is not a new experiment: it already failed Native activation before 0.1.6 added HID.
