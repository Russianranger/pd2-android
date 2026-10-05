# 0.1.13 first-launch failure and socket startup ordering

Evidence: user-supplied `pd2-support-20261005-183326.zip`, 107,671 bytes,
SHA-256 `13145cd731c70ad37fcd15a2dca0c51b65c013e0495aef71293abfaa51d5621f`.
Repository baseline: `51ea0a426c5694905e51a44dfca397788c0be574`, implementation
`ed45c929e423c191f5001b17ae303d9edd6cfd8e`. Main CI 37323581197 passed.

## Recorded physical result

The user reports another crash on the first try. The export identifies version
0.1.13, Android 13 on AYN Thor, prepared Wine 9.2 Custom/rootfs 24, the accepted
controller module hash, Turnip/Zink, GameNative arguments and Stability.

Launch `29f2d183-9263-4b25-abe0-67560af619bd` began at
2026-10-05 23:33:20.472 UTC (18:33:20.472 CDT); a caught startup failure was
recorded 906 ms later. The report contains `Runtime startup failed:
RuntimeException`, without the component, exception message or stack.

| Phase | Kill status | Wait status | Controller ports free | Passed | Duration |
| --- | --- | --- | --- | --- | --- |
| beforeLaunch | 1 | 0 | true | true | 206 ms |
| afterStop | 1 | 0 | true | true | 205 ms |

Status 1 for `wineserver -k9` is consistent with no existing server; `-w` and
exclusive port checks passed. The current runtime log contains only the launch
header, without guest Wine output. Controller discovery/state requests and
Android input-event counters are zero. Android still enumerates the accepted
controller. The latest Android process-exit record predates this attempt;
the export establishes a caught runtime startup failure/launcher return, not
a new whole-Android-process crash. Neither Native recovery sequence ran.

## Source-proven ordering defect

`XServerDisplayActivity.setupXEnvironment()` constructs socket configurations
for shared memory, X11, audio and optional renderers. The old
`UnixSocketConfig.create()` recursively deletes/recreates each parent directory
at configuration time. PD2 then completes exact-prefix Wine cleanup and clears
`rootfs/tmp`, deleting those just-created parents. This also touched existing
endpoint directories before the cleanup barrier had closed their old owners.

The first environment component is `SysVSharedMemoryComponent`. Its start
creates `XConnectorEpoll`, whose unchanged native allocator attempts to bind
the configured Unix socket. Native `xconnector_epoll.c` does not create parent
directories; a failed bind makes allocation return zero, and Java throws
`RuntimeException`. This deterministically reproduces a startup defect and
fits the observed branch. The export does not log the actual bind errno or
component, so attribution of this specific device exception to SysV remains
an inference from the source and timing.

The 0.1.13 `tmp/shm` repair only runs in the final guest component. It cannot
repair this earlier socket failure. Preserve it: Wine still needs that
directory when guest execution eventually begins.

## 0.1.14 correction and boundary

Socket configuration becomes path-only. `prepareForBind()` creates verified
private parent directories and removes only the endpoint's stale socket at
service start. XConnector uses it immediately before native allocation, and
PulseAudio uses it after stopping its previous process and before execution.
No parent directory is recursively deleted by configuration creation. The
existing PD2 environment lock keeps preparation after successful cleanup and
the temporary clear, before binding and guest execution.

Startup reports now retain the component, exception message and endpoint in
the matching launch JSON and visible launcher message, bounded to 512
characters. The original cause remains attached to the exception. Guest
execution is not attempted after an earlier service fails; existing teardown
cleans partially started components.

Runtime assets, JNI/native sources, Wine/Box64 versions, graphics, controller
routes, prefix, game installation, saves and container protection are retained.
Physical Play recovery remains to be tested. The Save/Quit-versus-same-app
Stop/Play controller gate remains unresolved, independently of startup and
automated checks.

## Completed validation and offered artifact

Implementation `d83228779e635939079247bd32e3e2d39af7ba81` passed
[CI 37390266190](https://github.com/Russianranger/pd2-android/actions/runs/37390266190).
The actual production socket configuration was exercised against real Linux
Unix sockets: six missing-parent failures reproduced, 19 prepared binds
passed, including stale endpoint replacement, a second session after tmp
clear, and continued communication through a live sibling socket. All 160
unit tests across 20 suites passed, alongside runtime/controller pin checks,
existing host regressions and ARM64 packaging. Local startup suites passed
26 tests; local AF_UNIX execution itself was blocked before bind, so CI is
explicitly the real-bind evidence.

The offered CI APK has SHA-256
`6d943637e375b853436e7ba236bdffedb5164253531ec358aef7f8c94064a028`,
174,045,817 bytes. Identity/signature/integrity/alignment verified; all 72
assets and 35 ARM64 JNI libraries match the preceding 0.1.13 CI APK exactly.
Physical startup and Native recovery still require the device result.
