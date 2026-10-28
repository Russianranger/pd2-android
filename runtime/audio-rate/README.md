# Optional Eco audio PCM

This separately named ARM64 ALSA plugin constrains playback negotiation to
22,050 Hz. It follows D2Android 1.4.1's audio-rate approach. The inherited PCM
transport and shared-memory ABI are unchanged. A small wrapper fixes the rate
and renames the entry point to `pd2_eco`; the vendored source is not edited apart
from a terminal newline.

Source: `N1v0k/D2Android-Releases`, commit
`2cd50c37293598e23dc423a0a0b68886d6bf5b47`, file
`source/tools/alsa-plugin/module_pcm_android_aserver_d2.c`.
That file is Winlator-derived LGPL 2.1 code with D2Android's changes. The complete
license is in `COPYING.LIB`, also included beside the APK asset. This repository
provides the corresponding source, wrapper, build script and pinned build inputs.

Normal playback continues using the original runtime plugin and configuration.
Only an explicitly selected Eco launch points `ALSA_CONFIG_PATH` to an app-private
config naming this module by its absolute path. Neither the original plugin,
Wine, Box64, controller DLLs nor imported game files are replaced. The tradeoff
is reduced audio bandwidth; reduced CPU use or improved stutter is not measured
on a physical Android device.

Reproduce or test from the repository root:

```sh
python3 scripts/build-audio-rate-runtime.py --check
python3 scripts/build-audio-rate-runtime.py --rebuild --test
```

The full command needs `python3`, `zstandard`, `dpkg-deb`, `readelf`, the verified
runtime assets, and network access to the SHA-pinned Ubuntu snapshot packages in
`TOOLCHAIN.json`. It extracts the compiler, headers and QEMU into an isolated
qualification directory; it does not install system packages. GCC links with
`-nostdlib` directly to the exact runtime's ARM64 `libasound.so.2` and `libc.so.6`.
The resulting PCM imports only GLIBC 2.17 and ALSA 0.9-era symbols, has no embedded
runtime search path, and preserves the original 4-byte shared pointer prefix.

The qualification compares the original and Eco modules under QEMU using the
exact packaged ARM64 ALSA, libc and loader. Each module negotiates, prepares,
starts, writes, stops and closes through the real ALSA API with both 16-bit and
floating-point PCM in shared-memory and socket-data modes (eight scenarios).
A deterministic mock AServer transport replaces only the
socket calls, so the test can run where Unix sockets are unavailable. It checks
that an initial 48 kHz request negotiates 48 kHz with the original module and
22.05 kHz with Eco, and that both preserve sample bytes and protocol sizes.
This is a module/ABI/negotiation check, not an Android playback-timing benchmark.
