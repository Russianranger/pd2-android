# 0.1.24 active-renderer and audio check

1. Install over the current app; reuse runtime, import and saves. Keep the accepted controller reader, Turnip/Zink, GameNative arguments, Stability and 60 Hz display request. Stop and Play.
2. Check initial Native input, Save/Quit/re-entry and cursor stopping. Check that game/menu/transparent content remains correct, then Stop/Play again to check audio and renderer teardown.
3. Start with **60 fps game limit**, **Lighter effects**, **32 ms Balanced audio**, **Standard audio**. The launcher reports the last D2GL applied/skipped result under Performance & temperature. Export while running after a few minutes. Inspect `launch.json.d2glSettings`: recognized schema, requested FPS, applied flags and allowlisted values. A skipped result means the game limiter/effects were not applied; do not infer success from the selected preference.
4. Compare the same 10–15 minute route, brightness, fan mode, charging state and starting temperature. `memory.json` includes `displayRefresh.audio` (actual rate, buffer size/capacity, underruns, partial/zero/error writes and write timing), `displayRefresh.contentRedraw` and `displayRefresh.compositor` counts. Counters establish path activity, not game FPS, audible success or CPU/GPU temperature.
5. If audio still stutters, compare **64 ms** with 32 ms after Stop/Play. Record whether music, effects or both stutter. Separately compare opt-in **Eco audio** with Standard at the same buffer size. Check `launch.json.audioRuntime` and the negotiated audio sample rate: Eco must actually negotiate 22050 Hz. Test music/effects, mono/stereo streams, title/game transitions, pause/resume and shutdown; report sound-quality changes.
6. Compare 30 fps only if lower rendering work is preferred over smoothness. Restore game frame settings and Restore game effects should restore launcher-owned values while retaining later user edits. The original backup remains. Do not replace DLLs or re-import merely to reset these choices.

No hardware gain is established by the automated build. An initially empty DirectSound buffer warning alone is not proof of Android underruns. Export before Stop when possible; capture any regression before changing other settings.

# Historical 0.1.23 graphics optimization check

The user accepts the gothic launcher and 60 Hz request but reports little performance/temperature improvement from 0.1.22. The controller reader and cursor fixes remain accepted. These changes reduce work in specific graphics paths; a build/test pass does not establish Thor FPS or cooling gains.

1. Install over the current app. Reuse the runtime/import and saves. Stop and Play with the accepted Turnip/Zink, GameNative arguments, Stability and default reader repair.
2. Check title/character screens, game textures, menus/overlays, one Save/Quit/re-entry and centered-stick cursor stopping. Check zoom or Menu cursor if used. Stop/Play again to check clean graphics shutdown.
3. Leave **Performance & temperature → 60 fps frame pacing (default)** selected. The separate **Display refresh** remains 60 Hz. Export while the game is running after a few minutes; support `memory.json` samples contain `displayRefresh.presentation`, `synchronization` and `compositor`. Nonzero counters establish whether the affected paths were exercised; zero Present requests means that backend was not capped.
4. Compare a 10–15 minute route with the same character/area, resolution, brightness, fan mode, charging state and starting temperature. Compare 0.1.23 60 fps with Unlimited for the pacing effect, or the prior APK for the complete change. Stop and Play between changes. Avoid an armed input trace. Check smoothness/input responsiveness as well as temperature.
5. If lower heat is the priority, try **30 fps frame pacing**. It trades visual smoothness for fewer submitted frames; game/display FPS and temperature must still be observed on the device. Leave 60 Hz unchanged.

The counters are aggregate source-path work counts, not displayed FPS, CPU/GPU utilization or direct power measurements. Battery temperature is not CPU/GPU temperature. The sample history remains bounded. Report stutter, missing/transparent content, stuck shutdown or input regressions with a support export.

# Historical 0.1.22 defaults, power and launcher check

The user confirms the 0.1.21 XInput reader repair works, including Save/Quit recovery. Keep that accepted DLL and the 0.1.20 cursor behavior unchanged. This pass promotes the repair to the default and reduces avoidable diagnostic/display work; it does not claim measured Thor FPS or temperature gains.

1. Install over the existing app, reuse the runtime/import/saves, **Stop client**, then **Play**. No manual controller selection is required. Keep notifications enabled, the original Wine HID driver, accepted graphics/GameNative arguments and Stability.
2. Check initial Native movement/button input, one Save/Quit/re-entry and centered-stick cursor stopping. Do not use the old identity/reconnect experiments during this regression check.
3. Check the gothic launcher and settings with the physical D-pad/sticks and touch. Verify clear focus, readable labels and access to all actions on the Thor's landscape display.
4. For a useful power comparison, keep screen brightness, charging state, game area, graphics settings and room conditions consistent. Leave Input trace Off. Play the same route for 10–15 minutes and export support logs before Stop. Battery temperature and Android thermal status are sampled observations; neither is GPU temperature or a direct power measurement.
5. **Performance & temperature** exposes **60 Hz display request (default)** and **Device refresh rate**. Stop and Play after changing it. Android may choose a different refresh rate; this is not a game FPS cap. Compare smoothness and battery/thermal history before concluding it helps on the Thor.

**Native input path → Standard Native** remains a manual fallback. Defaults migrate once; selecting a fallback afterward remains saved. Normal Wine logs keep warnings/errors and bounded reader diagnostics. Use **Input trace → Trace the next Play only** only for short failure investigations, not performance measurements.

# Historical 0.1.21 XInput reader recovery test

The user provisionally accepts the 0.1.20 cursor fix. Its Save/Quit experiment
still fails. The new repair targets Wine's XInput receive lifecycle and stale
state rather than another focus, identity, HID cancellation or SDL hint test.

Install over the existing app and reuse its runtime, prefix, import and saves.
Keep **Controller notifications enabled**, **Original Wine driver**, GameNative
graphics arguments and Stability. Leave the separate **Input trace** Off for
this test; the replacement reader includes bounded diagnostics of its own.

1. **Stop client**, then select **Launch settings → Controller → Native input
   path → XInput reader repair (experimental)** and **Play**.
2. Enter the same character and check initial Native movement and one ordinary
   button. If it fails, export support logs there instead of inferring a
   Save/Quit result.
3. **Save/Quit** once and re-enter. Test Native movement and one button for
   20–30 seconds. **Export support logs before Stop**, whether it works or
   fails. If successful, repeat Save/Quit/re-entry twice before exporting.
4. Stop when finished. **Standard Native → Play** restores the verified
   original XInput DLL after cleanup. No Prepare runtime or re-import is needed.

The report separates requested mode, installed module, executed reader and
returned-state freshness. Successful DLL installation or API calls alone do
not qualify gameplay. Do not run old reconnect or fresh-identity experiments
before the export. A manually armed relay trace remains a separate preference;
selecting reader repair does not turn it on or clear it.

# Historical 0.1.20 cursor and Save/Quit compatibility test

Install over the existing app. Keep controller notifications enabled, the original
Wine driver, accepted GameNative/graphics arguments and Stability. Reuse the
runtime, prefix, imports and saves.

1. Fully **Stop client**. Open **Launch settings → Controller → Native input
   path**, select **XInput compatibility (experimental)**, then **Play**. This
   selection arms one launch of API-return tracing. The choice list is visible;
   no separate hidden trace option is required.
2. Enter the same character and check initial Native movement/button input.
   In **Menu cursor**, briefly move and center each stick several times. The
   pointer should stop when centered, keep moving while deliberately held, and
   leave no motion after a quick-menu/mode change. The gold pointer remains
   visible with white cursors hidden.
3. **Save/Quit** once, re-enter the character with Menu cursor if needed, return
   to **Native**, and test movement and one button for 20–30 seconds. **Export
   support logs before Stop**, whether it works or fails. Report initial Native,
   cursor stopping and post-Save/Quit Native separately. If initial entry fails,
   export there instead of inferring a gameplay result.
4. Stop when finished. The trace's existing cleanup restores prior Wine debug
   filters; it is consumed once. XInput compatibility remains selected for later
   launches. Return to **Native input path → Standard Native** to restore the
   usual environment; an unconsumed manual trace can be turned Off under
   **Controller → Input trace**.

This is a distinct consumer-compatibility attempt, not a promised Save/Quit fix.
The game may ignore the hints. The new report records requested policy separately
from game acceptance. Avoid old focus/reconnect/fresh-identity trials before the
export. Do not use the traced launch for FPS or temperature comparisons.

# Historical 0.1.19 combined controller and pointer test

Install **0.1.19 over the existing app** and reuse the runtime, import and saves.
Keep **Controller notifications enabled**, **Original Wine driver**, accepted
GameNative/graphics arguments and Stability. No Prepare runtime or re-import.

1. Fully **Stop client**, then **Play** and enter the same character. Check
   initial **Native** movement and one ordinary button action. If entry/input
   fails, export immediately before Stop and report which stage failed.
2. Enable **Hide white cursor**, select **Menu cursor**, and check gold pointer
   movement plus one deliberate A click. Return to **Native**, release the
   controls, then check movement and a button again. This targets the changed
   release/send ordering and reticle redraw path.
3. **Save/Quit** once, re-enter using Menu cursor, return to Native and check
   movement/button acceptance. **Export support logs before Stop**, whether it
   passes or fails. State initial Native, Menu clicks, return-to-Native and
   post-Save/Quit results separately. If it fails, avoid reconnect/identity
   experiments before exporting.

Fallback users can separately confirm that LT/RT potion presses balance after
release; its mapping and the accepted Native LT/thumb/shoulder bindings are
preserved. Do not repeat the earlier controller identity/focus trials.

The existing **Input trace → Trace the next Play only** can capture Windows API
returns on a short follow-up if input fails. It is not needed for ordinary play;
keep trace Off for any performance or temperature comparison. Reduced hidden
pointer redraw requests are an automated policy result, not a measured Thor FPS
or power claim. Initial Native and Save/Quit recovery remain unqualified until
the physical results above are available.

# Historical 0.1.18 Menu visibility and Windows API return test

The latest [0.1.17 capture](evidence/2026-10-07-controller-boundaries.md) confirms both settings were restored but both input modes still fail by user report. Android input, non-neutral HID/legacy delivery and Menu movement/click requests continue. There is one physical pad; an overriding duplicate is not established. The previous configuration-only explanation is insufficient for this restored run.

0.1.18 provides a gold Menu pointer when **Hide white cursor** is On, plus a one-launch API return diagnostic. It does not claim Native or in-place Save/Quit recovery. Install over the existing app, preserving runtime/import/prefix/saves, graphics, GameNative arguments and Stability. **No Prepare runtime, re-import, uninstall or clear storage.**

1. Fully **Stop client**. Keep **Controller notifications enabled (default)** and **Save/Quit experiment → Original Wine driver (default)**.
2. Select **Launch settings → Controller → Input trace → Trace the next Play only**, then **Play**. This preference is consumed once; the trace stays active until Stop. Use a short test because tracing adds overhead. Existing runtime log retention remains bounded.
3. Select **Menu cursor**. With white cursors hidden, a **gold reticle** should mark the Windows pointer position. Test stick movement and one **A click** on the title/character screen, then enter the same character. Record visibility, movement and clicks separately. If entry fails, export now; do not infer a Native gameplay result from a title-screen test.
4. In character gameplay, select **Native controller**, press/release one face button and test ordinary movement for roughly 20–30 seconds. Avoid touch/mouse input during that short check. **Export support logs before Stop** whether it works or fails, reporting whether gameplay was reached.
5. Complete **Stop client**. Temporary relay filters restore their exact prior raw values/types/presence when still owned; unrelated and externally changed values are preserved. The next ordinary Play has no added relay channel. If restoration reports an error, export and retain the existing data.

`launch.json.inputTraceRuntime` records preparation/restoration for the matching launch, including the applied flag and pending restoration. `runtime.log` adds mode/active timestamps and selected relay Call/Ret lines. XInput return 0 establishes success status, **not fresh packet/state or accepted game actions**. A Raw Input size query can legitimately return 0; its buffer presence and command must be considered. The trace records scalar arguments and addresses, without dereferencing controller values. Menu SendInput success establishes OS insertion, not PD2 handling. The captured renderer flag `menuPointerCursorVisible` distinguishes the dedicated reticle from hidden white overlays.

Keep accepted LT/thumb and shoulder mappings. Do not repeat focus, ordinary reconnect, fresh identity or legacy-only experiments. Full-session cleanup is retained; the latest between-run Stop released all nine verified clients and both ports in 731 ms. Current Native failure after a clean session remains distinct from the older accepted 0.1.16 restart result.

# Historical 0.1.17 first-boot restoration

The latest user report says Native and Menu cursor fail on first boot. Its [support evidence](evidence/2026-10-07-first-boot-controller-settings.md) shows **Controller notifications disabled** and the original Winebus module, while the HID read experiment is enabled. Android receives controller events but the accepted HID backend never appears. This mixed configuration cannot qualify the experiment. Menu cursor has movement requests and cursor-position replies, but the reported game response remains unresolved.

Use the **existing 0.1.17 APK**, without reinstalling or rebuilding:

1. Fully **Stop client** and wait for cleanup.
2. Open **Launch settings → Controller**, select **Controller notifications enabled (default)**, then reopen that panel and choose **Save/Quit experiment → Original Wine driver (default)**.
3. Press **Play**. Check **Menu cursor** stick movement and an **A click** on the frontend, then enter the same character and check ordinary **Native** movement and one face-button action. Record the two results separately.
4. If either fails, **export support logs immediately before Stop**. The settings and installed module hashes in the new capture will establish whether the accepted baseline is restored.

No Prepare runtime, re-import, uninstall, clear storage, container deletion or old reconnect/identity experiment. Full same-app cleanup remains accepted and also passed in this capture. Save/Quit recovery remains unresolved. Resume the optional experiment below only after initial input works with both accepted settings restored.

# Historical 0.1.17 HID read-cancellation test

The new 0.1.16 physical result accepts Native after full **Stop client → Play in the same Android app process**. Save/Quit still fails, and both fresh-identity attempts failed despite successful Wine removal/start observations. The [new controller census](evidence/2026-10-06-controller-census.md) finds one physical pad and one active Wine HID parent, with paired child interfaces and an independent legacy XInput representation. Do not repeat the failed identity/reconnect/focus experiments or accepted binding tests.

Install **0.1.17 over the existing app**. Preserve the accepted runtime, import/prefix/saves, graphics/GameNative arguments, Stability and controller notifications. **No Prepare runtime, re-import, uninstall or clear storage.** The new experiment defaults Off, retaining the original driver.

1. Fully **Stop client** and wait for cleanup. Open **Launch settings → Controller → Save/Quit experiment** and select **HID read cancellation fix (experimental)**. Keep controller notifications enabled. The choice applies on the next clean Play; it does not change a running client.
2. Press **Play**, enter the same character and confirm ordinary Native movement and one face-button action. If the client does not start or initial Native fails, export immediately and use the rollback below.
3. Save/Quit, use Menu cursor to re-enter the character, select Native and test movement plus a face button. **Export support logs before Stop or any recovery action**, labeling whether Native worked. A successful driver install is not a gameplay result.
4. If it works, repeat Save/Quit and character re-entry twice more in the same client and export again. If it fails, capture that result and use the accepted full Stop/Play workaround; do not retry fresh identities.
5. To roll back, fully **Stop client**, choose **Save/Quit experiment → Original Wine driver (default)** and press **Play**. The verified original driver is restored after cleanup. Export if restoration or launch is refused. No Android force-stop is required to apply this setting.

Report initial Native and each Save/Quit re-entry separately. This targets a source-proven HID read-cancellation defect; the capture does not prove that defect causes PD2's symptom. Same-app session cleanup is unchanged and already accepted; retest it only if this preview produces a regression.

`launch.json.hidReadRuntime` records the requested/installed driver revision and exact hash after cleanup. `controller.json.controllerExposure` records selected slot counts, bounded declared Windows client IDs and request/reply port categories. These are diagnostic identity/counter fields, not control values, authenticated Linux process owners or proof that the game accepted input.

## Historical 0.1.16 test and results

The sequence below is retained as historical reference. The new physical capture completes its gates: same-app Stop/Play passes; fresh identity and in-place Save/Quit fail. Do not repeat it for 0.1.17.

Install **0.1.16 over the existing app**. Keep the accepted Wine 9.2 Custom, Box64 0.4.4, rootfs 24, import/prefix/saves, Turnip + Zink, GameNative Glide arguments, Stability and controller notifications. **Do not Prepare runtime, re-import, uninstall or clear storage.** LT/thumb, shoulders, Menu cursor and cursor/icon work are accepted and need no repeated qualification.

The latest 0.1.15 trial stayed open, lost Native after Save/Quit, and refused Play because controller ports remained occupied after server wait. This preview corrects server/client shutdown and adds a separate opt-in fresh Windows controller identity. [Evidence/research](evidence/2026-10-06-controller-session-recovery.md) records the diagnosis and limits.

1. Fresh app launch → **Play** → same character. Confirm ordinary Native movement and one face-button action; this checks the changed controller module's baseline. Save/Quit, use Menu cursor to re-enter, switch back to Native and test again.
2. If Native fails, return to launcher and **Export support logs before stopping or experimenting**. Resume the same client. This is the unchanged failing Save/Quit state.
3. In gameplay, open the gear and choose **Recover Native controller (experimental)** once. Wait for its message, up to eight seconds, then test movement and a face button. Record the message and whether input responds. Return to launcher and export again **before Stop**. If recovery is unavailable or times out, export that result too.
4. Save/Quit normally, then completely **Stop client** from the launcher. Wait for cleanup. Keep the Android app open, press **Play**, enter the same character and test ordinary Native again. **Export support logs** whether it works, fails or says cleanup not ready. Do not force-stop Android between Stop and Play.
5. If the Android app or client closes unexpectedly, reopen and export **before another Play**, with approximate duration and whole-app versus client-only closure. This one stable 0.1.15 trial did not establish a general memory-kill fix.

Report three outcomes separately: Native after Save/Quit; Native after the explicit identity experiment; Native after full same-app Stop/Play. A **Wine started the new controller device** message observes the Unix start callback, not completed Windows enumeration or PD2 acceptance. Native remains physically unqualified on this preview until these results arrive.

If full Stop/Play restores Native, accept that repair while keeping in-place Save/Quit separate. If fresh identity restores input, its repeatability remains to be established. If clean Stop/Play still fails, compare old/new backend PID/socket/session and Java input/state versus backend report counters; do not repeat blind focus/reconnect tests. If cleanup is refused, include the exact message and its verified residual-process/unknown-field/7949/7950 results.

New `controller.json` sections `nativeIdentityRecovery`, `hidBackend` and `controllerProducer` retain phases, generation/UID, selected Android device ID, backend PID/socket inode and aggregate lifecycle/state/report counts without input values. `launch.json.wineSessionCleanup` records shutdown signal, bounded server escalation, verified client identities and each exclusive port result. Support ZIPs retain matching launch/controller/memory JSON beside the last four runtime attempts, so subsequent refused Play attempts preserve future gameplay reports. Old overwritten reports cannot be recovered.

Current/older container management is retained and optional; deleting containers is not a cleanup or controller workaround. The older sections below preserve reference behavior and history. Use the 0.1.16 sequence above for this trial.

---

# 0.1.15 historical gameplay memory diagnostic preview

Install **0.1.15 over the existing app** and keep the accepted Wine 9.2/rootfs 24, imported installation, prefix, Turnip + Zink / Glide (GameNative arguments), Stability and controller notifications. **Do not Prepare runtime, re-import, uninstall or clear storage.**

0.1.14 reached character gameplay by user report, then Android recorded a low-memory termination 34.8 seconds after launch. The old ZIP cannot identify the killed process or memory owner. 0.1.15 adds bounded pre-exit memory/process checkpoints and refreshes controller reports every five seconds. This diagnostic preview does not claim a memory-kill repair. See [evidence](evidence/2026-10-06-gameplay-low-memory.md).

## First check: gameplay termination

1. Press **Play**, enter the same character, and use the accepted settings. Test normal gameplay for two minutes if it remains open.
2. If it closes, reopen the launcher and **Export support logs before pressing Play again**. Report whether the whole Android app closed or only the client returned, and approximately how long gameplay lasted. Do not stop/reset/delete containers to diagnose this termination.
3. If it remains open for two minutes, return to the launcher and export while the client is still running. Then resume and continue the retained controller gate below. Accepted LT/thumb and shoulder bindings do not need another qualification pass.

The new `memory.json` keeps the most recent 24 five-second samples (at most 512 KiB) for the matching launch. Device available/total RAM and pressure threshold are distinct from app Java/native heaps and visible same-UID process RSS. PID/PPID/start ticks and oom_score_adj help compare ownership. Proc visibility/read limits and unknown fields are explicit; aggregate RSS double-counts shared pages and is not total unique memory or GPU memory. Android exits include PID/process name/UID/importance/reason label plus sampled PSS/RSS, which are not final memory at death. Controller checkpoints contain counters/identity, not input values. A system kill cannot run the Java crash handler, so no crash.txt alone is inconclusive.

## Containers

Open **Advanced runtime settings → Containers**. Each row identifies **Current PD2 · protected**, **Older PD2**, or **Additional container**, with its number and Wine identifier. The current label uses the same ready managed-container selection as Play. Reading the list does not create/migrate a prefix or change the selected preference.

The current/persisted-selected/runtime-selected container is protected. With the client fully stopped and other operations finished, an eligible older container's three-dot menu offers **Delete container…**. Confirmation explains that its private C: drive is removed; back up any personal files kept only in that old container first. Imported PD2 files/saves and mapped-drive targets remain. Links are unlinked without traversal. The worker rechecks current selection, active runtime state and path/inode identity before removal; deletion blocks startup, creation and duplication until it finishes. If the current selection cannot be identified safely, all containers are kept.

- Verify the current row is clearly labeled and its deletion action unavailable.
- An older row may be protected while its prefix remains selected by the runtime; this is shown as **Deletion unavailable**.
- Delete only an older container you no longer need. Confirm it disappears, the current row remains, and Play still opens the same character.
- If deletion reports an error, refresh the list and export support logs. A storage/I/O failure can leave part of the older container; the app does not claim success or remove shared targets.

## Controller gate retained from 0.1.12

1. Fresh app launch → Play → same offline character: check initial Native movement and a face-button action.
2. Save/Quit → use Menu cursor to re-enter → select Native, wait about one second, press/release a face button and check movement.
3. Export the first support ZIP **before Stop**, labeled after Save/Quit.
4. Save/exit normally → Stop client → wait for cleanup → Play again **without force-stopping Android** → enter the same character and test Native.
5. Export the second ZIP labeled after same-app Stop/Play. If cleanup/launch is refused, export immediately.

If full Stop/Play restores Native, accept that restart repair while in-place Save/Quit remains separate. If it fails after clean cleanup, investigate surviving backend/process/device ownership using the records rather than repeating blind reconnect/focus experiments.

The older reference/checklists below preserve accepted behavior and historical boundaries; use the current 0.1.16 test above.

---

# 0.1.12 historical cleanup gate

The 0.1.11 device result accepts the LT/thumb combination and shoulder-button behavior. Native still fails after Save/Quit, and remains unavailable after stopping and relaunching Diablo II in the same Android app process. Force-stopping the Android app restores it. [Current evidence](evidence/2026-10-04-wine-session-cleanup.md) separates these two failure scopes.

0.1.12 repairs the confirmed Stop/relaunch teardown defect: the old launcher killed only explorer's PID, leaving Wine services and the controller port owner alive. It now shuts down the exact private Wine prefix, waits for server shutdown, verifies controller ports 7949/7950 are free, and refuses to start a competing client if cleanup is incomplete. Whole-environment cleanup and replacement startup are serialized on a worker; late old callbacks cannot clear a new launch PID. The report records both cleanup phases. Existing input routes, LT/shoulder fixes, runtime, prefix, import, saves and signer are retained.

**This is not a claim that Save/Quit inside the same running Wine session is fixed.** That result remains separately pending. No game DLL or Wine/HID binary is changed.

## Focused test

1. Install **0.1.12 over the existing app**. Keep the accepted runtime, prefix and import, **Turnip + Zink / Glide (GameNative arguments)**, **Stability**, and controller notifications enabled. Do not Prepare runtime, re-import or clear storage.
2. Enter the same offline character and check initial Native movement plus a face-button action. Save/Quit, use **Menu cursor** to re-enter if needed, then choose **Native controller**, wait about one second and press/release a face button. Report whether Native responds after re-entry. The LT/shoulder result is already accepted; no repeated binding qualification is requested.
3. If Native fails, return to the launcher and **Export support logs before stopping**, preserving the first session's controller counters. Save/exit Diablo normally, then **Stop client**. Wait for cleanup and the launcher to return. Keep the Android app open.
4. Press **Play** again in the same app session, enter the same character, select **Native controller** if needed and check movement/face-button response. This is the repaired restart path. **Export support logs** again, with both outcomes labeled. If launch is refused or cleanup fails, export immediately rather than resetting runtime/data.

New evidence: `launch.json.wineSessionCleanup.beforeLaunch` and `.afterStop` contain selected-prefix kill/wait statuses, exclusive controller-port release, elapsed time and passed/error status. UDP send counters alone still do not establish PD2 acceptance. A clean first launch can correctly have kill status 1 (no old wineserver), wait status 0 and free ports.

## White cursor check

Open the gear and set **Hide white cursor: On**. Check both the **main menu** and **in-game**: the app/root and Wine/X11 pointer overlays should disappear, while PD2's framebuffer cursor, such as its gauntlet, remains when supplied by the game. Confirm movement/clicks still work. Return to the launcher and resume, then relaunch later to check persistence. The option defaults **Off**; turn it Off if Menu cursor needs a visible pointer. Record cursor visibility separately from Native response.

The fiery PD2/skull icon and dark gear menu are retained. Report any clipped or unreadable controls.

Menu movement, click requests, and returned Windows cursor feedback are recorded separately. Request counts do not prove that PD2 accepted an input. `controller.json.nativeFocusRecovery` records queued foreground requests, without acknowledging Windows foreground or PD2 input consumption. Reconnect diagnostics likewise require a physical result; a requested reconnect is not proof of recovery.

`nativeReconnect` records Java-side reconnect phases and packet sends; `nativeStateDelivery` separates successful neutral/non-neutral HID and legacy state sends. Neither confirms PD2 input acceptance. The 0.1.10 trace independently shows Wine PnP detach/attach twice, yet the physical failure remains. 0.1.11 synchronizes legacy XInput lifetime with HID. New pointer-source diagnostics distinguish touch, external mouse, and captured forwarding by mode, and retain the latest context per mode through later Menu navigation; they record emitted requests rather than accepted game actions. If Native selection says **gamepad not ready to reconnect**, include Controller status and that message.

## Menu cursor bindings

| Thor input | Menu input |
| --- | --- |
| Either stick | Mouse cursor; right stick takes priority |
| A | Left click |
| B / Select | Esc / back |
| Start / R3 | Enter / confirm |
| L3 | Tab |
| D-pad | Arrow keys |
| L3 + R3 | Quick menu |

Menu cursor does not send WASD, skills, modifiers, triggers, or right click. It is temporary; choose Native after the character loads. The full Mouse / keyboard layout below remains available separately.

If the client unexpectedly stops reaching the title, export that attempt first. To restore the original controller backend:

1. Stop the client and choose **Launch settings → Controller → Controller notifications disabled**.
2. Force-stop **PD2 Android** in Android app settings, reopen it, and press Play. The verified original Wine backend is restored before launch.
3. Export this new attempt separately. Do not Prepare runtime, re-import, uninstall, or clear storage for rollback.

If the whole Android app closes:

1. Reopen the app. A captured Java exception should lead to **PD2 Android recovery**.
2. Choose **Export crash details**, save/share `crash.txt`, and upload it to the chat. Include whether it closed before or after pressing Play.
3. **Retry launcher** lets you try again and retains the report. If the launcher becomes accessible, **Export support logs** provides the full diagnostic ZIP, including that report when present.
4. If recovery never appears or no report can be shared, report that exact behavior. Some process exits cannot be captured by the Java handler. Do not uninstall or clear storage to retry.

Support export retains the current runtime log and up to four archived attempts, and adds:

- XInput/raw-input traces for the controller path, without the per-report HID trace. The live log continues recording after rotation: at 8 MiB it keeps approximately the first 512 KiB and latest 4 MiB. Export/archive snapshots remain at most 2 MiB each and keep startup/tail sections with capture metadata and omission markers.
- Up to 32 recent controller mode/input-gate transitions with timestamps and last handled input/reply times, alongside aggregate counters. Snapshot flags describe capture time, not the whole session. `support.json` labels the saved mode preference; `controller.json` records the current session mode.
- `controller.json` (at most 64 KiB) with Android device/bridge diagnostics for the matching launch, plus `inputMode` in `support.json`. Stale/invalid controller reports are skipped.
- HID discovery/device/state counters on port 7950 alongside the legacy counters, plus controller backend enable/revision/install status in the launch/support records.
- Wine/rootfs/runtime-revision identity for the replacement baseline in support and launch records.
- Selected installation/version/trace registry settings in `launch.json`.
- PE stack reserve/commit sizes and bounded core-file hashes in `installation-files.json`.
- The newest two dated D2 logs and available D2DX/D2GL logs under `game-logs/`, each capped at 256 KiB. Binaries and saves are not included.

The current runtime trace enables XInput/raw-input diagnostics instead of Fog export snooping. The fifth choice remains `-3dfx -dxnocompatmodefix` without `-w` and retains the native wrapper. Keep this accepted startup configuration for the controller test.

## After PD2 reaches the menu

Use your complete, updated **English classic Diablo II + Lord of Destruction + Project Diablo 2** installation. Preserve your working Winlator/GameNative copy and offline-save backup.

Title startup, initial Native input inside a character, Menu cursor navigation, LT/thumb combinations and shoulder behavior are accepted by user report. Native remains unavailable after Save/Quit and same-app client restart through 0.1.11. Use the focused 0.1.12 sequence above; the remaining sections retain reference bindings and later qualification checks. Do not prepare or re-import for this upgrade.

Export support information after the controller result before changing graphics settings. Further comparisons should follow diagnosis of that result.

## Native controller

Use a temporary offline character. PD2's controller mode activates on controller input; after selecting Native and closing the quick menu, press a controller button before checking movement/aiming.

The Wine 9.2 baseline keeps its legacy XInput bridge and adds a HID notification path for the same single selected gamepad. XInput triggers remain digital, and rumble is not supported by this donor DLL. The broader checklist below is retained for later qualification; LT/thumb and shoulder behavior do not need to be repeated for 0.1.12.

1. Move using the left stick, then aim independently using the right stick.
2. Check face buttons, shoulders, triggers, D-pad, inventory navigation, and a skill assigned through PD2's normal controller settings.
3. Open the quick menu using the gear, then separately using **L3 + R3**. Close it and confirm both sticks still work.
4. Report whether PD2 displays controller prompts and whether the controller is recognized as a Windows/Xbox device. If only mouse/keyboard mode works, native controller qualification remains incomplete.

The app reserves L3 + R3. In Native, the first thumb down is sent immediately so LT + L3 and other held combinations can work; that first press may perform a game action before the second thumb opens the quick menu. Opening the menu releases the input. Menu cursor and Mouse / keyboard retain deferred single-thumb navigation so the shortcut does not also send Tab/Enter.

## Mouse/keyboard fallback and quick menu

Select **Mouse / keyboard layout** in the quick menu. This fallback uses the following fixed layout; its left-stick movement requires PD2's WASD movement setting.

| Thor input | Windows input |
| --- | --- |
| Left stick | W/A/S/D |
| Right stick | Mouse cursor |
| A / B | Left / right click |
| X / Y | Shift / Alt |
| LB / RB | F1 / F2 |
| LT / RT | 1 / 2 |
| D-pad up / right / down / left | F3 / F4 / F5 / F6 |
| Select / Start | Esc / I |
| L3 / R3 | Tab / Enter |
| L3 + R3 | Quick menu |

1. Confirm right-stick cursor movement and A/B clicks. Adjust cursor speed and deadzone in the quick menu.
2. Open the Android keyboard and enter a short character name or chat message; confirm Enter and closing the keyboard work.
3. Switch between Native controller and Mouse/keyboard several times. Repeat while a stick is displaced or a button held, then release it. Confirm no movement, skill, click, or key remains stuck.
4. Use **Back to Launcher Menu**, then **Resume client** on the launcher. Confirm the same character/session remains open.

## Save, background, and online checks

1. In an offline session, save and exit normally, stop the game, and relaunch. Confirm that character can reopen with its saved progress.
2. During a session, switch to another Android app for a minute and return. Check audio, controls, rendering, and the running-session indicator. Screen-lock behavior is a separate test; record whether it was attempted.
3. Once rendering and inputs work, try your normal PD2 online login and enter a game. Record any exact login/server error. Never include passwords in feedback.

## Import checks

These can follow the gameplay checks; do not overwrite a valuable offline save for an import test.

- While the game is running, verify importing another folder/ZIP is blocked.
- Stop the game and try a ZIP containing only `ProjectD2`; confirm it is rejected and the accepted installation remains available.
- Re-import only when deliberately updating/replacing the install. The import copies files; do not assume existing private saves are merged into a replacement.

## What to send back

Use **Export support logs** after a failed attempt, save/share the resulting ZIP, and report:

- Renderer, launch arguments, input mode, and whether this was a first or subsequent launch.
- The last successful step and the exact visible error or screenshot.
- Whether both native sticks worked, whether input switching was clean, and whether Resume kept the session.
- Whether offline saves reopened and online login/game entry succeeded.
- Any black screen, missing texture/audio, crash, unusual heat, or shutdown of background apps.

An import success is not a gameplay success. APK build/CI success is not physical-device qualification. Performance comparisons should use the same character, area, resolution, and settings in this app and the existing working setup.

Support export contains diagnostics and log tails, not game files or offline-save backups.

Earlier local checks and published CI passed for 0.1.6, 0.1.7, and 0.1.8. The user accepted native in-character input on 0.1.6, rejected Menu cursor on 0.1.7, then accepted Menu cursor on 0.1.8 while reporting that Native remained unresponsive after Save/Exit. [0.1.8 CI run 37172243169](https://github.com/Russianranger/pd2-android/actions/runs/37172243169) succeeded. Automated checks do not qualify Native title-menu support, physical recovery after re-entry, complete controller bindings, saves, or online play. Full evidence and current validation are recorded in [Handoff](HANDOFF.md).

Local 0.1.9 verification passed all 93 API 33 tests and host input/focus regression checks, runtime verification, ARM64 assembly, and APK signature/identity checks. [Published 0.1.9 CI run 37191608087](https://github.com/Russianranger/pd2-android/actions/runs/37191608087) passed; the subsequent physical Native recovery failed by user report. Local 0.1.10 verification passed 105 unique automated tests, host checks, pinned dependencies, final ARM64 packaging, and APK identity/signature/payload comparison; its subsequent physical report still rejects Native on Save/Quit. Local 0.1.11 passed all 122 tests across 16 suites, the host/runtime checks, ARM64 assembly, and package identity/signature/integrity/alignment checks. Its physical feedback accepts LT/thumb and shoulders but rejects Native recovery. Local 0.1.12 passed 132 tests across 17 suites, all eight host scripts, runtime/backend checks, ARM64 assembly, packaging and identity/signature checks. All 72 assets still match 0.1.11. The current Save/Quit-versus-Stop/Play physical gate is pending; see [Handoff](HANDOFF.md) for the APK digest and native rebuild comparison.
