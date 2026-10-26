# 0.1.20 cursor motion retirement and Save/Quit compatibility test

The 0.1.19 device test still rejects Native after Save/Quit and reports sporadic
cursor continuation after stick release. Android input and the Wine HID producer
remain live during the failed Native retest; another paired reconnect does not
recover it. This preview fixes source-demonstrated cursor defects and provides
a different, optional controller-consumer compatibility test.

## Cursor changes

- Current neutral input, cancellation, device removal/change and route cleanup
  retire the repeating cursor task. Only its owning controller/source can update
  its vector, and an absent/replaced device cannot keep driving it.
- Late samples and retired/reentrant callbacks cannot restart movement in a new
  route. A held stick continues without requiring repeated Android events.
- Centering the stick also cancels ordinary movement still queued for the Windows
  helper. Initial cursor synchronization, clicks and balancing releases remain.
- Removed controllers cannot leave a deferred thumb tap or half of a quick-menu
  chord for a later device. Already delivered Native thumb presses are balanced
  before that ownership is forgotten, without releasing other held controls.

## Native Save/Quit attempt

Under **Launch settings → Controller → Native input path**, choose **XInput
compatibility (experimental)**. It requests a different game-side controller
consumer while keeping Wine's exposed HID device and the existing runtime.
It applies only after **Stop client → Play**. Selecting it also arms the existing
one-launch API trace for a short test; export before Stop. Future launches keep
the selected input path but do not repeat that trace unless explicitly selected.

The game may ignore these preferences or override them. **Save/Quit recovery is
not yet established.** Returning to **Standard Native** restores the ordinary
environment on the next Play. The input-trace choice can be turned Off separately.
Controller notifications must stay enabled and the original Wine driver remains
the test baseline; no fresh identity/reconnect or driver experiment is required.

The Native and Input trace choice lists are now visible: their explanatory text
uses the title area instead of occupying AlertDialog's list content area.

Version **0.1.20/code 21** preserves Wine 9.2 Custom, Box64 0.4.4, rootfs 24,
controller/HID/XInput binaries, existing bindings, graphics/GameNative/Stability,
imports/prefix/saves, cleanup and signing identity. Install over the current app
and reuse the runtime and import. See [Testing](TESTING.md),
[evidence](evidence/2026-10-25-savequit-cursor.md) and [Handoff](HANDOFF.md).
