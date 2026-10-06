# Experimental Wine 9 HID read cancellation backport

`wine9-hid-cancel-1` changes the early-cancellation predicate in the accepted
64-bit Wine 9.2 Custom HIDclass driver. This is an exact-hash binary backport of
[upstream commit 4ba2f77f](https://github.com/wine-mirror/wine/commit/4ba2f77fb6714d98285d8a07a8b6fbf85c0a9080),
not a newly compiled driver or replacement Wine stack. All imports, exports,
section layouts, relocations, unwind data, resources and remaining code bytes
stay unchanged. The existing 32-bit driver is not altered.

The source in `vendor/device.c` is the exact Git blob from
[`brunodev85/wine-9.2-custom@a4ef2bf8`](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/hidclass.sys/device.c).
It documents and tests the relevant source predicate. It is not presented as a
complete build tree or proof of the donor driver's entire compilation history.
`PATCH.json` pins that source and the qualified binary transformation.

The original source installs a cancel routine and then uses:

```c
if (irp->Cancel && !IoSetCancelRoutine( irp, NULL ))
```

The upstream fix removes `!`. Clearing the cancel routine returns the previous
routine. A non-null return means this caller owns early cancellation; a null
return means the cancellation machinery already claimed the routine and must
finish cancellation itself.

This backport changes that ownership test only. The retained `pdo_read` returns
the queue helper's status directly; its early-cancel branch does not itself call
`IoCompleteRequest`. The upstream fix has the same surrounding flow. Tests do
not establish complete Wine dispatch/IRP lifetime behavior, and the experiment
must not be described as resolving every cancellation or completion defect.

| Interleaving | Original predicate | Corrected predicate |
| --- | --- | --- |
| Already cancelled before installing the routine | Queues a pending IRP after clearing its routine, with no cancellation owner | Returns cancellation immediately |
| Cancellation machinery claims the installed routine while the queue lock is held | Returns synchronous cancellation while the callback still owns completion | Queues the IRP and lets the claimed callback complete it |
| No cancellation | Queues a pending read | Queues a pending read |

Closing or cancelling a controller read during a game transition is a plausible
trigger for these races. The October 6 physical bundle establishes continued
Android input, fresh Wine HID devices and Raw Input/XInput API traffic after
recovery failed. It does **not** establish that PD2 hit this cancellation race
on Save/Quit. This remains a separate opt-in physical experiment; retain the
accepted full-session cleanup and baseline controller paths.

## Exact qualified bytes

The accepted APK's rootfs member is
`./opt/wine/lib/wine/x86_64-windows/hidclass.sys`. The 64-bit Wine device service
loads `C:\windows\system32\drivers\hidclass.sys`; the physical log reports it as
builtin at address `000000027F8B0000` on thread `005c`. The game itself remains
32-bit. The basename used for a builtin-only load-order rule is `hidclass.sys`,
because this Wine version strips `.dll` only.

| Item | Value |
| --- | --- |
| Original SHA-256 | `a335d3560f14d5b1e31f90fd765ee6261f43a4d70a1f456fbec805ccf18132bc` |
| Experimental SHA-256 | `def30d1b2b6ada06c0ce04d96a8055c67b2f07f2d10f622960ad259f75a64dcb` |
| Size | 65,536 bytes |
| Predicate instruction RVA | `0x25f2`, in inlined `pdo_read` |
| Code byte change | File offset `0x25f3`: `84 → 85`, `JE → JNE` |
| Jump destination | RVA `0x26a8`, existing `STATUS_CANCELLED` return path |
| PE checksum change | `0x17c78 → 0x17d78`; file byte `0xd9`: `7c → 7d` |

The installer requires the exact verified original, preserves its backup,
rejects unknown target bytes and restores only the known experimental image.
The pinned [builtin loader](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/ntdll/unix/loader.c)
resolves marked builtin images against the runtime module directory before
prefix copies. Only the runtime module is changed; prefix copies remain intact.
The opt-in launch appends builtin rules for the full DOS driver path,
`*hidclass.sys` and `hidclass.sys`, preserving prior graphics overrides. The
[load-order parser](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/ntdll/unix/loadorder.c)
matches those names in that order and strips only `.dll`. No registry setting or
wineboot is needed. Support evidence distinguishes requested and installed state
from this launch's applied setting; only a Wine load trace establishes loading.

## Reproduce and qualify

```sh
python3 scripts/build-hid-read-runtime.py --rootfs app/src/main/assets/rootfs.tzst
python3 scripts/build-hid-read-runtime.py --check
python3 -m unittest discover -s tests -p test_hid_read_runtime.py -v
```

The builder accepts only the pinned original hash and branch context, updates
the required PE checksum and reconstructs the original from the artifact to
prove every other byte unchanged. Manifest checks pin production build inputs
and compare all PE directories and non-code sections. Tests also reject unknown
inputs, mutated artifacts, wrong machine headers and stale manifest identities.

The race test compiles the actual vendored function and its one-expression
correction against deterministic stand-ins for the queue/IRP APIs. It verifies
cancellation ownership under ASan/UBSan; it does not run Wine, Android or PD2.
LeakSanitizer is disabled because the host runner restricts `/proc` inspection.

Winebus and HIDclass also contain possible repeated-list-removal hazards.
They are intentionally separate future candidates and are not patched here.
