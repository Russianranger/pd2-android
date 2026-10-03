# Native Fog startup comparisons

Summary of the new device support bundle; no game binaries, saves, full logs, or private installation paths are included here. The launcher, runtime preparation, and installation import remain accepted. The game has not reached a qualified menu or gameplay session.

Source support ZIP SHA-256: `3e7670623e83abff3b9e6b40632a32b33323791adce498475a54708573675cc5`.

## Captured attempt

| Item | Recorded value |
| --- | --- |
| APK | 0.1.2 |
| Device | AYN Thor, Android 13 |
| Graphics | Turnip/Zink, Wine DirectDraw compatibility |
| Arguments | `-ddraw -w` |
| CPU | `STABILITY`, Interpreter disabled, `BOX64_DYNAREC=1` |
| DirectDraw policy | Built-in `ddraw=b` |
| Native module | `Fog.dll`, loaded at `0x6FF50000` |
| First captured game fault | `0x6FF6879A` = `Fog.dll + 0x1879A` |
| Memory access | Write to `0x00121FFC` |
| 32-bit stack pointer | `ESP=0x00122004` |
| Wine classification | `EXCEPTION_STACK_OVERFLOW`, `0xC00000FD` |
| Recorded 32-bit stack range | `0x00120000–0x00220000` = 1 MiB |
| PD2 module trace | No `ProjectDiablo.dll` load before the fault |
| Outer runtime exit status | 0, despite the captured game failure |

`launch.json` confirms that the 0.1.2 Stability and built-in DirectDraw policy actually reached this attempt. The module-load and exception traces locate the first game-thread fault in native Fog. Runtime exit status 0 is not evidence of successful Windows-game startup.

The exact Fog function and underlying cause remain unverified. The evidence does not distinguish recursion, legitimate stack use, or CPU-translation behavior. Earlier RPC exceptions on other threads and directory/status warnings have not been established as causes of this failure. No imported-file deletion, DLL replacement, stack increase, or new APK patch is justified by this record alone.

## Interpreter comparison: also failed

The next bundle confirms `interpreter: true` and `BOX64_DYNAREC=0` with the same graphics choice. Native Fog faults at `+0x1879B`, one byte after the translated attempt's recorded block address, at the second PUSH. The write is again to `0x00121FFC`, with `EBP=0x0012301C` and the same 1 MiB 32-bit stack. The runtime returned in about nine seconds with outer status 0; the game still failed.

Source ZIP SHA-256: `2339333ecc9fda774d0baba9d9ed2188935412be040d38030ff8acc5fd7ac76c`.

The paired results do not support attributing this failure solely to the dynamic recompiler. They still do not identify the exact Fog function or distinguish recursion from legitimate stack use or a broader runtime/translation difference.

## 0.1.3 matching-arguments comparison: also failed

The next support bundle confirms Turnip/Zink, Stability, and `-3dfx -dxnocompatmodefix` without `-w`, matching the user's supplied GameNative arguments. The written `Fog.*` filter captured this sequence on the game thread:

| Trace event | Recorded result |
| --- | --- |
| `CALL Fog.10021` | Returned `0x17` (23) to `0x0040829B` |
| `CALL Fog.10019` | Caller return address `0x004082B1`; no return recorded before the fault |
| First captured fault | `Fog.dll + 0x1879A`, write to `0x00121FFC`, `EXCEPTION_STACK_OVERFLOW` |

The 32-bit stack is again 1 MiB, and the outer runtime exits with status 0 despite game failure. The export trace narrows the failing initialization interval; it does not identify every internal call, prove recursion, or supply a complete stack. Matching the launch arguments did not resolve the fault. The different Wine/runtime generations remain a compatibility hypothesis requiring a controlled device comparison.

## Working GameNative comparison supplied by the user

The user confirms that the exact source installation folder runs in GameNative. Their screenshots show:

| Displayed setting | Value |
| --- | --- |
| Runtime | Bionic Proton 9.0, x86_64 |
| Box64 | 0.3.7, Performance preset |
| Wrapper/driver | Turnip `25.3.0_R3_Gmem` |
| WineD3D mode | Vulkan |
| Launch arguments | `-3dfx -dxnocompatmodefix`, without `-w` |

The screenshots are a partial configuration; Windows version and environment settings are not yet recorded. Do not infer another Wine build or FEX configuration from them. This provides a working comparison, not evidence that any single setting explains the failure. The app's 0.1.3 matching-arguments test still failed.

## 0.1.4 baseline selected: device result pending

An independent [AnomalOS maintainer commit](https://github.com/weegs710/AnomalOS/commit/4c12f04da66d28998d73942550f58c62c78667d0) reports Median XL's Fog stack overflow at `0x6FF6879B` on Wine 10/11 and changes that PC setup to Proton based on Wine 8. This is a matching-address report from another mod/platform, not proof that its underlying cause or remedy applies here.

The app's selected comparison is Wine 9.2 (Custom), with its complete Wine tree and matching prefix/common-DLL assets from the [official Winlator 10.1 APK](https://github.com/brunodev85/winlator/releases/tag/v10.1.0). Source APK SHA-256: `c46ec3fc96548cecb3716ada8733ebdea4fb25c3c945e0695f2c992c8d3ecf4e`. Box64 0.4.4 and current graphics components remain in place. This does not reproduce GameNative's Bionic Proton 9.0/Box64 0.3.7 setup.

The comparison requires runtime preparation once after the upgrade and a fresh managed prefix, retaining old prefixes and imported files/saves. It keeps the exact matching arguments and Stability for one launch. A Wine 10 compatibility issue remains a hypothesis requiring the new device result; no Windows-version setting difference or confirmed fix is established.

Preserve the accepted launcher/runtime/import. Gameplay, native controller input, rendering/audio, saves, and online play remain pending.
