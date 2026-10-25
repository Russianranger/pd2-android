# 0.1.19 controller transitions and hidden-cursor rendering

This preview fixes source-demonstrated input producer and dispatch defects. It
does not claim that the outstanding physical Native/Save-Quit failure is resolved.

- After the Windows helper initializes, Menu mouse/key releases and Native
  controller packets retain their global enqueue order. Controller packets can
  still run before helper initialization. This prevents a later Native send
  overtaking an earlier Menu release; guest processing order remains unmeasured.
- Native right-stick input accepts RX/RY controllers as well as Z/RZ. Trigger
  values use ranges declared for the event's source, including signed ranges and
  BRAKE/GAS aliases. Unrelated-source motion preserves an existing trigger hold.
  Existing Native analog authority and LT/thumb/shoulder mappings remain.
- The mouse/keyboard fallback retains its 0.35 trigger press threshold and uses
  0.25 for release, preventing threshold noise from generating repeated potion
  presses. Digital and analog owners still balance independently.
- Synthetic fallback keys, repeated presses and unsupported key actions cannot
  duplicate controls or release a physical hold. Real key-up still balances its
  press. The Activity filters synthetic keys before its reserved thumb chord.
  Detached-device motion is ignored safely.
- Pointer movement alone no longer redraws the Android compositor when its
  cursor is hidden. Windows cursor feedback follows the same policy. The gold
  Menu reticle, visible cursors, magnifier and pointer-following view still
  redraw; game content, cursor shapes and visibility changes are unaffected.

Version **0.1.19/code 20** retains Wine 9.2 Custom, Box64 0.4.4, rootfs 24,
the accepted controller backend/HID binaries, imports/prefix/saves, graphics and
GameNative arguments, Stability, cleanup, container protections and signer.
Install over the existing app; no runtime preparation or re-import is needed.
The original Wine driver and controller notifications remain the test baseline.

Implementation and validation boundaries are recorded in
[the evidence note](evidence/2026-10-24-controller-render-pass.md).
Follow the combined [device test](TESTING.md). Existing next-Play API tracing
remains available and defaults Off; performance comparisons must use trace Off.
