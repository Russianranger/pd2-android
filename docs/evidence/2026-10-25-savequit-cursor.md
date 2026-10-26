# 0.1.19 device result and 0.1.20 follow-up

Base main: `7e4ff2f26ba48bcb5759f22ed7f6417a55484a99`.
Inspected `pd2-support-20261025-202057.zip` locally, without executing or publishing
its contents: **343,900 bytes**, SHA-256
`03297782fac0350bc834d4d8b69591c169b34e89a9db19afa1e7be660e3c2892`.

## Physical result and capture limits

The user rejects Native after Save/Quit on 0.1.19 and reports occasional pointer
continuation after releasing the mouse stick. The capture uses the accepted
controller backend and original HIDclass, with Turnip/Zink, Stability and retained
GameNative arguments. It has no applied API-return trace.

The two current launches start at October 26 01:17:29.629 and 01:20:08.644 UTC
(October 25 20:17:29.629 and 20:20:08.644 CDT). The first has Native → Menu →
Native → Menu phases. During its failed Native retest, paired reconnect completes
at 01:19:23.111 UTC, followed by Android motion and non-neutral HID/XInput sends.
Its totals include 1,703 Native motions, 68 keys, 1,608/1,603 successful non-neutral
HID/XInput sends and 1,857 backend states/1,474 queued reports. Menu has 239 move
requests and 239 feedbacks. The replacement launch receives 751 Native motions,
12 keys and 693/692 non-neutral sends. Both show one physical pad, one XInput
client, no transport errors and no low-memory flag. Cleanup passes in both.

The complete current log bodies contain 1,484 and 603 XInputGetState entries.
They establish neither returned controls/freshness nor API success or game
acceptance. The failed Native retest continues querying index zero, so a sticky
XInput user-index hypothesis is not supported. Continued two-second discoveries
from the same process also weigh against a dead legacy reader thread. A separate
helper binding the same XInput port would compete with the game and is excluded.

The report lacks raw stick/neutral timestamps. It does not prove the exact
physical source of cursor drift. Late Menu output after the game disappears is
visible, but does not establish that this caused the reported stick-release case.
Archive filenames denote archive time: `*1792977449547` contains an older October
10 launch and must not be counted as another current attempt.

## Cursor source defects and changes

The old router reused one repeating task with a global last vector and no
device/source ownership or cancellation path. Old callbacks and late motion
could reactivate a retired route; reentrant callbacks could inject the remainder
of an old event into a replacement route. Cleanup time fences must also reject
samples from the cleanup millisecond. Motion is now owned, generation-bound and
retired on neutral/cancel/lifecycle/device events. Continuous holds are preserved;
there is no event-rate timeout because Android need not repeat a steady stick.

The Menu helper previously allowed queued moves for the whole active route even
after the stick centered. Its separate motion generation now expires those
ordinary moves while leaving initial feedback/recentering and click/key ownership
intact. InputManager removal/change notifications reach the router; destruction
unregisters the listener. Thumb deferred/chord state retires per removed device.
Previously delivered Native thumb bits are released before shortcut ownership is
forgotten, so a later physical Up that resolves to NONE cannot leave a thumb held.
That device-scoped cleanup preserves its other controls and every other device.

The existing Input trace dialog combined AlertDialog's message with a choice
list. AppCompat uses the message content instead of attaching that list. Both
that dialog and the new Native input-path dialog now keep explanations in a
custom title and expose their actual choice lists. This is a UI source defect;
the archive does not show whether the user attempted to select the old trace.

## Different controller-consumer candidate

The new optional launch policy requests:

| Hint | Value |
|---|---|
| `SDL_JOYSTICK_RAWINPUT` | `0` |
| `SDL_XINPUT_ENABLED` | `1` |
| `SDL_JOYSTICK_HIDAPI_XBOX` | `0` |

Pinned [SDL2 XInput](https://github.com/libsdl-org/SDL/blob/4c2d9014afda49553c76f7045529207bb593f9b5/src/joystick/windows/SDL_xinputjoystick.c)
prefers the RawInput backend and suppresses its own XInput devices when RawInput
is enabled. [RawInput initialization](https://github.com/libsdl-org/SDL/blob/4c2d9014afda49553c76f7045529207bb593f9b5/src/joystick/windows/SDL_rawinputjoystick.c)
honors the hint before registering usages. [SDL3](https://github.com/libsdl-org/SDL/blob/2296712f9f7ab4cb00678b55352c5bc9f43321ee/src/joystick/windows/SDL_xinputjoystick.c)
has the equivalent ownership preference. Environment hints override normal/default
SDL hints, but a game can use override priority or a different implementation.

The exact private PD2 linkage and consumption of these hints are unproven. The
launch report records requested policy and environment, not successful backend
selection. If honored, the relevant SDL gamepad/joystick RawInput registration
and reads should disappear while XInput calls continue; generic mouse/keyboard
RawInput and Wine HID enumeration may remain. Absence requires complete early
log coverage. Returning to Standard constructs the original managed environment.

Wine's HID producer stays present. This is distinct from the pre-0.1.6 trial that
removed that producer, and from the already failed reconnect/identity actions.
No game DLL, XInput DLL, HID driver, renderer or CPU setting is changed. A single
short compatibility selection arms the existing trace with its existing verified
cleanup/journal/restoration. Performance must not be evaluated during that trace.

Automated results and the exact offered APK are recorded in [HANDOFF.md](../HANDOFF.md).
Cursor behavior and Save/Quit recovery still require the combined physical test.
