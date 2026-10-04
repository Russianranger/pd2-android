# 0.1.7 Menu cursor failure and 0.1.8 recovery scope

The user reports controls still unresponsive at the menu after Save/Quit. This result rejects the 0.1.7 menu workaround; native in-character input remains accepted.

Bundle: `pd2-support-20261003-211729.zip`, SHA-256 `541836eb584c6533a0ab5ac1639e6bf6ade24427aa25878005d378548f57e62a`. App 0.1.7, AYN Thor/Android 13, launch ID `77a018b2-c7af-4c8a-b563-1c0a208ac2fc`. Wine 9.2/rootfs 24/prefix `wine-9.2-pd2-1`, enabled `wine9-hid-1` module hash `541523c1e21059a386cfd60f6f18354c05b28ca2457faf867221911b545c4b0e`, GameNative arguments and Stability.

| Evidence | Boundary |
| --- | --- |
| Menu cursor selected at 1791080220093; focused input active 1791080220103–1791080229956 | About 9.85 seconds; Android focus/modal gate was not stuck |
| 2,377/2,377 motion and 24/24 key events handled | Aggregate counts cannot attribute stick activity to Menu cursor |
| Last handled key at 1791080212420 | No routed A/Enter/D-pad key attempt is captured during the active menu interval |
| 49 HID device and 1,881 HID state replies; 3,743 total state replies | Zero reply/socket/invalid-packet errors; device remained discoverable |
| Save-directory/Disconnect from BNET calls followed by continuing RawInput/XInput calls, same HID handle 5 | No captured device removal; Windows focus or actual menu mouse acceptance is not established |
| Complete 1,940,190-byte runtime snapshot | This capture did not hit the old cap; no omitted middle marker |

Source findings: renderer `setCursorVisible(true)` still honors an invisible game cursor; pointer motion uses desktop bounds, and the X11 button path does not use an active grab's listener. The managed launch does not enable FullscreenTransformation; CursorLocker is compile-time disabled. These do not prove the specific device failure, but justify a visible, bounded Menu pointer using the existing Windows input path.

The actual packaged `winhandler.exe` was checked directly: 19,456 bytes, SHA-256 `7d7237752c6d3587ea78005d25566440b5b2e3647d22b548d8d8badf6dc0ee40`. Its 256-byte receive capacity, mouse/keyboard request offsets, mouse_event/keybd_event calls, cursor feedback, and Game.exe restore/foreground operation match Java. This is protocol verification, not physical PD2 menu acceptance.

0.1.7 CI run [37169294205](https://github.com/Russianranger/pd2-android/actions/runs/37169294205) succeeded. 0.1.8 adds scoped pointer recovery and per-mode/output/geometry diagnostics. Physical confirmation remains pending; preserve the accepted runtime and import for the focused [test](../TESTING.md).
