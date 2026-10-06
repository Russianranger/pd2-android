# PD2 Android handoff

## Scope and current state

The user requested the first milestone of a dedicated Project Diablo 2 Android app in `Russianranger/pd2-android`: import an existing working installation, play using PD2's own controller support, and switch to mouse/keyboard input from a quick menu. The primary target is the AYN Thor Max, Android 13, Snapdragon 8 Gen 2/Adreno 740, ARM64, 16 GB RAM.

The initial implementation used Winlator 11.2's embedded Wine 10.10/Box64 0.4.4 runtime and Android display/input components. The accepted current baseline is Wine 9.2 (Custom) with Box64 0.4.4. Java/JNI keeps its upstream `com.winlator` namespace; the distinct installed application ID is `com.pd2.thor`.

Runtime archives are deterministically relocated from the original `com.winlator` data paths to the equal-length `com.pd2.thor` identity, including embedded binary paths. This is runtime packaging, not a change to imported PD2 binaries. Preserve this relocation when updating the donor runtime; merely changing the Android application ID is insufficient.

The historical **0.1.12 same-app Wine cleanup preview** was version code 13; current 0.1.16 state is recorded in the continuation below. The 0.1.11 physical follow-up accepts LT/thumb combinations and shoulder behavior, rejects Native after Save/Quit, and demonstrates that Stop/Play within the same Android process also fails. The second launch cannot bind HID port 7950. This pass repairs full-session cleanup; in-place Save/Quit remains unresolved. See [current evidence](evidence/2026-10-04-wine-session-cleanup.md) and [0.1.12 notes](RELEASE-0.1.12.md). Preserve all accepted controller results; do not repeat the LT/shoulder qualification.

The preceding 0.1.11 preview was version code 12. Initial Native gameplay and Menu cursor navigation are accepted. The latest 0.1.10 report still rejects Native on Save/Quit and adds unreliable LT + L3 and shoulder-tab navigation. Earlier 0.1.9 recovery also failed after gameplay re-entry. The matching 0.1.10 log shows two actual Wine HID detach/attach cycles and continuing game-side controller calls; it does not establish accepted controller values or game actions. The full recovery cause remains unconfirmed.

Install over the existing app and reuse Wine 9.2/rootfs 24/prefix/import: **no Prepare runtime or re-import**. Keep GameNative-arguments Glide, Stability, and controller notifications enabled. See [Testing](TESTING.md), [0.1.11 release notes](RELEASE-0.1.11.md), and the [controller research evidence](evidence/2026-10-04-controller-research.md).

## 0.1.16 continuation

Current implementation is **0.1.16/code 17**, continued from live main `188aecc84c636b12edc2819d94ab66c31de86fba`. The newest user report accepts that the 0.1.15 trial did not crash, still rejects Native after Save/Quit and reports cleanup not ready on same-app Play. The locally inspected support ZIP `pd2-support-20261006-010338.zip` is 114,901 bytes, SHA-256 `37fc076be853b273bd651584bb1a7144b4589628a90c99bfabcc9f3eb6c5bec0`. The current launch/controller/memory reports describe a refused replacement; the actual gameplay trace survives in an older runtime attempt. Six same-UID PPID-1 Wine clients survive server wait while controller ports remain occupied. [New evidence/research](evidence/2026-10-06-controller-session-recovery.md) preserves timings, process identities and attribution limits.

The custom Wine 9.2 source proves that `-k9` bypasses server-side client termination. Immediate `-k2` enters that path before bounded wait; server SIGKILL is only a bounded escalation after failed wait. Verified legacy orphan clients are retired after server wait using real UID, exact private Box64 executable, captured canonical WINEPREFIX and revalidated PID/start ticks. Independent exclusive binds verify both controller ports. Scope/permission uncertainty is exported and cannot authorize a kill; an unverified stored-PID fallback no longer bypasses cleanup. Whole-environment serialization and stale session guards remain.

The gear adds **Recover Native controller (experimental)**. Unlike the failed previous reconnects, it changes Wine's HID instance UID after matched old-device removal and Unix stop observations. Its transport is session/UID bound, with bounded retries/watchdog, neutral delivery and cancellation. New device-start confirmation is only the Unix callback boundary. Backend PID/socket inode/lifecycle/state/report counts and selected Android device ID/producer generation support the next capture. Baseline UID 0, the Xbox descriptor/VID/PID, controller slot/bindings and ordinary input remain unchanged until opt-in. A new identity path is source-proven; PD2 recovery remains a hypothesis.

The dedicated matching Unix controller module advances to `wine9-hid-2`. All 55 vendored Wine 9 sources and the 17-entry ABI are retained. Upgrade recognizes only the exact prior module hash and requires its verified original backup; unknown modules are preserved. Disabling notifications restores the verified donor backend. This is not a runtime-stack replacement. Wine 9.2 Custom, Box64 0.4.4, rootfs 24, PE HID/XInput/game files, relocation, graphics/GameNative/Stability, prefix/import/saves, accepted LT/thumb/shoulder/Menu/cursor/icon work, containers and signer remain.

One-shot controller/process workers now end with their task, and old process output cannot inherit a replacement launch's debug callbacks. Matching launch/controller/memory JSON is archived before a new attempt and exported with four bounded runtime archives. The already overwritten 0.1.15 gameplay JSON is unrecoverable. These worker/evidence defects are corrected without claiming they caused Native failure.

Next physical gate: install over the app **without Prepare runtime, re-import or repeated binding qualification**. Capture Save/Quit failure before mutation, try the explicit fresh identity once and export its result, then save/quit, fully Stop client and Play within the same Android process. Export again if cleanup is refused. Accept full-session recovery separately from in-place recovery. The prior memory-kill cause remains unresolved despite this one stable trial. Standing approval to publish validated changes to main applies; no tagged release requested. [Testing](TESTING.md) and [0.1.16 notes](RELEASE-0.1.16.md) give the focused sequence.

Local verification passed **253 unit tests across 28 suites**, zero failures/errors/skips, and ARM64 assembly. The 24 runtime composition/fetch/relocation tests and three controller source/artifact/real-UDP ASan/UBSan checks passed; all 206 final runtime pins and relocation checks passed. The eight existing import/input/pointer/crash/log host scripts passed. The local AF_UNIX permission restriction still prevents that real socket check; CI supplies it. Additional scratch harnesses verified Wine's instance-ID formatter and the actual C/Java tag/ACK round trip, without claiming Android/Wine/PD2 integration.

The final local fallback APK is 174,248,431 bytes, SHA-256 `426f4ebdbcd1fa37b4f66b0f4401a86aff9329e4d76005e705e59adb5af13d4e`; it is not the offered artifact. Identity/signature/ZIP integrity/alignment passed: `com.pd2.thor`, 0.1.16/code 17, ARM64 only, min SDK 26/target 28, unchanged V2 preview signer. Only the two controller assets change; 70 assets remain identical to 0.1.15. Twenty-six JNI files match that CI baseline and nine differ from local recompilation, as in prior build-path comparisons. The CI-built artifact will be verified separately before delivery. Do not assign the local fallback digest to CI.

Published implementation: [802ddf622764c0956ac8d407630268223326d9fc](https://github.com/Russianranger/pd2-android/commit/802ddf622764c0956ac8d407630268223326d9fc), tree `6a0456c463a8df778d531e1cb3c061ee6de68039`, identical to the locally validated source. [CI 37426576271](https://github.com/Russianranger/pd2-android/actions/runs/37426576271) passed for that source at 07:03:16 UTC on October 6: **253 tests across 28 suites**, zero failures/ignored; all 206 runtime pins, three native sanitizer checks, eight host regressions, 19 real AF_UNIX binds and ARM64 assembly. The actual cross-language C/Java fixture passed locally and was not a separate CI step. No release tag was published.

The offered CI APK is `PD2-Android-0.1.16-preview.apk`, **174,080,093 bytes**, SHA-256 `28b436d02b1ca5c484a6707acae1e5efffe02eaad74a24395dfcf8c609225b8b`. Package/ARM64/SDK levels, accepted V2 signer, ZIP integrity and alignment were independently verified. All **70 unchanged runtime assets and all 35 Android native libraries** match the verified 0.1.15 CI APK byte-for-byte; only `assets/pd2/controller/manifest.json` and `winebus.so` change and match the committed source. The final module is 35,176 bytes, SHA-256 `8f306c818432e94379efd9ae01b37126261c4d6a267beebb3cdad6c8b642af3c`.

CI APK artifact 11395525610 is wrapped in a separate **173,152,631-byte ZIP**, SHA-256 `d7d0237b2e755ea5a2c1e62728117c699f0f2d3294eb4bcef149ecbb145ca3e5`; the ZIP, offered APK and local fallback have distinct digests. The requested research ran from **06:07:06 to 07:08:44 UTC** on October 6, 2026, completing **61 minutes 38 seconds**. It covered primary Wine/Android/Linux/SDL sources, the supplied physical evidence, controlled implementation, adversarial tests and independent review. Native recovery and same-app Stop/Play remain physical acceptance gates; no old focus/reconnect experiment, runtime preparation or game re-import was repeated.

## 0.1.15 historical continuation

Current implementation: **0.1.15/code 16**, a diagnostic follow-up to a new gameplay termination. The user reports a crash shortly after entering the character on 0.1.14. Support ZIP `pd2-support-20261005-220232.zip` (66,911 bytes, SHA-256 `e1b7fe516fb9024110ee39f74bae8cf052402dad78b01b04afd85293afeb11cf`) records Android reason **3 = LOW_MEMORY**, status 0, at 03:02:29.540 UTC October 6, 34.804 seconds after launch. Game.exe/ProjectDiablo.dll loaded and a fresh game log exists. No fatal runtime tail, caught startup error, afterStop, normal guest exit or crash.txt is captured. The saved controller snapshot predates gameplay; its near-zero counters do not establish absent gameplay input. [Evidence](evidence/2026-10-06-gameplay-low-memory.md) preserves exact timing and limits.

The old exporter omitted PID/process name/UID/importance/RSS. Sampled PSS 129,803 KB cannot establish total guest/device memory or memory at death. The source audit found no confirmed gameplay-time cleanup regression or accumulating Java log buffer. **The killed process and memory-pressure cause remain unknown; this build does not claim a fix.** Do not change graphics/CPU/controller backend or repeat previous reconnect experiments based on this ZIP alone.

Add a session-owned daemon worker that records every five seconds, retains up to 24 samples and atomically writes at most 512 KiB, dropping older samples at the byte cap so recent evidence continues updating. It captures device memory pressure, app heaps/importance/trim/activity state, protection request and bounded visible same-UID /proc identities/RSS/HWM/thread/oom_score_adj/start ticks. Missing/restricted fields stay unknown; aggregate RSS double-counts shared pages. Stop closes the worker and its queued final checkpoint; generation checks under the launch writer lock prevent stale memory writes. Controller diagnostics also persist at these checkpoints without changing input production or values. Export includes only matching memory/controller reports. Android exits retain numeric reason/status and add identity, reason labels, importance and separately sampled PSS/RSS.

Runtime assets/JNI/native source, Wine 9.2 Custom, Box64 0.4.4, rootfs 24, application ID/signing, graphics/GameNative/Stability configuration, prefix/import/saves, accepted controller fixes and protected container management remain. The earlier cleanup and socket startup repair stay in place. Standing main publication approval still applies; no tagged release requested.

Next physical gate: install over the app **without Prepare runtime or re-import**, enter the same character with accepted settings. If it closes, reopen and **export before Play again**, reporting whole-app closure versus client-only return and approximate duration. If stable for two minutes, export while running, then resume the retained Save/Quit/re-entry versus same-app Stop/Play controller comparison. Initial gameplay stability and both Native recovery cases remain unqualified on 0.1.15.

Local verification passed **184 tests across 23 suites**, with zero failures/errors/skips, and ARM64 assembly. The 24 new tests cover exit reason/identity, real-UID/privacy/limits/unknown fields, bounded rolling memory history, continued writes at the byte cap, stale-worker rejection, executor shutdown and matching support export. All 15 local host/runtime commands passed, including final 206 pins, relocation/controller ABI and eight existing input/runtime checks. The local AF_UNIX bind restriction remains; CI supplies the real socket check. Source [a9ed9f009a7bba226ed0fb0d1fe16942681e2261](https://github.com/Russianranger/pd2-android/commit/a9ed9f009a7bba226ed0fb0d1fe16942681e2261), tree `3f9b4a0d2ba41b019d12f2746bf4ed7d8273d21a`, is published to main under standing approval. [CI37408126420](https://github.com/Russianranger/pd2-android/actions/runs/37408126420) passed; job `112090180096` completed 03:22:17 UTC October 6. Raw reports confirm **184 tests/23 suites, zero failures or ignored tests**. All 206 runtime pins, relocation/controller ABI, ARM64 assembly and packaging passed. The real socket regression reproduced six missing-parent failures and passed 19 prepared binds while preserving a live neighbor. These automated results do not qualify physical gameplay or Native recovery. The historical 0.1.14 artifact and its separate ZIP wrapper below remain reference artifacts; they are not the new preview.

Offered **CI-built** `PD2-Android-0.1.15-preview.apk`: **174,055,701 bytes**, SHA-256 `3f1da6364f354ab2bfd0ef878fdcfadab6d077e09ef819261b28f1ab3baa9704`. Package `com.pd2.thor`, 0.1.15/code 16, ARM64 only, min SDK 26/target 28, ZIP integrity/alignment and V2 signature verified. Preview certificate remains `a61297bef1be2652b53b763730275e1f0e0975222294602ed8ffa5f8147ddb1e`. All **72 assets and 35 ARM64 JNI libraries match the offered 0.1.14 CI APK byte-for-byte**. The APK was saved successfully for delivery.

CI artifact `11389055350` is a separate ZIP wrapper: **173,127,467 bytes**, SHA-256 `d0d1c76ce8f8fb8c6331662338c2ca35784c832c7e2234b7e6e5f303528f5ad9`; its checksum is not the APK checksum. The local fallback APK (174,055,629 bytes, SHA-256 `b66efacb11563c89f8c0d239feaf92fdf7fed533dbfae66f91432c1401ea7321`) was not offered. This final checkpoint is documentation only and requires no rebuild. No tagged release was published. The memory-kill cause and Native recovery physical gates remain unresolved.

## 0.1.14 historical continuation

Preceding implementation: **0.1.14/code 15**, correcting runtime socket preparation after the new physical 0.1.13 first-launch failure. The attached `pd2-support-20261005-183326.zip` records a caught `RuntimeException` 906 ms after launch, with both Wine cleanup phases passing (kill 1/wait 0/ports free) and no guest Wine or controller traffic. No new Android-process crash is established by the exported exit history. The 0.1.12/0.1.13 Native recovery comparison has still not run. [New evidence](evidence/2026-10-05-startup-sockets.md) preserves exact support ZIP identity, timing and diagnosis boundaries.

Source construction previously recreated socket parents before preflight cleanup and the subsequent `rootfs/tmp` clear removed them. The first SysV/XConnector native bind therefore has a missing parent; the original exception text/component was not exported, so attribution of the device branch to this source-proven defect remains an inference. The 0.1.13 Wine `tmp/shm` recreation occurs later at guest startup and cannot repair this earlier failure; keep it.

Socket configurations now have no filesystem side effects. Shared-memory/X11/ALSA/VirGL/Vortek connectors prepare their runtime-confined parent/endpoint immediately before native allocation; PulseAudio prepares after stopping its prior process and before execution. No recursive socket-parent deletion occurs, sibling endpoints/files are preserved, and unsafe/non-socket paths are refused. Existing exact-prefix cleanup and the environment lock retain teardown-before-clear-before-bind-before-guest ordering. Startup failures include their component and endpoint/message, bounded to 512 characters with the original exception cause retained.

No accepted Wine/Box64/rootfs, native source, controller, renderer, arguments, prefix, imported files, saves, container ownership/deletion protection or signer changes are included. The user has standing approval for main pushes as recorded below. No tagged release is requested.

Next physical gate: install over the existing app, **no Prepare runtime or re-import**. Check Play reaches the frontend and same offline character first; export immediately if it returns. If gameplay opens, check initial Native, Save/Quit/re-entry, export before stopping on failure, then full Stop client/Play in the same Android process and export again. Accepted LT/thumb/shoulder and Menu cursor fixes do not need requalification. Container deletion is optional and must not be used as a startup workaround. See [Testing](TESTING.md) and [0.1.14 notes](RELEASE-0.1.14.md).

Final verification: [main CI 37390266190](https://github.com/Russianranger/pd2-android/actions/runs/37390266190) passed on implementation source [d83228779e635939079247bd32e3e2d39af7ba81](https://github.com/Russianranger/pd2-android/commit/d83228779e635939079247bd32e3e2d39af7ba81) at 23:52:16 UTC October 5. It reproduced six missing-parent Unix bind failures, then passed 19 prepared real binds including same-process restart and preservation of a reachable live sibling socket. All **160 unit tests across 20 suites** passed with zero failures/ignored tests, along with the eight existing host regression checks, all 206 runtime pins, relocation/controller ABI checks, ARM64 assembly and packaging. Local fallback independently passed **26 startup/diagnostics tests across three suites** with zero failures/errors/skips and ARM64 assembly. This workspace's AF_UNIX restriction prevented local real-bind execution; unrestricted CI supplies that verification.

The offered artifact is the **CI-built** `PD2-Android-0.1.14-preview.apk`, **174,045,817 bytes**, SHA-256 `6d943637e375b853436e7ba236bdffedb5164253531ec358aef7f8c94064a028`. Source tree: `abdcb50e672498e9f02cea6d931058324a70d371`. Package `com.pd2.thor`, 0.1.14/code 15, ARM64, min SDK 26/target 28 and V2 signature with preview certificate `a61297bef1be2652b53b763730275e1f0e0975222294602ed8ffa5f8147ddb1e` verified independently. ZIP integrity/alignment passed. All **72 packaged assets and all 35 ARM64 JNI libraries match the 0.1.13 main CI APK byte-for-byte** (that baseline APK has SHA-256 `9b695af295a2e504bb6302c4b20442ee19f7b23d12c0998edb13ddd336acaf0f`; distinguish it from the separately built local 0.1.13 preview).

CI artifact 11380887926 is a ZIP wrapper, **173,117,949 bytes**, SHA-256 `6628007e4dcbf2ee9e448d1dd99db8139db93fa9d0af8bf7fe66be9fe28a89f1`; this ZIP digest is not the APK digest. The local fallback build was not offered; its digest differs. This later checkpoint changes documentation only and does not require another APK rebuild. Physical Play recovery and the Native controller comparison remain pending; no tagged release was published.

## 0.1.13 historical continuation

The preceding implementation: **0.1.13/code 14**, repairing a source-proven 0.1.12 startup regression and adding protected container management. User feedback: pressing Play instantly returns to launcher; two containers in Advanced settings need current/older identity and safe old-container deletion. No support ZIP accompanied the initial 0.1.12 report. The later 0.1.13 ZIP establishes the caught startup failure recorded above; the controller comparison remains incomplete.

Preflight captures the launch environment and creates `rootfs/tmp/shm`; the subsequent temporary clear removes it, and cached environment reuse previously skipped recreation. 0.1.13 recreates and verifies this directory inside guest `start()` after the clear, immediately before execution. Accepted ARM64 libc contains the exact relocated shm path; Wine esync uses it. Bundled Wine server is a regular x86-64 ELF; host invocation confirms absent-server `-k9` status 1 and `-w` status 0. The exact-prefix bounded teardown is retained.

Cleanup command output is now drained with a 4 KiB tail per command and retained in matching launch diagnostics; launch failures are bounded/launch-ID guarded and shown on return to launcher. No environment/input values are added to these messages. All accepted input fixes/runtime assets are retained.

Advanced Containers rows identify Current PD2 (protected), Older PD2 or Additional with ID/Wine version, sharing Play's read-only managed-container selection. Deletion protects current, saved selection, active prefix, unknown selection, imported PD2 tree and mapped-link targets. It removes only the confirmed direct private `home/xuser-N` directory without following symlinks, rechecks selection and directory/config identities on the worker, and blocks runtime startup/cleanup/container creation/duplication races. A final Activity startup gate also covers shortcuts/file-manager launch paths. An old container's own C-drive files are deleted only after explicit confirmation; partial I/O failures are reported without claiming success.

Install over the app; **no Prepare runtime, re-import or accepted LT/shoulder requalification**. First confirm Play reaches the frontend and same character; if it fails export immediately. Then compare Save/Quit/re-entry against full Stop/Play in the same Android process, exporting before Stop and after relaunch. Container cleanup is an optional separate test after the startup check; do not use deletion to work around launch failure. See [Testing](TESTING.md) and [0.1.13 notes](RELEASE-0.1.13.md).

Local final verification: **148 tests across 18 suites**, zero failures/errors/skips (including 12 container ownership/removal checks, post-clear shared-memory recreation, linked-path rejection, bounded real subprocess output and launch-ID guarded failure persistence). All eight host input/import/pointer/crash/log scripts passed, all 206 pinned dependency hashes and relocated assets verified, and three controller source/artifact/ABI/sanitizer checks passed. Final ARM64 assembly, package metadata, V2 signature, ZIP integrity and alignment passed.

APK: `PD2-Android-0.1.13-preview.apk`, **174,187,752 bytes**, SHA-256 `a36214874401a0346401d1620dacea122bd44b1006deb4265234fb1be44d7673`. Identity: `com.pd2.thor`, 0.1.13/code 14, min SDK 26/target SDK 28, preview certificate `a61297bef1be2652b53b763730275e1f0e0975222294602ed8ffa5f8147ddb1e`. All **72 bundled assets match the recorded local 0.1.12 APK byte-for-byte**, including accepted Wine/Box64/controller assets. Native sources are unchanged; 26 JNI libraries match exactly and nine were rebuilt by the same pinned NDK. Binary review confirms eight change only build IDs. VirGL changes five scratch-path literals plus constant-string layout/address references: all 1,424 changed instructions preserve opcode/register fields, and all 898 changed dynamic relocations preserve offset/type and resolve to matching normalized strings. Imports/exports/dependencies/ABI metadata remain identical. No tagged release was requested/published.

Published implementation source (tree identical to the locally compiled source): [ed45c929e423c191f5001b17ae303d9edd6cfd8e](https://github.com/Russianranger/pd2-android/commit/ed45c929e423c191f5001b17ae303d9edd6cfd8e). The offered local APK digest still identifies this tested implementation. [PR #1](https://github.com/Russianranger/pd2-android/pull/1) merged to `main` on October 5, 2026 at [2eea9d9a5d4e29c7fb70df86fd4b9b84d4790577](https://github.com/Russianranger/pd2-android/commit/2eea9d9a5d4e29c7fb70df86fd4b9b84d4790577); its tree matches the reviewed final PR head exactly.

[Independent PR CI 37244069821](https://github.com/Russianranger/pd2-android/actions/runs/37244069821) passed on `8c6438909d3ed3143aa23657d27af48e81caa3a6` at 23:36:44 UTC October 4. The later pre-merge checkpoint added only the CI record, leaving compiled source unchanged. CI built its own separate preview artifact (11319050385); do not assign it the recorded local APK digest. [Main CI 37323581197](https://github.com/Russianranger/pd2-android/actions/runs/37323581197) passed for the merge commit at 14:23:12 UTC October 5. No tagged release was published.

On October 5, 2026, the user explicitly approved pushing this and future project changes to `main`. This standing authorization removes the earlier main-publication approval blocker; future validated changes may be pushed without asking for main-push permission again. Preserve the accepted runtime, imports/saves and controller results, and distinguish automated validation from physical test evidence.

Physical startup and Native recovery remain pending independently of automated checks. The user owns any deliberate older-container deletion and must accept its C-drive confirmation.

## 0.1.12 historical continuation

The next physical gate compares Save/Quit/re-entry and full Stop/Play in the same Android app process. Install over the app, reuse runtime/import, save/exit before Stop, and export before stopping if Native first fails. New exact-prefix Wine cleanup, server wait, controller-port release, worker serialization and callback guards are implemented without changing controller binaries. Startup refuses an incomplete cleanup. Current [Testing](TESTING.md) replaces the earlier 0.1.11 binding-retreat request; its LT/shoulder fixes are accepted.

Local validation passed: 132 unit tests in 17 suites, no failures/errors/skips (122 existing Android/Robolectric tests plus 10 cleanup/ownership/socket/subprocess tests); all eight import/input/pointer/crash/log host scripts; pinned runtime/controller checks; ARM64 assembly and packaging. The APK is `com.pd2.thor`, 0.1.12/code 13, min SDK 26/target SDK 28 and retains the preview signer. All 72 assets match 0.1.11 byte-for-byte. Native sources are unchanged; 26 JNI libraries are identical, eight differ only in build IDs and VirGL has build-path/address relocation differences. APK size 174,036,401 bytes, SHA-256 `6b32b9b8bd978dc58192332419e2da98b9944397ad698103f8ae07215d20dd58`. These host checks do not qualify ARM64 Box64/Wine integration. Same-app restart recovery and in-place Native remain physically unqualified.

APK source: [798ad3700c65bb037445515315d86a160b49494d](https://github.com/Russianranger/pd2-android/commit/798ad3700c65bb037445515315d86a160b49494d). [Published source CI 37221364372](https://github.com/Russianranger/pd2-android/actions/runs/37221364372) passed, including runtime preparation, controller verification, unit tests, ARM64 assembly, packaging and artifact upload. The installable local preview is saved as `PD2-Android-0.1.12-preview.apk`; the digest above identifies that local APK, not the separately built CI artifact. No release tag was published. Do not rebuild or change assets before reading the next physical result.

## 0.1.11 scope

- Replace the session-wide digital-trigger latch with independent analog authority for LT/RT. Digital-only sides retain their state during motion, and a full-press digital release does not cancel a partial analog hold. The legacy Wine XInput protocol still exposes digital trigger output.
- Deliver Native single L3/R3 down/up edges immediately through `Pd2ThumbButtons`, preserving held combinations. L3 + R3 remains reserved; the first thumb can reach gameplay before the second opens the menu, which releases the input. Pointer routes retain deferred single-thumb navigation.
- Consume captured pointer callbacks while disabled, suppress moves that round to zero, and release only held mouse buttons in activity cleanup. Shared-router cleanup coalesces releases for controls held by multiple sources on the same output route. Invalidate delayed touch releases on disable and verify callback generation, finger ownership, and current held state so stale callbacks cannot release a later press. Disabled touch-up cannot warp. Deliberate Native touch/mouse remains available.
- Add `inputByMode.*.pointerRouting` source/emission counters for touch, external mouse, and captured input, including disabled/zero captured callbacks. Retain the latest pointer context per mode independently of the recent-context ring, preserving Native geometry/focus through subsequent Menu navigation. Values and device identities are not recorded. These count input requests, not PD2 acceptance.
- On explicit Native selection, detach legacy XInput before HID, keep both absent for at least 600 ms starting after required successful sends, then restore legacy neutral and HID neutral before completing. Existing generations, cancellation, watchdog, and input gates bound this Java-only experiment. Unlike 0.1.10, legacy discovery/polls/state pushes also advertise absence during the gap. No runtime/PE replacement is involved.
- Expand saved **Hide white cursor** to the app's root and Wine/X11 overlays and Android pointer icons, keeping the cursor rendered inside PD2's framebuffer. Default Off is retained.
- Preserve foreground recovery, accepted Menu cursor, Wine 9.2/rootfs 24/`wine9-hid-1` assets, prefix/import/saves, graphics settings, icon/theme, and signing identity.

The requested research ran from **12:55:09 to 13:55:36 UTC** on 2026-10-04, completing **60 minutes 27 seconds**. Final local and published CI validation passed as recorded below. The evidence report contains primary PD2, D2GL, Android, and Microsoft links, exact latest-log counts, source defects, and remaining limitations. Exact official LT + L3/shoulder mappings and frontend Native support were not established by accessible sources. Use the installed client's Controls menu for reproduction and keep Save/Quit UI, title/character menus, and gameplay re-entry results separate.

Local verification passed **122 tests across 16 suites**, with no failures, errors or skips; all host input/import/recovery/log and runtime checks passed, including the Wine backend sanitizer checks and all 206 final runtime pins. Final ARM64 assembly, package identity, matching preview certificate/V2 signature, ZIP integrity and alignment passed. All 72 runtime assets match the verified 0.1.10 CI APK byte-for-byte. Of 35 Android native libraries, 26 match exactly, eight differ only in build IDs, and VirGL rebuild differences were statically traced to source-path strings and address/relocation adjustments; this is not physical graphics qualification. No Wine/HID binary changes are included.

APK: `PD2-Android-0.1.11-preview.apk`, **174,055,860 bytes**. SHA-256: `0a307e4cde8a88312ec82f4413e76f71cf1f7d935a1597e86557081460e7a89c`. Identity: `com.pd2.thor`, 0.1.11/code 12, min SDK 26, target SDK 28, ARM64 only. [Published 0.1.11 CI run 37206816716](https://github.com/Russianranger/pd2-android/actions/runs/37206816716) passed for code commit `2f5e1a9086c3131a9de265813c636f4c57eda6c0`; physical Save/Quit and shoulder-tab qualification remain pending. Physical Save/Quit recovery, shoulder navigation, and complete controller qualification remain pending independently of build checks. The next test checks LT + L3 and shoulders before Save/Quit, repeats after re-entry and explicit Native selection plus a face-button activation, and checks cursor hiding separately in the main menu and in-game.

## 0.1.10 scope

- Request a Java-side HID soft reconnect whenever the user explicitly selects **Native controller**, including reselecting it as a retry. Use the existing absent/present protocol with an absence interval of at least 600 ms starting after a successful absence UDP send; keep the legacy XInput device connected and neutral during that interval. Complete the Java-side cycle only after present and neutral-state packets both send successfully.
- Begin the explicit reconnect after Native regains an active input/focus gate. Opening/closing a modal or changing lifecycle state alone does not request another reconnect.
- Cancel reconnect work on input-gate loss and reject stale queued controller reports; restore neutral device advertisement when an interrupted reconnect needs cleanup. Bound an unfinished reconnect with a 2.5-second timeout.
- Add `controller.json.nativeReconnect` request/detach/attach/completion/cancellation/unavailable/failure/timeout counters and last phase/time. Add `nativeStateDelivery` successful UDP neutral/non-neutral send counters for HID and legacy XInput. These record Java/UDP progress without acknowledging Windows PnP acceptance or PD2 consumption.
- Record the Java producer revision as `java-hid-7950-v2`; the packaged Wine backend remains `wine9-hid-1`.
- Preserve 0.1.9 foreground recovery and existing input routes. The accepted Wine/HID assets, controller DLLs, prefix, imported PD2 files/saves, graphics configuration, and signing identity remain the baseline.
- Add a saved **Hide white cursor** gear-menu toggle, default Off. It controls the app's root white-cursor rendering while preserving the game's cursor and pointer input. It applies to the app's forced Menu pointer too; turn it Off if needed to retain visible Menu cursor navigation.
- Retain the fiery PD2/skull icon and dark quick menu.

Local 0.1.10 verification passed:

- All 105 unique automated tests across 15 suites passed with zero failures, errors, or skips: 100 Android 13/API 33 Robolectric tests and five plain cursor-rendering tests. The full run passed 104 tests; a focused 20-test legacy-controller rerun also passed, including the final added neutral-attach failure test.
- Nine real-router scenarios and 12 menu-pointer/native-focus scenarios with 156 checks passed. Three native-controller host tests passed, including production UDP/replug under ASan/UBSan. All 206 pinned dependencies verified.
- Final ARM64 packaging and APK identity/signature/payload checks passed: `com.pd2.thor`, 0.1.10/code 11, min SDK 26, target SDK 28, ARM64 only, with the same preview certificate as 0.1.9.
- All 72 runtime assets and all 35 Android native libraries match the verified 0.1.9 APK byte-for-byte, including the Wine/HID assets.
- Local APK size: 174,025,221 bytes. SHA-256: `e2f8ea363b5f2d6c3b332998bad91b612bdd348d12e1c6f920b6af1186ce5440`.

The subsequent 0.1.10 physical report still rejects Native on Save/Quit. Its matching trace shows both HID reconnects reach Wine, new raw handles 0x7/0x9, and continued controller calls; legacy XInput stays connected. This establishes a meaningful reconnect without proving correct game input or recovery. The current test is 0.1.11 above; see [0.1.10 release notes](RELEASE-0.1.10.md) for historical scope.

## 0.1.9 scope

- Restore Game.exe Windows foreground focus when Native input resumes after a mode/menu/lifecycle gate, using the existing Windows helper. Coalesce map/resize events belonging to the mapped game subtree with a 75 ms delay, then request one settling restore 250 ms later. Each stage permits at most eight helper-readiness checks.
- Resolve the current game HWND for each request. Cancel queued focus work when Native loses ownership or a newer game-window transition supersedes it. Menu cursor's existing focus request now also expires when its mode loses ownership. No pointer/key input is injected by Native recovery.
- Add `controller.json.nativeFocusRecovery` with `requests`, `lastRequestAt`, `lastReason`, and `scope`. Reasons are `route`, `window`, and `settle`; these count queued requests, not Windows acceptance or PD2 consumption.
- Keep the same accepted HID backend/device and input forwarding; no device replug or runtime/prefix change is introduced.
- Retain accepted Menu cursor navigation and its pointer diagnostics. Native support in PD2's title/character menus is not established by existing evidence.
- Add the requested fiery PD2 icon with a devilish skull, including an adaptive launcher icon. Apply a dark full-screen session theme and dark gear menu/dialog surfaces.
- Retain Wine 9.2, `wine9-hid-1`, rootfs 24, prefix `wine-9.2-pd2-1`, graphics settings, imported PD2 files/saves, and signing identity.

Local 0.1.9 verification passed:

- All 93 Android 13/API 33 Robolectric tests across 14 suites passed with zero failures, errors, or skips.
- Nine real-router scenarios, 12 menu-pointer/native-focus scenarios with 156 checks, 47 import checks, 11 crash-recovery checks, and 749 session-log checks passed.
- All 24 runtime tests and three native-controller tests passed; 206 final dependency hashes, 49 relocated assets, and controller source/artifact/ABI checks verified.
- ARM64 assembly completed in 1 minute 35 seconds. APK identity is `com.pd2.thor`, 0.1.9/code 10, min SDK 26, target SDK 28. V2 signature verification passed with the same preview certificate as 0.1.8; ZIP integrity and alignment passed.
- All 72 packaged runtime assets match the verified 0.1.8 APK byte-for-byte, including the accepted Wine/HID assets.
- Of 35 Android native libraries, 26 match byte-for-byte; eight rebuilt libraries differ only in their GNU build IDs. VirGL's rebuilt differences are string/address placement after the scratch build path changed: normalized strings and referenced targets match, without opcode/register/control-flow changes. No native source changed.
- APK size: 174,020,117 bytes. SHA-256: `e6b1a0215b3502b3fe6fc57d915bdeb1d66b136db89720cde65162c9db5d9e16`.

[Published 0.1.9 CI run 37191608087](https://github.com/Russianranger/pd2-android/actions/runs/37191608087) passed. The user subsequently rejected Native after Save/Exit and after character re-entry while confirming Menu cursor still works. This rejects foreground recovery as a complete physical fix; it does not establish the underlying cause. See [0.1.9 release notes](RELEASE-0.1.9.md) and the [new evidence](evidence/2026-10-04-native-reconnect.md).

## 0.1.8 scope

- Force the app's root cursor image only while Menu cursor owns active input. A hidden game cursor can no longer suppress that menu pointer; native/full fallback rendering is unchanged.
- Route Menu mouse movement, button clicks, and the supported navigation keys through the existing verified Windows helper. Reacquire Game.exe on menu activation; use current mapped game-window geometry for centering and bounds. Windows cursor feedback is authoritative; no duplicate X11 motion is injected.
- Keep at most one outstanding menu movement, use a bounded feedback timeout, and invalidate queued presses/moves on mode or focus loss. Balance held controls on release. Existing unguarded runtime input APIs preserve their behavior.
- Record handled input per mode, menu output request counts/times and returned Windows cursor feedback separately, plus up to 16 filtered geometry/cursor contexts. No key codes, axis values, titles, game binaries or saves are exported in these fields.
- Preserve the accepted Wine/HID backend and all runtime assets. Do not change aspect-ratio math or modify PD2 DLLs.

Local 0.1.8 verification passed: 86 Android 13/API 33 Robolectric tests across 13 suites, with no failures, errors or skips; 9 real-router scenarios; and 11 menu-pointer scenarios with 145 checks. These include the actual mouse/keyboard datagram layout, queued-action expiry, fresh Windows cursor synchronization, lost-feedback retry, resize/clipping, and balanced controls. ARM64 assembly and APK identity/signature checks passed: `com.pd2.thor`, 0.1.8/code 9, min SDK 26, target SDK 28, unchanged preview certificate. All 35 Android native libraries and 72 runtime assets match the verified 0.1.7 APK byte-for-byte, including the accepted HID module. APK size: 171,307,574 bytes. SHA-256: `53cec30a4ee97ef6bb30453ee694c618631572eb853c86dfcc2944e2e4d4c550`. Packaging drift rejection and correct-version artifact fixtures passed. [Published 0.1.8 CI run 37172243169](https://github.com/Russianranger/pd2-android/actions/runs/37172243169) passed. The user subsequently accepted Menu cursor but reported that Native still did not respond after Save/Exit. This does not establish whether Native was also tested after character re-entry.

## 0.1.7 scope

- Add temporary, manually selected **Menu cursor**: either stick moves the pointer (right priority), A clicks, B/Select Esc, Start/R3 Enter, L3 Tab, D-pad arrows. Native forwarding and the full Mouse / keyboard layout remain separate. Return to Native after entering the character; menu mode is not persisted for the next launch.
- Release held inputs on route changes and pause forwarding while the quick menu/fallback dialogs are open. No automatic menu detection, resolution change, or imported DLL patch is introduced.
- Continue live recording after the old cap: compact at 8 MiB, keeping the first approximately 512 KiB and latest 4 MiB under a shared capture lock. Export/archive snapshots remain at most 2 MiB each, with startup/tail sections, capture metadata, and omission markers.
- Remove per-report HID tracing while retaining XInput/raw-input diagnostics. Add up to 32 recent mode/input-gate transitions with timestamps, current focus/pause/menu/drawer gates, and last handled input/reply times. Count pointer-routed input too; `support.json` identifies its input mode as a saved preference, while `controller.json` describes the current session.
- Preserve Wine 9.2, `wine9-hid-1`, rootfs 24, prefix `wine-9.2-pd2-1`, graphics configuration, imported files/saves, and signing identity.

Final local tests, ARM64 assembly, identity/signature, and payload checks passed. [Published 0.1.7 CI](https://github.com/Russianranger/pd2-android/actions/runs/37169294205) passed. The user subsequently rejected the physical menu test; re-entry remains unqualified. See the [menu-transition evidence](evidence/2026-10-04-menu-transition.md).

## 0.1.6 HID notification scope

- Add targeted Wine 9 Unix `winebus.so` revision `wine9-hid-1` using the matching 17-entry Wine 9 ABI, plus Java's 256-byte producer on UDP 7950. Keep the existing 7949/64-byte legacy XInput path. Both expose the first selected pad; HID identity/layout are fixed and cannot be changed by model/mapper preferences.
- Install before Play with a pinned original hash, verified backup, staged verification, and atomic replacement. Unknown runtime bytes are rejected. Notifications default on; disabling restores the verified original on the next fresh launch after force-stop/reopen.
- Restore PlugPlay Start 2 and RpcSs Start 3 while retaining other Essential service settings. Keep private-prefix `Enable SDL=1`; set `DisableHidraw=1` while notifications are enabled and 0 while disabled, resolving the actual CurrentControlSet alias.
- Preserve Wine 9.2, rootfs 24, prefix revision `wine-9.2-pd2-1`, imported PD2 files/saves, and application/signing identity. The change replaces only Wine's Unix controller backend; no PD2 DLL patch or prefix migration is performed.
- Record `nativeBridge=legacy_xinput_7949_and_hid_7950`, `bridgeRevision=java-hid-7950-v1`, separate HID discovery/device/state counters, and backend install/enable/revision status.

Local Java/native/source checks and final ARM64 APK verification passed. [0.1.6 CI run 37167615176](https://github.com/Russianranger/pd2-android/actions/runs/37167615176) succeeded. The user subsequently accepted native input inside a character; the [preceding evidence](evidence/2026-10-04-controller-rawinput.md) records the 0.1.5 failure.

## 0.1.5 device evidence

The matching report accepts Android device 92, `Xbox Wireless Controller`, with 736/736 motion and 16/16 key events handled. It records 58 legacy discovery requests/replies and 859 state replies without reply failures. Wine loads built-in `XINPUT1_4.dll`; PD2 registers raw input for page 1/usages 4 and 5 with flags `0x2100`, then logs four initial GetState calls for indices 0–3 and no later calls. This establishes Java-path activity, not PD2 activation. Final socket/availability flags describe snapshot time and must not replace those accumulated observations.

## 0.1.5 controller scope

- Send controller discovery/state replies and press/release pushes when the socket is ready, independently of `winhandler.exe` INIT. Process/runtime actions still wait for INIT. This removes a source-level controller-response gate without proving it caused the device report.
- Accept capability-qualified `uinput` gamepads/joysticks instead of rejecting their names wholesale. Fingerprint devices (`uinput-fpc`/`goodix_fp`) and Android virtual devices remain excluded. The actual Thor device identity is not present in the 0.1.4 bundle, so filtering is not a verified device cause.
- Add quick-menu **Controller status** for accepted pads, aggregate button/stick events, and discovery/state replies. Opening the menu pauses gameplay input; the display is diagnostic, not proof that PD2 consumed replies.
- Export `controller.json`, bounded to 64 KiB and 32 Android devices, and record `inputMode` in `support.json`. The snapshot records capabilities, selected device, route/availability, socket/INIT status, and aggregate request/reply/input counts; it excludes current keys, axis values, and device descriptors.
- Match its `launchId` to `launch.json`, clear the prior report when a launch begins, and reject stale/invalid reports during saving and export. A previous session's counters must not describe a new launch.
- Replace the Fog `+snoop` channel after accepted startup with `+xinput,+rawinput`, retaining error/warning/exception/module-load logging.
- Keep Wine 9.2, rootfs 24, revision `wine-9.2-pd2-1`, runtime assets, prefix, imported game files/saves, and application/signing identity unchanged. There is no new runtime preparation or prefix migration.

Local 0.1.5 tests/build and [CI run 37162841712](https://github.com/Russianranger/pd2-android/actions/runs/37162841712) passed for commit `f9f0618`. Its subsequent device result establishes Android discovery/input/replies while native activation remains absent. The [earlier controller evidence note](evidence/2026-10-03-controller-detection.md) records the preceding 0.1.4 report.

## 0.1.4 runtime comparison

- Wine 9.2 (Custom), its complete `opt/wine` tree, prefix template, and common DLLs come from the official Winlator 10.1 APK. Box64 0.4.4, current graphics components, and the other rootfs dependencies are retained. This is not GameNative's Bionic Proton 9.0 runtime.
- Rootfs version 24 requires runtime preparation after the upgrade. Managed runtime revision `wine-9.2-pd2-1` creates/selects a fresh prefix on first Play. Previous `xuser-N` prefixes are retained but not selected; the entire private `pd2` tree, including the accepted installation and its saves, is preserved.
- The existing fifth launch choice retains `-3dfx -dxnocompatmodefix` without `-w`. Selected settings from the 0.1.3 attempt persist; explicitly confirm that choice and Stability for the comparison.
- The 0.1.4 support/launch records identified the Wine version, rootfs version, and runtime revision, with Fog export tracing and bounded game/file diagnostics.
- The Wine 9.2 controller DLLs use the older UDP bridge: XInput 7949 and DirectInput 7948 communicate with Java 7947. The matching Java codec is selected only for `wine-9.2-custom` on those ports; other runtimes retain the modern bridge. This baseline exposes one active gamepad, digital XInput triggers, and no rumble. Binary/source protocol verification and focused checks passed; physical Thor controller behavior remains unqualified.

Application/signing identity and SDK levels were unchanged. Local 0.1.4 checks and [CI run 37153029246](https://github.com/Russianranger/pd2-android/actions/runs/37153029246) passed for commit `3537161`; its physical title-startup test was accepted. The runtime baseline is retained for the controller work.

## 0.1.3 device trace

The fifth-profile attempt reached `CALL Fog.10021`, returned `0x17` (23) to `0x0040829B`, then called `Fog.10019` with caller return address `0x004082B1`. No return from 10019 is recorded before the same `Fog.dll + 0x1879A` guard-page write and stack overflow. This narrows the failing initialization interval without establishing its internal call chain or recursion. Matching the arguments alone did not resolve it. See the [evidence note](evidence/2026-10-03-fog-startup.md).

## 0.1.3 diagnostic scope

- The fifth choice uses `-3dfx -dxnocompatmodefix` without `-w`, with the imported native wrapper and Stability. This matches arguments only; the app does not replace its runtime with GameNative's Bionic Proton. The D2DX compatibility flag is a later wrapper option, not an explanation for the early Fog stack fault.
- In 0.1.3, Wine `+snoop` was enabled with a written-and-verified `Fog.*` registry filter. It recorded native export ordinals/arguments and caller return addresses, without establishing internal recursion or a complete stack. Box64 0.4.4's x64-only `SHOWBT` was not used as an x86-game trace. The 0.1.5 policy replaces this with controller tracing.
- `launch.json` includes selected persisted `InstallPath`, `GamePath`, Wine version settings, and `SnoopInclude`; full registry hives are not exported.
- `installation-files.json` adds PE stack reserve/commit sizes and SHA-256 for `Game.exe`, `Fog.dll`, and `PD2_EXT.dll` when at most 8 MiB. Larger files receive a bounded hash error; no binaries or saves are exported.
- `game-logs/` includes the newest two dated D2 logs plus `d2dx_log.txt`/`d2gl.log` when present, each capped at 256 KiB with head/tail preservation. Symlinks are excluded.

For 0.1.3, the application ID, signing identity, SDK levels, runtime archives, and imported installation were preserved. That pass added diagnostics and one launch comparison, with no performance claim or native-controller qualification. Its local tests and APK build passed; that did not establish a physical startup fix.

## 0.1.2 device failure: native Fog stack overflow

The new support ZIP confirms that the intended policy was applied: `cpuPreset: STABILITY`, `interpreter: false`, `BOX64_DYNAREC=1`, and built-in `ddraw=b`, using Turnip/Zink with `-ddraw -w`. This was a genuine compatibility attempt, not the earlier native-first DirectDraw choice.

The game thread loaded native `Fog.dll` at `0x6FF50000`. Its first captured fault is at `0x6FF6879A`, which is `Fog.dll + 0x1879A`: a write to `0x00121FFC` with 32-bit `ESP=0x00122004`. Wine dispatches `EXCEPTION_STACK_OVERFLOW` (`0xC00000FD`) and reports a 32-bit stack range `0x00120000–0x00220000`, totaling 1 MiB. The module trace shows no `ProjectDiablo.dll` load before that fault.

The fault location and stack-overflow classification are established for this captured attempt. The exact Fog function and whether the failure reflects recursion, legitimate stack consumption, or CPU-translation behavior are not established. Earlier RPC exceptions on other threads and directory/status warnings have not been shown to cause this game-thread failure. Do not delete imported files, increase the game stack, replace DLLs, or claim a renderer/translator fix from these observations.

`launch.json` records runtime exit status 0 despite the captured game fault. That outer runtime result does not mean the Windows game started successfully. See the summary-only [device evidence note](evidence/2026-10-03-fog-startup.md).

The subsequent Interpreter bundle confirms `interpreter: true` and `BOX64_DYNAREC=0`, yet fails at `Fog.dll + 0x1879B` with the same write to `0x00121FFC` and 1 MiB stack. It returned in about nine seconds with outer status 0. This does not support attributing the fault solely to the dynamic recompiler. The working GameNative screenshots show Bionic Proton 9.0 x86_64, Box64 0.3.7 Performance, Turnip `25.3.0_R3_Gmem`, WineD3D Vulkan, and `-3dfx -dxnocompatmodefix`. Windows version and environment settings are not visible; do not infer them or an unseen FEX setup.

## 0.1.1 device evidence and 0.1.2 response

The user's support ZIP records Android 13 on an AYN Thor, `runtimePrepared: true`, and a structurally accepted installation with launch path `Diablo2/ProjectD2/Game.exe`. `launcher.log` records all four graphics/argument attempts. The user reports that each returned to the launcher.

Only the final **Turnip/VirGL + DirectDraw** attempt's runtime log survived because the prior logger restarted the same file for every session. It reaches the imported `Game.exe` through Wine's 32-bit/WoW64 path and ends with:

```text
wine: Unhandled stack overflow at address 6FF6879A (thread 00e0), starting debugger...
```

The surviving 0.1.1 log ends before any logged OpenGL-library initialization, and Wine's detailed output was suppressed with `WINEDEBUG=-all`. That earlier evidence did not identify the faulting game DLL, establish that all four attempts had the same exception, or prove a graphics-driver cause. The new 0.1.2 trace above identifies native Fog as the first captured fault location; its underlying cause remains unconfirmed.

All four 0.1.1 choices used `WINEDLLOVERRIDES=ddraw,glide3x=n,b`; the DirectDraw options could still load the imported native D2GL wrapper. They therefore did not establish a built-in Wine DirectDraw baseline.

The 0.1.2 response:

- Uses Box64's **Stability** preset instead of **Conservative**, with **Launch settings → CPU mode** offering **Stability (default)** and **Interpreter (diagnostic; very slow)**.
- Makes both DirectDraw choices use Wine's built-in `ddraw=b` without editing imported files; Glide keeps the native wrapper.
- Captures `WINEDEBUG=-all,err+all,warn+all,+seh,+loaddll` and `BOX64_SHOWSEGV=1` for exception/module-load evidence.
- Exports the latest launch record as `launch.json` and selected-file inventory as `installation-files.json`, with settings and runtime process exit status.
- Retains the latest four attempt logs under `attempts/`, each at most 2 MiB in support export. The live runtime log is capped at 8 MiB.

These changes make failures distinguishable without repeating import/setup or cycling through all renderers before collecting evidence. The first compatibility test qualifies client appearance only; using Wine's built-in DirectDraw may bypass D2GL/controller features, which need their own later test. CPU profiles and wrapper selection are compatibility candidates, not proven remedies or performance optimizations.

The Interpreter and subsequent 0.1.3 fifth-profile comparisons were completed and failed as recorded above. Preserve their results rather than repeating them on Wine 10.10.

## Startup correction and diagnostics

- Automatic installation validation runs on the existing worker without starting `Pd2WorkService`.
- Explicit import/export jobs wait until the service's `onStartCommand`, after `onCreate` has promoted it to foreground, before work can finish and stop the service. This removes a possible service-start/stop race; it does not identify the original Thor crash cause.
- `Pd2Application` installs Java uncaught-exception capture during `attachBaseContext`, before manifest content providers are created, then delegates the original exception to Android's handler.
- The last Java exception is written to `pd2/logs/crash.txt`, bounded at 128 KiB. A separate pending marker routes the next launch to recovery.
- Platform entry/recovery activities avoid initializing the AppCompat launcher and game runtime on the recovery path. **Export crash details** shares the report; **Retry launcher** removes the pending marker while retaining the report.
- Normal **Export support logs** includes `crash.txt` when present, in addition to its existing device, exit-history, and runtime logs.

Crash capture covers uncaught Java exceptions when the process can write its report. Native crashes, abrupt process kills, and storage failures may leave no recovery report. If the launcher still closes without recovery, obtain the user's exact observation and any support export available before choosing another fix.

## Implemented design

- A dedicated launcher manages a single PD2 prefix, prepares bundled runtime files, and exposes Play/Resume.
- Complete folder and ZIP imports are staged in private storage, validated, then accepted. A failed import preserves the previous installation. The game must be stopped before importing.
- A successful replacement retains `install.previous` until the next accepted replacement. This is recovery storage, not a user-facing save-merge or backup manager; the replacement's supplied saves become its own saves.
- The managed `P:` drive points to the imported installation. The selected `ProjectD2/Game.exe` is launched with its directory as the working directory.
- The dedicated Wine prefix seeds Diablo II's `InstallPath` and `GamePath` registry keys from the validated base/client directories so imported PD2 can find its base installation.
- Structural validation looks for a 32-bit x86 PE `Game.exe` and `ProjectDiablo.dll`, PD2 data, and the required base MPQ archive headers. It is bounded and read-only; it is not full file-integrity or version verification.
- The initial screen size is 1280×720. Runtime options expose Turnip/Zink and Turnip/VirGL; standard Glide uses `-3dfx -w`, and the fifth Glide choice uses `-3dfx -dxnocompatmodefix`, both with the imported native wrapper. Wine DirectDraw compatibility uses `-ddraw -w` with built-in `ddraw=b`.
- Physical-controller input uses the Windows gamepad bridge by default. The mouse/keyboard fallback is a fixed PD2-oriented layout with adjustable cursor speed and stick deadzone.
- The on-screen gear and L3 + R3 open a quick menu for input switching, keyboard access, and returning to the launcher. Held input is released at mode/menu/lifecycle transitions.
- Game DLLs are imported as supplied. No custom PD2/BH patching is part of this milestone.
- **Export support logs** shares a ZIP with device/runtime/installation details, recent Android process-exit information, and bounded log tails. It is diagnostic export, not game/save backup.

## Verification boundary

The initial source checks passed with JDK 17:

- `./scripts/test-import.sh`: 47 checks, including unsafe ZIP rejection, ZIP64, synthetic PE/MPQ structure validation, replacement preservation, and interrupted-promotion recovery.
- `python3 tests/test_input_router.py`: tests the actual fallback router with small Android/X-server stand-ins; pointer/key release, shared input sources, direction reversal, analog/digital triggers, drift, alternate axes, and timer cleanup passed.
- `python3 tests/test_crash_recovery.py`: 11 checks passed for the production crash-store/handler code, including bounded valid UTF-8 output, pending-marker acknowledgement, report retention, and delegation when report writing fails. Android metadata is represented by JVM stand-ins.

The 0.1.1 startup pass also verified:

- `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.winlator.pd2.Pd2*Test'`: all six Robolectric 4.14.1 Android 13/API 33 tests passed, with no failures, errors, or skips. They cover the actual manifest/themed launcher, both platform entry routes, recovery fallback and retry report retention, and foreground notification before protected jobs.
- The original published `ebcd311` source was tested in isolation with its actual manifest. Its UI opened, but the startup regression assertion failed because it requested `Pd2WorkService`. This confirms the behavior changed; it does not reproduce Android's foreground-service watchdog or the user's device crash.
- The ARM64 0.1.1 APK build passed with version code 2, the unchanged `com.pd2.thor` application ID, and the same preview signing key.

The completed local 0.1.2 checks are:

- `./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.winlator.pd2.Pd2*Test'`: all 13 Android 13/API 33 Robolectric tests passed, with no failures, errors, or skips. The previous six startup/service/recovery tests passed alongside seven new diagnostic checks for launch policy, PE inventory, exit-status reporting, and bounded logs.
- `python3 tests/test_session_logs.py`: 26 host checks passed for attempt-log retention, size bounds, preservation, and cleanup behavior.
- The ARM64 APK build passed in 22 seconds as version 0.1.2/code 3. Application ID, signing certificate, minimum SDK 26, and target SDK 28 are unchanged. Runtime dependency sources/archives and the native libraries are unchanged from 0.1.1.

The completed local 0.1.3 checks are:

- All 20 Android 13/API 33 Robolectric tests passed with no failures, errors, or skips. They include PE stack fields, hash bounds, allowed game-log exports and symlink exclusion, and persistence of the fifth launch choice.
- The 47 import checks, 11 crash-recovery checks, 26 session-log checks, and input-router checks passed again.
- The ARM64 APK build passed in 1 minute 51 seconds; all 205 final runtime dependency hashes and 49 relocated assets passed verification. Version 0.1.3/code 4 retains `com.pd2.thor`, min SDK 26, target SDK 28, and the same preview signing certificate.
- APK size: 172,992,519 bytes. SHA-256: `5124f9b7a5560875b2ab5d19d33985da743b26448ccd1ef4005c77fbdd76b03f`.

The 0.1.3 [GitHub Actions run 37151153178](https://github.com/Russianranger/pd2-android/actions/runs/37151153178) succeeded for commit `3fc973`. This does not establish a physical PD2 startup fix.

The completed local 0.1.4 checks are:

- All 39 Android 13/API 33 Robolectric tests passed with no failures, errors, or skips, including fresh-prefix migration/recovery and the Wine 9.2 legacy gamepad protocol.
- The 47 import checks, 11 crash-recovery checks, 26 session-log checks, and input-router checks passed.
- All 24 runtime tests passed: 13 fetch, six composition, and five relocation tests. All 206 final dependency hashes and 49 relocated assets verified.
- The ARM64 APK build passed in 28 seconds. Version 0.1.4/code 5 retains `com.pd2.thor`, min SDK 26, target SDK 28, and the same preview certificate.
- APK size: 171,268,551 bytes. SHA-256: `c1eeb0e0968cfa852f736b62ed4d045d57abeaa336f0973ae768656fdd61e413`.

Published 0.1.4 CI run 37153029246 succeeded. The user subsequently accepted title startup on the Thor; these automated checks do not qualify controller input or identify the exact earlier Fog failure mechanism.

The completed local 0.1.5 checks are:

- All 49 Android 13/API 33 Robolectric tests passed with no failures, errors, or skips, including pre-INIT controller replies, capability discovery, bounded diagnostics, and launch-matched saving/export.
- The 47 import checks, 11 crash-recovery checks, 26 session-log checks, and input-router checks passed.
- The ARM64 APK build passed in 18 seconds. Version 0.1.5/code 6 retains the same application/signing identity and SDK levels; all 35 packaged native libraries and 25 runtime archives match 0.1.4, with no temporary files packaged.
- APK size: 171,401,972 bytes. SHA-256: `cd9f9ed746cd1c67e4417172034aa89c8b993253be1d48b92dc36dbb3639e02a`.

Published 0.1.5 CI run 37162841712 succeeded. Neither framework tests nor payload comparison qualify physical PD2 controller activation.

The completed local 0.1.6 checks are:

- All 76 Android 13/API 33 Robolectric tests passed with no failures, errors, or skips. The 47 import checks, 11 crash-recovery checks, 26 session-log checks, and input-router checks passed.
- Three native controller tests passed, including the production UDP/HID/lifecycle host harness under ASan/UBSan. Alignment checking is excluded for the upstream x86 report writes; LeakSanitizer is unavailable on this host. This does not establish complete Wine/HID/PD2 integration; that host test was blocked.
- The pinned 55 Wine 9 source files and LGPL notice, producer/build source hashes, manifest identity, artifact hash, and exact ABI checks passed. The module exports only `__wine_unix_call_funcs`, with 17 entries and compile-time struct size/offset assertions; it links only `ntdll.so`/`libc.so.6`, requiring at most GLIBC 2.17.
- Final backend: 31,064 bytes, revision `wine9-hid-1`, SHA-256 `541523c1e21059a386cfd60f6f18354c05b28ca2457faf867221911b545c4b0e`.
- ARM64 assembly and signature/version checks passed: `com.pd2.thor`, version 0.1.6/code 7, min SDK 26, target SDK 28, and the same preview certificate.
- All 35 packaged Android native libraries and 70 existing assets match 0.1.5 byte-for-byte. Only the controller backend and manifest assets are added; the final module hash was verified inside the APK.
- APK size: 171,307,488 bytes. SHA-256: `21a5b1095ab254a7f4d5cf60ef9e6364b848fc604bbec58838226c510f796f8b`.

Published 0.1.6 CI run 37167615176 succeeded. Native input inside a character was subsequently accepted by user report. No complete Wine/HID/PD2 integration success is claimed from the host checks alone.

The completed local 0.1.7 checks are:

- All 78 Robolectric tests across 13 suites passed with zero failures, errors, or skips. Eight real-Java-router host scenarios and 749 logging assertions passed.
- ARM64 assembly, identity, and signature checks passed: `com.pd2.thor`, 0.1.7/code 8, min SDK 26, target SDK 28, with the unchanged preview certificate.
- All 35 packaged Android native libraries and 70 baseline assets match the verified 0.1.5 APK byte-for-byte. Both custom controller assets match tracked 0.1.6 source; the `wine9-hid-1` module retains SHA-256 `541523c1e21059a386cfd60f6f18354c05b28ca2457faf867221911b545c4b0e`. No native module change is included.
- APK size: 171,296,970 bytes. SHA-256: `d36d82927bcc7ee745fe7a5ae704545c8cc27d256b15c5a928000fd409c30d1a`.

Published 0.1.7 CI run 37169294205 passed. The user rejected its physical menu test; re-entry remains unqualified.

Robolectric models Android framework/resources in the JVM; it does not qualify the physical Thor, Android's service watchdog, ARM64 native-library loading, Wine/controller forwarding, graphics drivers, or PD2. The standalone source checks use small stand-ins and do not execute Android lifecycle behavior. Synthetic PE/MPQ fixtures do not establish compatibility with a real installation. The user accepted launcher/setup/import, title startup on 0.1.4, and native input inside a character on 0.1.6. The original Android crash, earlier Wine 10/Fog failure mechanism, and current Native failure after Save/Exit/character re-entry remain unconfirmed.

The current physical step tests 0.1.16 fresh identity and full-session cleanup as described above. The table preserves historical gates with the latest accepted LT/shoulder result. Runtime preparation and import are not repeated:

| Gate | Status | Evidence or next requirement |
| --- | --- | --- |
| Launcher startup | Accepted on 0.1.1 | User opened the launcher and completed setup/import |
| Runtime preparation | Accepted on 0.1.1 | User report and `runtimePrepared: true` in support ZIP |
| Installation import | Accepted on 0.1.1 | User report, import-completed log, and structural validation details |
| Client launch | Title startup accepted on 0.1.4 | User report; Wine 9.2/current prefix confirmed and Fog 10019 returns |
| Rendering/audio | Pending | PD2 must reach a playable scene with correct textures, UI, and audio |
| Native controller | In-character input accepted on 0.1.6 | User report; detailed independent aiming/buttons/triggers still pending |
| Menu cursor | Accepted on 0.1.8 | User report; retain this navigation path |
| Native after Save/Quit/re-entry | Failed through the latest 0.1.15 trial; Stop/Play was refused with cleanup not ready | 0.1.16 corrects server/client teardown and adds an explicit fresh-identity experiment; both physical results remain pending |
| Native combinations / shoulder tabs | Accepted by user on 0.1.11 | Preserve LT/thumb and shoulder behavior; no repeated qualification requested |
| White cursor toggle | Main-menu white cursor persists in latest report | Check expanded hiding in main menu and in-game independently of Native response |
| Input switching | Pending | Gear/chord and repeated mode switches leave no held input |
| Lifecycle | Pending | Return to launcher and Resume preserve the same session; background/foreground verified |
| Offline saves | Pending | Save, exit, stop, relaunch, and reopen the same character |
| Online play | Pending | User authenticates and enters a normal PD2 online game |

The current qualification step checks initial Native, Save/Quit/re-entry, then saved/closed game → full Stop client → Play within the same Android process. Keep Menu cursor as the accepted navigation path and export the first failure before Stop. LT/thumb and shoulders are accepted; broader rendering/audio, lifecycle and online gates retain their earlier boundaries.

## Next work after the first device test

Investigate failures from the exported support bundle and the user's exact renderer/launch/input settings. Preserve accepted tests and the current architecture; fix the failing layer before adding unrelated features.

Once the app matches the existing working setup, capture baseline startup time, frame stability, crowded-combat behavior, input latency, temperature, and power use. Compare configurations one at a time before claiming optimization gains.

PD2 updates, loot-filter management, broader device presets, production signing, and performance tuning are follow-up work. Do not replace the official PD2 DLLs or add these managers as part of first-milestone qualification.
