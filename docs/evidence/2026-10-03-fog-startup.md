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

## Next comparison

Use the existing 0.1.2 CPU option **Interpreter (diagnostic; very slow)** with the same Turnip/Zink Wine DirectDraw profile. Press Play once and allow at most 60 seconds for the title/menu. Stop an unfinished attempt, or record a successful menu and then stop. Export the new support ZIP in either case and restore Stability. The time limit bounds the test; it does not prove how long every valid Interpreter load should take.

Preserve the accepted runtime and imported installation. Follow [the testing instructions](../TESTING.md); gameplay, native controller input, rendering/audio, saves, and online play remain pending.
