# Controller and cursor investigation — 2026-10-04

The user reports that Native still fails on Save/Quit, the white pointer remains in the main menu, LT + L3 does not reliably toggle run/walk, and shoulder buttons have difficulty switching in-game menu tabs. This continues the earlier Native failure after Save/Exit and character re-entry. Initial Native gameplay and Menu cursor navigation remain accepted. The latest support bundle belongs to **0.1.10**; no physical 0.1.11 result is available.

Research started at **2026-10-04 12:55:09 UTC**. The requested research window runs until **13:55:09 UTC**. Completion time and final validation will be recorded after the window ends; this note does not claim that the full hour has already elapsed.

## Findings and priority

| Priority | Finding | Resulting action | Evidence boundary |
| --- | --- | --- | --- |
| 1 | The first digital LT/RT event permanently disabled analog processing for both triggers; Native L3/R3 were deferred until release, collapsing held combinations into a tap | Preserve each trigger independently and deliver Native thumb-button down/up immediately | Source bugs and focused regressions establish the bridge correction; physical LT + L3 still requires qualification |
| 2 | PD2 switches to mouse/keyboard when the mouse moves or clicks; disabled/zero-distance captured pointer events and unpaired raw mouse releases were possible during route changes | Honor the captured-pointer input gate, suppress motion that rounds to zero, and release only mouse buttons actually held | Concrete source behavior and official mode-switch design; no recorded mouse event is proven to have caused this failure |
| 3 | The 0.1.10 HID reconnect reached Wine twice, while its independent legacy XInput device stayed connected | Test a bounded reconnect of both controller paths together, preserving the accepted runtime and installation | A controlled experiment addressing inconsistent device lifetime; Save/Quit recovery remains unconfirmed |
| 4 | The cursor option originally controlled only the root arrow, leaving a separate Wine/X11 pointer layer eligible for drawing | Apply the option to root and Wine/X11 cursor overlays and Android pointer icons | Rendering correction; the PD2 framebuffer cursor remains independent of controller health |
| 5 | PD2 released relevant controller connection and new-game shoulder fixes after Season 11 | Establish the actual imported client version; consider a separate comparison with an updated, independently backed-up installation if bridge qualification still fails | Season 11 branding is a lead, not proof of the installed DLL version or a promised Save/Quit fix |
| 6 | Exact PD2 LT + L3 and shoulder-tab bindings, frontend support, and internal controller reset logic are not documented in the accessible public sources | Use the actual client's Controls menu and observed behavior as the binding authority; record Save/Quit UI, title/character menus, and gameplay re-entry separately | Do not import Diablo II: Resurrected bindings or label this failure an intended frontend limitation |

## Latest device capture

The bundle's matching launch is `489baebe-9c00-4d95-8594-36c352c43c66`, captured at **12:51:10.100 UTC**. It identifies app 0.1.10, Wine 9.2, rootfs 24, and `wine9-hid-1`. Accepted pad: Xbox Wireless Controller, Android device 92. Backend SHA-256: `541523c1e21059a386cfd60f6f18354c05b28ca2457faf867221911b545c4b0e`. Captured `runtime.log` SHA-256: `a090c6b04497f2432c1b53117862547582993f6c82d6f9a45695cc931a53a6f0`.

| Measurement | Recorded count | Meaning and limit |
| --- | ---: | --- |
| Native handled Android motion / keys | 4,171 / 200 | Aggregate activity; no button identity or trigger overlap |
| Menu cursor handled motion / keys | 460 / 12 | Separate temporary navigation route |
| HID successful nonneutral / neutral sends | 3,754 / 839 | 4,593 successful UDP sends; no game acknowledgment |
| Legacy XInput successful nonneutral / neutral sends | 3,755 / 791 | 4,546 successful UDP sends; no game acknowledgment |
| Java reconnect request / detach / attach / complete | 2 / 2 / 2 / 2 | No cancellation, failure, or timeout in this capture |
| HID discovery requests / replies | 168 / 172 | Extra four replies agree with two explicit absent/present pairs |
| XInputGetState calls | 1,598 | Index 0: 1,583; indices 1–3: five each |
| GetRawInputData calls | 3,154 | 1,577 size/data pairs; not 3,154 distinct reports |
| GetRawInputDeviceInfo calls | 4,733 | Handle 0x5: 3,021; 0x7: 769; 0x9: 943 |
| RegisterRawInputDevices calls | 1 | Two usage entries in one registration |
| GetRawInputDeviceList calls | 0 recorded | Absence in this trace does not establish all internal enumeration paths |
| Transport reply / invalid-packet / socket / state-send failures | 0 / 0 / 0 / 0 | Success at the producer/socket boundary |
| Queued Native foreground requests | 17 | No foreground-success acknowledgment |

Wine removes and recreates the HID child twice. Initial raw handle 0x5 is followed by 0x7, then 0x9. Game thread `00f4` queries the newly created handles and continues reading raw input. The trace therefore rejects the narrower claim that the Java reconnect never reaches Wine. It also rejects a claim that all controller API requests stop after the initial game.

After the last Java reconnect completes at **12:50:29.125 UTC**, the last handled Native key occurs at **12:50:34.516** and the last successful nonneutral Native send at **12:50:39.696**. The new capture includes post-reconnect key activity, unlike the weaker 0.1.9 observation. It does not identify the key, API return status, simultaneous LT + L3 state, or accepted game action.

PD2 registers page 1/gamepad usage 5 and joystick usage 4 with flags `0x2100`, combining background raw-input delivery and device arrival/removal notification. [Microsoft's RAWINPUTDEVICE documentation](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-rawinputdevice) explains these flags. This weakens a simple missing-foreground explanation, while leaving game logic and XInput acceptance unresolved.

Runtime lines have no wall-clock timestamps or PD2 screen/state markers. Their exact alignment to Android menu selections, Save/Quit, or gameplay re-entry cannot be reconstructed. Return codes and controller values are absent. The HID producer suppresses unchanged reports, so Java-send/raw-report count differences do not prove dropped packets. Transient missing-device errors during detach are followed by successful reattachments.

## Synchronized controller lifetime experiment

The Wine 9.2 legacy XInput path on UDP 7949 maintains its own cached selected-device/state identity, separate from the HID lifetime on UDP 7950. The actual donor source was inspected at [wine-9.2-custom main.c, commit a4ef2bf8963fe4bfab390eebf73336364ce209bc](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/xinput1_3/main.c). Receiving a zero controller ID on the legacy discovery protocol makes GetState return `1167` (`ERROR_DEVICE_NOT_CONNECTED`); later nonneutral state pushes while absent do not reconnect it. Receiving a positive ID restores a neutral cached state and resets its packet counter. An ordinary positive discovery reply during an intended gap would reconnect it early.

A temporary host harness extracted the pinned production dispatch/GetState functions and passed six assertions under ASan/UBSan: connected initial state, zero-ID disconnect, ignored state while absent, persistent absence, neutral/packet-zero restoration, and premature reconnect from a positive discovery reply. This validates the source-level protocol behavior, not Windows/PD2 integration.

The latest game trace rescans XInput indices 0–3 after each HID removal. 0.1.10 supplied HID absence while legacy XInput remained connected and neutral, so the game could observe different lifetimes through the two APIs. This is a plausible recovery issue, not a demonstrated PD2 reset algorithm.

0.1.11's Java-only experiment requires the existing legacy subscription. It serializes legacy absent-discovery and disconnected-state sends before HID absence, attempts cleanup on both transports even if one send fails, measures the 600 ms gap from required successful detach sends, and keeps periodic legacy discovery/polls/pushes absent during that gap. Attach restores legacy neutral and HID neutral before normal forwarding. The 2.5-second watchdog, generation guards, route cancellation, unsubscribe handling, and stop guard remain. Producer revision is `java-native-reconnect-v3`; packaged `wine9-hid-1` is unchanged.

Successful UDP sends do not acknowledge that Windows received the intended status, and order across separate UDP sockets is not guaranteed at the receiving boundary. Public PD2 source cannot establish whether this reconnect resets the client's controller selection. The experiment must be assessed by the physical re-entry result rather than completion counters alone.

## Trigger and thumb-button source defects

`ExternalController` previously had a session-wide latch: the first digital LT/RT event permanently disabled subsequent analog trigger processing for both sides. Mixed or asymmetric reporting could therefore stop a usable analog trigger after a digital event on either trigger. Before that latch, motion also updated both trigger values without checking whether that side had a usable axis. 0.1.11 makes analog authority independent per trigger, preserves digital-only sides during motion, and prevents a digital full-press release from cancelling a still-held partial analog trigger. The legacy Wine XInput ABI still represents trigger output digitally; this is not a runtime migration to analog XInput.

The activity's L3/R3 quick-menu interception previously held a single thumb press until release, then forwarded a rapid down/up pair. Native gameplay therefore could not observe that thumb button held while another input changed. `Pd2ThumbButtons` now forwards Native single-thumb down/up edges at event time. The L3 + R3 shortcut remains reserved. Because the first thumb goes to Native immediately, it can perform a game action before the second thumb opens the gear menu; opening the menu releases the delivered input. Pointer routes retain their deferred single-thumb navigation behavior.

This directly addresses the user's reported LT + L3 symptom at the Android bridge, without asserting that the exact default PD2 binding is established upstream. Shoulder buttons already use direct Native edges in the inspected path. No equally concrete shoulder-specific Android remap bug was found; a shoulder-tab fix is not claimed.

## Mouse events and automatic PD2 input switching

The official [Season 11 developer recap](https://www.reddit.com/r/ProjectDiablo2/comments/1k8p6if/project_diablo_2_season_11_dev_stream_3_recap/), authored by the PD2 lead developer, describes controller-button activation and a return to mouse/keyboard upon mouse movement or clicking. This makes unexpected pointer input a relevant hypothesis, particularly around gear-menu closure and Native selection.

The app intentionally permits real touch/mouse input in Native. Captured input reaches `TouchpadView.onCapturedPointer` directly and bypasses ordinary activity generic-motion counters. Before 0.1.11 this callback did not honor the enabled gate and injected movement even if the relative delta rounded to zero. Pointer-position notifications can still be emitted for unchanged coordinates. Route cleanup also requested release of all seven X11 mouse buttons; ordinary button state suppressed some releases, but raw-button release events were emitted unconditionally by the injection path.

0.1.11 blocks captured forwarding while the input view is disabled, skips captured movement that rounds to zero, and balances only mouse buttons actually held in activity cleanup. Shared-router cleanup emits one release per held control and output owner when multiple sources share a key or mouse button. Delayed touch-release callbacks now validate generation, finger ownership, and held state, and are invalidated when disabled; a stale callback cannot release a later press, and disabled touch-up cannot warp. It adds `inputByMode.*.pointerRouting` source/emission diagnostics and retains the latest pointer context separately for each mode, so subsequent Menu cursor navigation cannot prune the last Native geometry/focus context from export. Actual deliberate touch and mouse input remain available. Existing helper-route counters alone cannot rule out X11 captured/touch input: Native helper outputs are zero in the latest capture, but the retained 16 pointer contexts all belong to Menu cursor. The ODIN Station Virtual Mouse is excluded as a gamepad and remains eligible as a mouse; its presence does not prove it emitted an event.

Primary Android references: [pointer capture guidance](https://developer.android.com/develop/ui/views/touch-and-input/gestures/movement#pointer-capture) and [Android 13 ViewRootImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android13-release/core/java/android/view/ViewRootImpl.java), inspected blob `a13872eef6b80ccbadb09e785dbeb14b20e493b8`. The framework's captured relative-mouse/touchpad dispatch precedes normal generic motion, supporting the instrumentation boundary.

## Cursor layers and D2GL

The white arrow is separate from PD2's rendered gauntlet. 0.1.10's option controlled only the app's root arrow; the renderer could still draw a guest Wine/X11 cursor overlay. 0.1.11 applies **Hide white cursor** to both overlay branches and Android pointer icons. It remains saved and defaults Off. PD2's cursor drawn inside the framebuffer is unaffected. Hiding a pointer does not activate Native or demonstrate input recovery.

The official [Project-Diablo-2 D2GL fork](https://github.com/Project-Diablo-2/d2gl) was inspected at commit `ab34011f5e9979d56046506833c9cd254c7c3c97` (2026-07-12). Source references:

- [win32.cpp at the inspected commit](https://github.com/Project-Diablo-2/d2gl/blob/ab34011f5e9979d56046506833c9cd254c7c3c97/d2gl/src/win32.cpp): focus events lock/unlock the OS pointer; `setCursorLock` hides it and `setCursorUnlock` shows it. Unhandled messages are forwarded to the original window procedure. No controller or WM_INPUT-specific implementation was found in the public tree.
- [hd_cursor.cpp at the inspected commit](https://github.com/Project-Diablo-2/d2gl/blob/ab34011f5e9979d56046506833c9cd254c7c3c97/d2gl/src/modules/hd_cursor.cpp): a separate HD game cursor uses the game's cursor position and cursor-draw state.
- The window procedure intercepts Ctrl + Tab to unlock the pointer. [Upstream issue 182](https://github.com/bayaraa/d2gl/issues/182) describes this conflict with holding Ctrl for run and Tab for the map. This is relevant only if actual keyboard modifiers are being sent; the app's LT + L3 fallback maps to 1 + Tab, so the issue does not establish the reported Native chord cause.
- [Upstream PR 205](https://github.com/bayaraa/d2gl/pull/205) and [PR 206](https://github.com/bayaraa/d2gl/pull/206) concern unpaired mouse-up messages during focus changes. The inspected official PD2 fork already comments out those mouse-up sends. Applying an upstream patch blindly is not warranted.

These source findings describe the inspected public fork. They do not prove which D2GL build is present in the imported installation. The publicly available PD2/BH repositories did not expose the client's native controller-selection/reset code, so they cannot establish whether WM_INPUT immediately reads XInput or how Save/Quit resets controller mode.

## Historical unofficial XInput shim

The original author's [TaraHoleInIt/pd2_xinput_hack source](https://github.com/TaraHoleInIt/pd2_xinput_hack/tree/74622db13eb06cdac14498db7e467ea4e31e201a), commit `74622db13eb06cdac14498db7e467ea4e31e201a` (2025-06-11), was inspected read-only as a technical lead. This is a third-party experimental shim, not official PD2 documentation or a proposed installed DLL.

[hooks.c](https://github.com/TaraHoleInIt/pd2_xinput_hack/blob/74622db13eb06cdac14498db7e467ea4e31e201a/src/hooks.c) identifies the Diablo II window class, logs raw-input registration/data/device queries, posts a dummy device arrival after registration, and starts a 16 ms timer. [dllmain.c](https://github.com/TaraHoleInIt/pd2_xinput_hack/blob/74622db13eb06cdac14498db7e467ea4e31e201a/src/dllmain.c) clears the window property named `userData` and posts dummy WM_INPUT messages on that timer. [xinputmod.c](https://github.com/TaraHoleInIt/pd2_xinput_hack/blob/74622db13eb06cdac14498db7e467ea4e31e201a/src/xinputmod.c) forwards GetState/SetState to the ordinary System32 XInput DLL.

The source contains no Save/Quit detection, native mode/reset algorithm, button mapping table, cursor handling, or explanation of the property reset. It demonstrates an earlier attempt to compensate for absent raw notifications; our current capture already has real HID arrivals and ongoing game-side raw reads/GetState calls. That difference removes support for adopting this shim as the next fix. No downloaded game binary, shim DLL, or imported client file was modified or redistributed.

## Later PD2 changes and version comparison

The [Season 11 Patch 1 post](https://www.reddit.com/r/ProjectDiablo2/comments/1kpehj7/season_11_patch_1/) also fixes hotkeys after the currency tab was last active and RT/R2 being blocked while aiming at an ally. This adds another client-version lead for menu/input symptoms, without establishing that either condition matches the reported LT + L3 or shoulder-tab failure.

The official [Season 12 announcement](https://www.reddit.com/r/ProjectDiablo2/comments/1p8eg34/project_diablo_2_season_12_suffering_patch_notes/) links the [final English patch notes](https://docs.google.com/document/d/1sTGHhvm54IFq8tPz2NYrWd2kBcxHhygPRg3rGuiX_y0/edit). Relevant fixes include controller connections on some configurations, an LB/L1 crash after joining a new game, joystick direction, movement-option handling, aim-assist persistence, and D-pad item navigation requiring prior cursor movement. These demonstrate that early controller support had connection and menu defects; they do not promise a fix for this app's Save/Quit failure.

The official [Season 13 announcement](https://www.reddit.com/r/ProjectDiablo2/comments/1stzq23/project_diablo_2_season_13_betrayal_patch_notes/) links the [final English patch notes](https://docs.google.com/document/d/1biWlLF7x5uq5NSr171-w6eRh9yjoQ3btStpGeLipYM0/edit). It adds controller glyph selection and changes quickcast timing/input buffering, with controller gold/quickcast/quest/unplug crash fixes. None is explicitly a Save/Quit controller reset fix. The [Season 14 developer preview](https://www.reddit.com/r/ProjectDiablo2/comments/1wx13w6/project_diablo_2_s14_developer_stream_3_recap/) describes aim-assist options and trigger-release movement stopping; it is a preview, not a released fix to prescribe on 2026-10-04.

The screenshot's Season 11 label suggests checking the installed version. Branding alone does not establish DLL age. Keep the accepted prefix/import for the 0.1.11 test. Any later client comparison should use a separately backed-up working installation and a diagnostic plan, preserving the current files and offline saves. Do not use Prepare runtime, re-import, uninstall, or Clear storage as the controller fix.

## Limits and next physical result

If 0.1.11 still fails, measure delivered XInput status before another reconnect change. The pinned [Wine relay implementation](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/ntdll/relay.c) supports filtering to `xinput1_4.XInputGetState`. Inspection of the packaged 32-bit builtin DLL confirms its ordinal-2 relay thunk; packaged ntdll contains return-status tracing. A separately planned short trace can preserve/restore existing `HKCU\Software\Wine\Debug` settings, set `RelayInclude` before a fresh game process, enable `+relay`, capture one deliberate reconnect, then restore the prior trace policy. No registry/default trace change is part of 0.1.11.

Pair calls/returns by thread and controller index: index 0 should return `0000048f` (1167) during absence, then `00000000` after attach. Indices 1–3 may correctly remain disconnected. If index 0 never becomes disconnected, investigate transport reception/order; if it transitions correctly and PD2 still fails, further reconnect proposals lose support. Relay establishes status and call arguments, not pointed-to returned button values, so successful status still cannot qualify LT + L3 or shoulder timing.

The shoulder symptom also warrants a deliberate held press versus quick tap comparison. Java sends legacy XInput and HID from the same snapshot, but independent Wine receivers can process them at different times. A rapid release may reach the legacy cache before an older pressed HID report reaches the game. Repeated GetState/raw-read ordering is consistent with combining both paths, but the closed PD2 algorithm is unknown. A temporary production C harness already represents LT + L3 together, preserves held LT through shoulder/L3 changes, and emits both shoulder edges under ASan/UBSan; this weakens a C report-packing defect when Java supplies a correct snapshot. Avoid a competing UDP XInput consumer alongside the game because the donor uses a fixed shared port.

A lower-ranked architectural comparison would use upstream Wine 9.2's [HID-backed XInput source](https://github.com/wine-mirror/wine/blob/wine-9.2/dlls/xinput1_3/main.c), retaining the existing [winexinput driver](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/winexinput.sys/main.c). Winlator's own [Wine 10 bus transition](https://github.com/brunodev85/wine-10.10-custom/commit/c507e3fa64a415ecf81b1bf238f316467647bc39) removes its separate UDP XInput implementation in favor of a Winebus input source, supporting the design direction. This is a future ABI/import-verified, reversible diagnostic candidate, not a proposed runtime upgrade or proven PD2 fix. A unified device source removes independent connection/cache lifetime and restores analog XInput representation; it still cannot guarantee equal timing between an older raw report and a later GetState snapshot. No DLL was built, replaced, or added for this research.

Accessible official sources did not establish an exact LT + L3 binding table, shoulder tab-navigation rules, or guaranteed Native support in title/character selection and Save/Quit screens. The official wiki was inaccessible during this investigation; public controller implementation source was not found. Use the installed client's Controls menu and the user's known binding as the reproduction reference. Do not substitute D2R controls or describe frontend Native failure as expected behavior.

The useful next result is a short 0.1.11 comparison with the accepted settings: initial Native movement and LT + L3, left/right shoulder tab presses separately, Save/Quit UI, title/character menus, gameplay re-entry, explicit Native reconnect and a face-button activation, then the same chord/tab checks again. Record exactly which stage fails and export support logs. Run the cursor option independently in the main menu and in-game. The tests and build can qualify the changed bridge behavior; only the physical result qualifies PD2 recovery.

Local verification passed **122 tests across 16 suites**, with no failures, errors or skips; all host input/import/recovery/log and runtime checks passed, including the Wine backend sanitizer checks and all 206 final runtime pins. Final ARM64 assembly, package identity, matching preview certificate/V2 signature, ZIP integrity and alignment passed. All 72 runtime assets match the verified 0.1.10 CI APK byte-for-byte. Of 35 Android native libraries, 26 match exactly, eight differ only in build IDs, and VirGL rebuild differences were statically traced to source-path strings and address/relocation adjustments; this is not physical graphics qualification. No Wine/HID binary changes are included.

APK: `PD2-Android-0.1.11-preview.apk`, **174,055,860 bytes**. SHA-256: `0a307e4cde8a88312ec82f4413e76f71cf1f7d935a1597e86557081460e7a89c`. Identity: `com.pd2.thor`, 0.1.11/code 12, min SDK 26, target SDK 28, ARM64 only. Published source CI is pending; physical Save/Quit and shoulder-tab qualification remain pending.

Actual research-hour completion time is pending until the requested window ends.
