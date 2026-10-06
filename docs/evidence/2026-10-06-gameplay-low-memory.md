# 0.1.14 gameplay termination and 0.1.15 diagnostic follow-up

The user reports: "It crashed shortly after entering the game with my character."
The live main checkpoint was `a5600799883c3110554c96cddf76961ec4ce81b3`,
with implementation `d83228779e635939079247bd32e3e2d39af7ba81` (0.1.14/code 15).
It supersedes the previously supplied 0.1.12 checkpoint. Existing runtime,
import, prefix, graphics, arguments, Stability and controller acceptance remain.

## Evidence identity and timeline

Support ZIP: `pd2-support-20261005-220232.zip`, 66,911 bytes, SHA-256
`e1b7fe516fb9024110ee39f74bae8cf052402dad78b01b04afd85293afeb11cf`.
Only bounded diagnostic findings are committed; the attachment and game data
are not added to the repository.

| Event | UTC October 6, 2026 | Interpretation |
| --- | --- | --- |
| Launch `4d514cdb-05d3-4f5c-ae5e-95b26cf8fb66` | 03:01:54.736 | Accepted runtime/configuration |
| Preflight cleanup | Approximately 03:01:55 | Passed: kill 1, wait 0, ports free, 208 ms |
| Persisted controller snapshot | 03:01:57.754 | Only three seconds into launch; predates gameplay |
| Fresh Diablo log begins | 03:01:58.690 | Game.exe and ProjectDiablo.dll loaded |
| Last dated Diablo log entry | 03:02:07.646 | DirectSound buffer warning; no fatal game exception |
| Android exit-history record | 03:02:29.540 | Reason 3, status 0, sampled PSS 129,803 KB |
| Support export | Approximately 03:02:32 | Runtime file modification matches the exit second |

Android's official [ApplicationExitInfo reference](https://developer.android.com/reference/android/app/ApplicationExitInfo)
and local SDK 35 constants identify **reason 3 as REASON_LOW_MEMORY**: a system
low-memory termination. Reason 2 is signaled; reasons 4/5 are Java/native
crash. Status 0 does not turn a memory termination into normal game exit.
The preceding 02:38:21.907 UTC record also has reason 3, but predates this
launch and is not proof of a second kill within the same launch.

The current runtime log contains 7,686 lines and approximately 680 KiB,
ending abruptly in normal XInput/raw-input polling. There is no captured
fatal signal, access violation, assertion, unhandled exception, normal
Windows-runtime-exit marker, startup failure, afterStop cleanup, or crash.txt.
Numeric signal 17 records are child-status notifications, not a fatal
SIGSEGV. Earlier esync/RPC/audio warnings are followed by continuing execution
and do not identify the terminal cause.

## What this establishes and leaves unknown

0.1.14 advanced beyond the previous startup failure and reached character
gameplay by user report. It does not establish sustained stability, Native
Save/Quit recovery, or full same-app Stop/Play recovery. The stale controller
snapshot cannot characterize gameplay input; its near-zero counters are not
evidence that no controller events reached the game.

The export omitted PID, process name, UID, importance and RSS. Android's PSS
is an earlier sample of one exited process, not the device's available RAM,
final memory at death or the total Wine process group. The game's 2 GiB
physical-memory report is the guest's view, not the Thor's actual 16 GiB.
The specific killed process, memory owner and reason for pressure remain
unidentified. Do not attribute the kill to a particular renderer, controller
backend, cleanup helper, or Android process priority from this ZIP alone.

A source audit found no periodic/delayed cleanup during gameplay: cleanup
runs before launch or during explicit stop/destruction, and socket preparation
runs at component startup. The logger is disk-backed. Existing executor
lifetime concerns across repeated sessions are a separate audit item, not a
proven cause of this first reported gameplay attempt.

## 0.1.15 diagnostic change

Keep runtime and settings fixed. Add five-second disk checkpoints retaining
up to 24 recent samples (at most 512 KiB); older samples are removed when the
byte bound is reached so current evidence continues updating, with device available/total RAM,
low-memory threshold/status, app Java/native heaps, app importance and trim
level, activity state, protection request, and visible same-UID process
identity/RSS/HWM/threads/oom_score_adj/start ticks. Missing/restricted fields
remain unknown and scan limits are explicit. RSS sums double-count shared
pages and do not establish GPU or hidden-process memory.

The monitor begins with the PD2 Activity and ends on destruction. Its worker
is shut down; generation-checked atomic writes cannot replace a newer launch.
Controller counters are also persisted at checkpoints, without changing
event production, device selection, or reconnect behavior. Support export
includes only matching bounded memory/controller reports. Android exits now
include exact process identity, reason labels, importance and sampled PSS/RSS.

This is an evidence collection preview, **not a claimed memory-kill repair**.
The next device attempt must preserve its post-kill export before another Play.
Automated validation and offered APK provenance are recorded in the handoff
with CI and offered APK provenance. No runtime preparation, re-import, container deletion,
graphics experiment, or repeat of accepted controller bindings is requested.

Local validation passed 184 tests across 23 suites (24 new diagnostic tests),
with zero failures/errors/skips, and ARM64 assembly. New checks cover the
rolling history under count and byte limits, stale generation rejection,
worker shutdown, proc identity/UID/privacy/unknown fields and matching export.

CI [37408126420](https://github.com/Russianranger/pd2-android/actions/runs/37408126420)
passed on source `a9ed9f009a7bba226ed0fb0d1fe16942681e2261`, tree
`3f9b4a0d2ba41b019d12f2746bf4ed7d8273d21a`: 184 tests/23 suites, zero
failures or ignored tests, all runtime/controller checks, ARM64 assembly and
packaging. The unrestricted real Unix test reproduced six missing-parent
failures and passed 19 prepared binds, retaining a live neighboring endpoint.

Offered CI APK SHA-256
`3f1da6364f354ab2bfd0ef878fdcfadab6d077e09ef819261b28f1ab3baa9704`,
174,055,701 bytes. Its package/version/signer/integrity/alignment verify. All
72 assets and 35 ARM64 JNI libraries match the 0.1.14 CI artifact exactly.
ZIP-wrapper and unoffered local APK checksums are separate in the handoff.
Physical gameplay stability, memory owner/cause and Native recovery remain
unresolved; the next export is necessary before choosing a runtime change.
