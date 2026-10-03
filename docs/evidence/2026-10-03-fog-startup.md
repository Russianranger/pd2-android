# 0.1.2 native Fog startup failure

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

## Working GameNative comparison supplied by the user

The user confirms that the exact source installation folder runs in GameNative. Their screenshots show:

| Displayed setting | Value |
| --- | --- |
| Runtime | Bionic Proton 9.0, x86_64 |
| Box64 | 0.3.7, Performance preset |
| Wrapper/driver | Turnip `25.3.0_R3_Gmem` |
| WineD3D mode | Vulkan |
| Launch arguments | `-3dfx -dxnocompatmodefix`, without `-w` |

The screenshots are a partial configuration; Windows version and environment settings are not yet recorded. Do not infer another Wine build or FEX configuration from them. This provides a working comparison, not evidence that any single setting explains the failure. No new APK or correction has been selected in this evidence record.

Preserve the accepted launcher/runtime/import. Gameplay, native controller input, rendering/audio, saves, and online play remain pending.
