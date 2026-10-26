# 0.1.20 physical result and bounded XInput reader follow-up

Inspected main `7d247c06a1edae14f4204b80f26a46910163fd66` and the supplied
`pd2-support-20261026-145905.zip` directly. Archive SHA-256:
`cae13460553a220ea1244aca2781e234c97eafb48c7db1507d0779fc9f7254f0`.
This note records aggregate evidence and source locations, without publishing
device identifiers, process IDs, socket identifiers, character names or network
addresses from the capture.

## Physical result and capture coverage

The user reports the cursor working so far after the 0.1.20 motion-retirement
changes, while Native input still fails after Save/Quit. Treat the cursor result
as provisional acceptance and the Save/Quit result as a failure; automated checks
do not replace either physical result.

The current launch starts at **2026-10-26 19:57:39.584 UTC**. Its captured Native
input gate is active from 19:57:39.740 to 19:58:43.759, then again from
19:58:50.726 to 19:59:01.258. The intervening inactive interval is an Android
quick menu. These timestamps identify launcher input ownership, not the exact
instant the game saves, leaves a world or enters another world.

The original current runtime log is **5,629,069 bytes**. The exported snapshot is
**2,097,152 bytes**, retaining its beginning and end and explicitly omitting the
middle. Counts below are counts of retained evidence, not a complete chronology.
Archived attempts have their own launch metadata and must not be attributed to
the current launch merely from their archive filenames.

| Retained API evidence | Observation | What it establishes |
|---|---|---|
| `XInputGetState`, user index 0 | 335 calls, 335 returns `ERROR_SUCCESS` (0) | The game obtains a connected-success response for its selected slot in the retained portions. |
| `XInputGetState`, indices 1, 2 and 3 | Three calls per index, nine returns `ERROR_DEVICE_NOT_CONNECTED` (1167) | Other-slot probes are disconnected, as expected with one pad. |
| `GetRawInputData`, null destination | 331 calls, 331 returns 0 | Successful size queries; 0 is not by itself an input failure. |
| `GetRawInputData`, populated destination | 331 calls, 331 returns 39 bytes | The Windows API returns report bytes; their control values and game acceptance are not measured. |

The selected relay records scalar arguments and returns, not the pointed-to
`XINPUT_STATE`. A success return therefore does **not** establish that
`dwPacketNumber` advances, that returned buttons or axes are fresh, or that PD2
uses those values. The XInput caller return address `0x1018469b` and Raw Input
caller `0x10183f13` fall inside the loaded `ProjectDiablo.dll` image at
`0x10000000`. No separately loaded SDL module is established by this log.

Android handles 1,531 Native motion events and 32 keys. Java records 1,421
successful non-neutral HID sends and 1,413 successful non-neutral legacy XInput
sends, plus **one failed Native-state send**. The general reply/socket/invalid
packet counters remain zero. Preserve that distinction: this is not an entirely
error-free transport capture, and the failed-send category does not identify a
reader exit or its cause.

Wine's HID observations include 1,615 received states, 1,311 queued reports and
zero rejected packets, with one physical Android pad assigned to one slot. An
explicit fresh-identity request also appears and reaches the recorded backend
start stage. It does not establish game acceptance or reverse the user's failed
Save/Quit result. Further focus, reconnect and identity repetitions are not the
next qualification step.

## The SDL compatibility request did not establish another consumer

The launch metadata requests `xinput_compatibility` and its environment contains
all three requested hints:

```text
SDL_JOYSTICK_RAWINPUT=0
SDL_XINPUT_ENABLED=1
SDL_JOYSTICK_HIDAPI_XBOX=0
```

The one-launch API trace is recorded as applied for this launch; its three owned
registry fields are restored after Stop with no preserved conflicts. Nevertheless
the game still registers page 1 usages 5 (gamepad) and 4 (joystick), with flags
`0x2100`, and continues the same Raw Input and XInput calls. The environment is
proof of a request, not proof that the private PD2 consumer honors SDL settings.

The published [SDL2 initialization and ownership rules](https://github.com/libsdl-org/SDL/blob/4c2d9014afda49553c76f7045529207bb593f9b5/src/joystick/windows/SDL_rawinputjoystick.c#L1026)
supported testing those hints when the consumer implementation was unknown. New
primary reverse-engineering research provides a more specific architectural
lead: the [fd2 Controller analysis](https://github.com/Nugwa628/fd2/blob/fe0cb1897ccfd99c6cb59e22a5bd2699a9fd9451/4_pd2_source/ProjectDiablo/features/Controller/README.md)
describes PD2's own XInput/Sony-HID implementation, including the same two Raw
Input registrations and flags, four XInput slot records, an active-device
selection and direct D2Client actions. In that implementation the Raw Input
parsers handle Sony controllers, while Microsoft `IG_` paths are treated as
XInput duplicates. Raw Input traffic is therefore not proof that the Microsoft
virtual HID reports drive the character.

That reconstruction is **not official PD2 source** and targets a different
binary: its [inventory](https://github.com/Nugwa628/fd2/blob/fe0cb1897ccfd99c6cb59e22a5bd2699a9fd9451/README.md)
describes a 4.3 MB `ProjectDiablo.dll`, while the installed image in this report
is 3,239,936 bytes. Its addresses and data layouts
must not be applied to this installation. It supports the inference that the
SDL-specific preferences are inapplicable to the observed native consumer; it
does not establish a current-version binary patch or the Save/Quit cause.

The reconstructed [leave-game path](https://github.com/Nugwa628/fd2/blob/fe0cb1897ccfd99c6cb59e22a5bd2699a9fd9451/4_pd2_source/ProjectDiablo/features/Controller/ControllerShared.cpp#L79)
resets glyphs, targets and input, then continues out-of-game polling. It provides
no evidence that Save/Quit requires another Wine device replug. Its recovered
[command handler](https://github.com/Nugwa628/fd2/blob/fe0cb1897ccfd99c6cb59e22a5bd2699a9fd9451/4_pd2_source/ProjectDiablo/_unattributed/between_client_Sound_SoundSample_and_functions_D2CommonFunctions_unit26_10259d70.cpp#L883)
also implements `.debugcontroller`, whose overlay shows the game's accepted
controller state. Availability in this installed version remains unverified; no
command is injected automatically. The recovered
[poll](https://github.com/Nugwa628/fd2/blob/fe0cb1897ccfd99c6cb59e22a5bd2699a9fd9451/4_pd2_source/ProjectDiablo/features/Controller/Controller.cpp#L1001)
maps returned control fields directly, without a packet-number gate. Changing
packet numbers alone is therefore not a source-backed repair for that
implementation. Its own **Controller Movement Off → On** option also
[clears the selected-device/mode state](https://github.com/Nugwa628/fd2/blob/fe0cb1897ccfd99c6cb59e22a5bd2699a9fd9451/4_pd2_source/ProjectDiablo/features/Controller/Controller.cpp#L5987)
while off; if available in this version, that is a separate game-side reset test,
not a Wine reconnect or a proved recovery.

## Source-demonstrated reader lifecycle fault; causality remains open

The accepted custom Wine revision is
`a4ef2bf8963fe4bfab390eebf73336364ce209bc`. Its
[`xinput1_3/main.c`](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/xinput1_3/main.c)
is also the parent source for
[`xinput1_4.dll`](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/xinput1_4/Makefile.in).
The relevant retained behavior is:

- `controller_read_thread_proc`, lines 292–355: a failed initial socket setup
  returns; an unexpected receive error breaks the loop. Neither exit resets the
  cached connected/state fields or makes the one-time initialization retryable.
- `start_read_thread`, lines 377–380: the reader is started through a static
  `INIT_ONCE`, so later API calls do not restart an exited reader.
- `xinput_get_state`, lines 437–451: a cached connected flag allows success and
  a copy of the stored state. It has no reader-health or last-receive-age check.
- `controller_update_state`, lines 222–289: accepted UDP state updates increment
  `dwPacketNumber`. The existing API-return trace does not inspect that number.
- `XInputEnable`, lines 409–420: updates `controller.enabled`, but the retained
  state getter does not test it. A disabled flag is not a demonstrated cause for
  this custom runtime's failure.

This is a real lifecycle gap in the pinned source: an exited reader can leave a
successful cached response behind. **The capture does not prove that the reader
died**, that this happened at Save/Quit, or that repairing it restores gameplay.
Discovery timing alone is insufficient: the receiver sends its periodic request
from the would-block branch, so continuous queued traffic can affect that timing.

The next bounded implementation is an **opt-in, exact-image XInput reader
lifecycle repair**, with reliable reader ownership/cleanup and recovery, retaining
the existing controller protocol, mappings and runtime family. Its builder,
source-derived tests, exact original-image checks, installer rollback and
physical qualification must be reviewed separately before a pass is claimed.
It must not start another helper that loads XInput or binds port 7949, change the
game DLLs, or be described as a proved Save/Quit fix.

The separate [Pocketward bridge](https://github.com/Pocketward/pd2-launcher/blob/4de8d7235dd6cfd85fff1ddcba9fff37e398dbe9/tools/xinput-bridge.c)
illustrates a shared-file transport with fresh reads, but replacing the accepted
transport/runtime with that architecture is not this bounded repair. No
Pocketward implementation or fd2 reconstructed game code is copied into the app.

The targeted candidate still requires CI and a physical before/after check that
distinguishes initial Native behavior, Save/Quit/re-entry, and the already
accepted full same-app Stop/Play path. Preserve the provisional cursor acceptance
and all accepted import, save, graphics and CPU settings during that check.
