# 0.1.17 first-boot controller report and settings mismatch

## Capture and attribution

The user reports: Native and Menu cursor do not work, even on first boot. Treat this as a new failed first-launch result, separate from the previously accepted 0.1.16 full Stop/Play recovery. Do not infer a Save/Quit result from this attempt.

The locally inspected `pd2-support-20261006-192433.zip` is 202,583 bytes, SHA-256 `d9ce3319a52d8a9f2896a6aedf622c010c4b7c53ee8d7db495c4b069c2b80916`. At inspection, repository main is documentation commit `23a277bb1dcc83ad7fd605e83e324efd3f0f041c`, implementation `fde447f8ba4f7d428d4bc5201a6da495ae6dbd93`, 0.1.17/code 18. The export contains matching current launch/controller reports plus archived 0.1.16 attempts. The current runtime snapshot contains the complete 367,700-byte original log, with an export header added; it has no omitted middle.

Times below are October 7 UTC; subtract five hours for the user's October 6 CDT evening. `support.json` records Android PID 27750. Its latest previous-process exit is PID 26433 at 00:19:48.088 UTC, reason `USER_REQUESTED`, description `stop com.pd2.thor due to installPackageLI`. This records an app upgrade rather than a new crash claim.

## Accepted HID backend was disabled in this launch

| Evidence | Earlier accepted replacement | Current failed first launch |
| --- | --- | --- |
| Launch ID | `6563c413-56a2-42a2-9219-b791f4fa2d61` | `36285153-38e8-4afc-82e3-a000206d91f1` |
| Start | October 6, 18:22:50.183 UTC | October 7, 00:20:30.312 UTC |
| Controller notifications | Enabled | Disabled |
| Installed Unix Winebus SHA-256 | `8f306c818432e94379efd9ae01b37126261c4d6a267beebb3cdad6c8b642af3c` | `4ca5b1dd5f2d56ae9ca3090f356b700f4e5282b6915a195719d0a1ab66128117` |
| HID discovery requests / device replies / state replies | 71 / 71 / 2,058 | 0 / 0 / 0 |
| Last observed dedicated backend | PID 27901, socket inode 39928244, UID 0, created 1 / started 1 | None |

The earlier values are retained in `attempts/launch-attempt-1791332430264.json`, `attempts/controller-attempt-1791332430264.json` and `attempts/runtime-attempt-1791332430264.log`. The current values agree across `launch.json`, `support.json`, `controller.json` and the current runtime launch header. Disabling notifications restores the verified donor Winebus module; it removes the dedicated Android UDP HID backend required by accepted Native gameplay. The current runtime loads Winebus PE/Unix modules, but records unsupported SDL/UDEV/IOHID buses and no `pd2-xbox-slot0` parent or Raw Input device addition. A loaded Winebus module alone does not demonstrate the dedicated backend is active.

The HID read experiment is enabled and recorded as applied for this launch at 00:20:31.943 UTC. Its installed SHA-256 is the exact patched `def30d1b2b6ada06c0ce04d96a8055c67b2f07f2d10f622960ad259f75a64dcb`, and the original backup verifies. The runtime loads builtin AMD64 HIDclass at `000000027F8B0000`. However, the required accepted Winebus backend is disabled. This is a confounded experiment, not an isolated physical rejection or acceptance of the cancellation correction. The export does not identify who or what changed the notifications preference.

## Android input and Menu cursor bounds

Android still enumerates eleven devices and accepts exactly one physical controller, ID 92, Xbox Wireless Controller, VID 8224/PID 274. Java handles all 353 received motion events and all seven key events. Producer generation is 1, selected device 92, HID UID 0. There is no evidence of another physical gamepad overriding it.

The existing legacy XInput representation remains active: one declared Windows client PID 192 sends 111 discoveries, all from port 7949; no DInput discovery occurs. Java records 269 successful XInput state sends, including 116 non-neutral and 153 neutral states. These are producer observations, not XInput return values, packet freshness or PD2 acceptance. The declared Windows PID is not an authenticated Linux socket owner.

Native mode handles 136 motion/five key events. Menu cursor, selected at 00:21:33.275 UTC, handles 217 motion/two key events and emits 24 pointer move requests with 24 feedback events, zero button events and zero keyboard events. Recorded X pointer positions change, and input is available while testing with the quick menu closed. These establish that Android input and pointer production continue; they do not qualify visible cursor movement or accepted clicks in PD2. The two key events cannot be identified as attempted clicks because the export intentionally contains no pressed-key values. The final inactive input gate, unavailable socket and disabled Native state are captured after Stop and must not be projected backward over the gameplay attempt.

## Cleanup remains successful

Preflight passes in 214 ms: wineserver kill status 1, wait status 0, both ports exclusively bindable and no verified prior-prefix clients. Stop at approximately 00:24:17 UTC passes in 640 ms: kill/wait status 0, all eight verified same-prefix clients exit, zero unknown identities remain, no residual SIGKILL is required and ports 7949/7950 are free. The controller report is captured at 00:24:18.587 UTC. This attempt does not reproduce the earlier cleanup-not-ready problem.

## Next physical gate

Use the existing 0.1.17 APK to restore **Launch settings → Controller → Controller notifications enabled (default)** and **Save/Quit experiment → Original Wine driver (default)**. Complete **Stop client → Play** so the accepted `wine9-hid-2` backend and verified original HIDclass are installed after clean teardown. Do not Prepare Runtime, re-import, clear data or rebuild merely to change these settings. Confirm initial Native movement and one button; check the reported Menu cursor failure and export while the client is running if either still fails. Restore the accepted initial-play baseline before another Save/Quit experiment.

Full same-app restart remains physically accepted from the earlier 0.1.16 evidence. In-place Save/Quit recovery remains unresolved. This capture proves a changed required backend setting and continuing Android input, but does not prove the HID read correction caused the first-boot failure or explain the reported Menu cursor behavior.
