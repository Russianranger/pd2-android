# Active renderer and audio follow-up

The user reports no apparent performance gains and some audio stutter with 0.1.23, asks for another D2Android comparison, then authorizes continuing the recommendations.

## Capture

`pd2-support-20261026-221706.zip`, 341,360 bytes, SHA-256 `47faf980c441aca1301a7d0731d9f347638543ce39ee60c5b874769474430f8d`, was inspected in the preceding research pass. The current launch is `d55a10aa-27e8-4193-a963-329afd7282d4`, 0.1.23, approximately 295 seconds; older archived attempts are not additional tests of 0.1.23.

All 24 retained snapshots have cumulative Present requests/pacing attempts/waits and Sync await requests/wait time equal to zero. The configured 60 fps X Present limit was unused. Compositor culling ran: final 10,715 covered-window draws skipped and 10,722 windows drawn. These counters are not Windows game FPS.

Two DirectSound probable-underrun warnings occur near mixer creation; the earlier 0.1.22 capture also contains one. Wine's warning can be emitted for an initially empty buffer and does not measure Android AudioTrack starvation or identify the reported stutter interval. No Android underrun/buffer-timing series was available. RAM pressure was not observed; battery 29 degrees C and Android thermal status zero do not measure CPU/GPU temperature or establish improved cooling.

## Primary-source comparison

[D2Android 1.4.1 source](https://github.com/N1v0k/D2Android-Releases/tree/2cd50c37293598e23dc423a0a0b68886d6bf5b47/source) is a Wine/Box64 compatibility stack, not a native engine rewrite. Its Stability flags and Box64 version match ours. Its renderer differs: D2DX/D3D11/DXVK versus the imported PD2 D2GL/OpenGL/Zink path.

- `D2GameActivity` sets both `DXVK_FRAME_RATE` and a nonblocking Android redraw coalescer. The DXVK environment setting does not cap our native D2GL OpenGL path. The new content coalescer adapts the scheduling concept with explicit pause/detach/close cancellation and immediate cursor/control paths.
- Official PD2 [D2GL config](https://github.com/Project-Diablo-2/d2gl/blob/55148de0aec79d487f5bcef1842cfdc3effd2f62/d2gl/src/option/config.cpp) documents the six fields used here. Its [renderer](https://github.com/Project-Diablo-2/d2gl/blob/55148de0aec79d487f5bcef1842cfdc3effd2f62/d2gl/src/graphic/context.cpp#L304) enables the foreground limit only when V-Sync is off. The support ZIP listed config files but omitted their values, so a V-Sync mismatch was a hypothesis, not an observed root cause.
- D2Android defaults its D2DX internal game size to 540 lines. That mod/config is not a drop-in PD2 optimization. This release retains resolution and offers supported optional-effect reductions instead.
- D2Android uses a customized native ALSA plugin to constrain the advertised sample rate to 22.05 kHz. An environment variable without the matching plugin would do nothing. Our separately named Eco module is selected only by the explicit Eco option; Standard uses the unchanged original plugin.
- D2Android's main AudioTrack buffering/write implementation is substantially the same as our prior bridge. The 32 ms default, zero-progress handling, shutdown ordering and new diagnostics are targeted local improvements, not a copied proven stutter cure.

## Boundaries

Only the app-private existing recognized D2GL JSON is edited after the exact-prefix cleanup barrier. A per-install identity, original backup, validated journal, atomic replacement and conflict-aware per-field restore protect other values and re-imports. Unknown/ambiguous/malformed configurations are kept and reported. No game or controller DLL, imported save, Wine baseline, CPU preset or game input binding changes are part of this pass.

Configuration application, synthetic tests and source/ABI checks do not establish device performance gains. The next capture must establish actual D2GL settings, audio rate/buffer/underrun behavior, and the user's perceived smoothness over a comparable route.
