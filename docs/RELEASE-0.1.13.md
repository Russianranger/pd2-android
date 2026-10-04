# 0.1.13 startup repair and protected container cleanup

The 0.1.12 physical report says Play immediately returned to the launcher. This preview corrects a source-proven startup ordering regression: preflight created the runtime shared-memory directory, later temporary-file cleanup deleted it, and the cached environment omitted recreation. The guest launcher now verifies/recreates `tmp/shm` after cleanup and immediately before Wine execution. Without a matching device support ZIP, the observed exit branch is unconfirmed; retest Play before the controller recovery sequence.

Exact-prefix Wine cleanup, wait, port-release checks and worker serialization remain. Bounded cleanup-helper output and launch-ID guarded failure details now help distinguish preflight refusal from Windows runtime exit, including a visible launcher message.

Advanced Containers identifies the Current PD2 container, Older PD2 containers and Additional containers, with ID/Wine version. Eligible old containers can be deleted after confirmation. Current/saved/runtime-selected or unknown-current containers are protected. A worker rechecks identity/selection, removes only the private older C-drive tree without following links, and preserves imported PD2 files/saves and mapped-drive targets. Runtime startup/cleanup and create/duplicate operations block deletion. A final startup gate covers all entry paths while deletion runs.

Wine 9.2 Custom, Box64 0.4.4, rootfs 24, `wine9-hid-1`, existing prefix/import/saves, graphics configuration, GameNative arguments, Stability, HID/input fixes, icon/theme and signing identity are retained. No Wine/HID/game binary or native source is changed. Version 0.1.13/code 14 retains `com.pd2.thor`, min SDK 26/target SDK 28.

Install over the existing app; no Prepare runtime or re-import. [Testing](TESTING.md) gives Play, container and retained Save/Quit versus same-app Stop/Play checks. Startup repair and Native recovery require physical results independently of host build checks.

Final validation/artifact details are recorded in [Handoff](HANDOFF.md).

Local validation passed 148 tests across 18 suites, all eight host input/import/pointer/crash/log scripts, pinned runtime/controller checks, ARM64 assembly and APK identity/signature/integrity/alignment checks. All 72 bundled assets match 0.1.12 exactly. Native sources are unchanged.

Local APK: `PD2-Android-0.1.13-preview.apk`, 174,187,752 bytes. SHA-256: `a36214874401a0346401d1620dacea122bd44b1006deb4265234fb1be44d7673`. Preview certificate: `a61297bef1be2652b53b763730275e1f0e0975222294602ed8ffa5f8147ddb1e`. A separately built CI APK may have a different digest.
