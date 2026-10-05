# 0.1.14 socket startup repair

The 0.1.13 first-launch support ZIP records a caught runtime startup exception
before PD2 begins. Both exact-prefix Wine cleanup phases pass. Source inspection
and reproduction identify another temporary-directory ordering defect:
socket configuration created service parents before cleanup cleared them.

Socket configurations now leave the filesystem untouched until startup.
Shared-memory, X11, audio and optional renderer services prepare their private
endpoint immediately before binding, after cleanup. Preparation preserves
sibling files and refuses unsafe paths. The earlier Wine shared-memory
directory recreation and bounded full-session teardown remain.

Failures now include the failed component and endpoint/message in the launcher
and matching support record, rather than only `RuntimeException`. Messages are
bounded and preserve the original exception cause internally.

0.1.14/code 15 retains `com.pd2.thor`, preview signing, Wine 9.2 Custom,
Box64 0.4.4, rootfs 24, accepted graphics/GameNative/Stability settings,
controller fixes, container management, prefix, import and saves. No runtime
asset or JNI/native source change is required.

Install over the existing app, without Prepare runtime or re-import. First
test Play; if it returns, export immediately. If it reaches gameplay, continue
the retained Native Save/Quit/re-entry versus full same-app Stop/Play sequence
in [Testing](TESTING.md). Physical startup/controller acceptance remains
independent of automated validation. [Evidence](evidence/2026-10-05-startup-sockets.md)
and [Handoff](HANDOFF.md) record provenance and validation.
