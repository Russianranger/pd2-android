# Controller and rendering implementation pass — 0.1.19

Base: `a0107242f2817af45981022d13dbc2e682dbd1aa` on `main`.
Research reference: D2Android 1.4.1 source at
[`2cd50c3`](https://github.com/N1v0k/D2Android-Releases/tree/2cd50c37293598e23dc423a0a0b68886d6bf5b47/source).

## Controller changes

`WinHandler` previously drained every controller action before every helper
action. Menu button/key releases use the helper queue, so a later Native packet
could overtake them. Both queues now carry a common sequence and, after helper
INIT, merge in enqueue order. Before INIT, controller delivery retains its
independent path. Socket readiness and reentrant enqueues are preserved.
This establishes host send ordering, not ordering of processing across different
guest UDP consumers. The latest physical input failure has not been attributed
to this race.

`ExternalController` and `Pd2InputRouter` normalize source-declared trigger ranges,
support BRAKE/GAS and RX/RY alternatives, and reject unsupported/synthetic key
events. Ranges from another input source cannot turn absent-axis zero into a
signed trigger midpoint; unrelated-source motion retains a prior analog hold.
Fallback trigger hysteresis preserves the previous press threshold while
stopping chatter on release. Native analog LT/RT remains analog; no new Native
binding layer or controller identity/reconnect experiment is introduced.
The Activity rejects synthetic key events before thumb-chord processing.

## Rendering change

Motion-only invalidation now checks whether a cursor is drawn or the viewport
follows its position. It includes the independent gold Menu reticle. Windows
helper cursor feedback uses the same decision as X11 pointer motion. Window
content, cursor-shape and cursor-visibility invalidations remain unconditional.
Enabled overlays are treated conservatively because the point window can change
after the renderer receives a motion callback.

This is intended to reduce redundant compositor work; it is not a measured FPS,
latency, battery or temperature improvement on Thor.

## Research and compatibility boundary

D2Android's controller design informed the trigger/event audit and its renderer
informed motion-only redraw suppression. Its own controller code is MIT; inherited
Winlator renderer code is LGPL 2.1. Our changes retain this repository's existing
Winlator licensing. No D2Android binary, vanilla game hook, fixed game address,
save mutation, gameplay helper or DLL is included. D2Android's direct-command
module targets vanilla LoD 1.13d and gates gameplay commands to single player;
it is not a drop-in PD2 Native controller backend.

## Qualification

Automated checks and the exact implementation CI run are recorded in
[HANDOFF.md](../HANDOFF.md). The previous producer code fails the expanded trigger
regressions, and the previous dispatcher fails the global-order regression.
Physical initial Native, Menu click acceptance, mode handoff and Save/Quit
recovery remain device gates. Preserve the accepted runtime and use the combined
test in [TESTING.md](../TESTING.md).
