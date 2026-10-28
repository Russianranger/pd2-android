# FPS and scaling follow-up

The user reports that FPS remains poor, the explicit 60 FPS limiter helps temperature, and selecting Classic cursor fixes the black square. Preserve those accepted choices. These are device observations reported by the user; the capture does not measure the size of the temperature benefit or establish achieved game FPS.

Capture: `pd2-support-20261028-174239.zip`, **201,923 bytes**, SHA-256 `0d48500aaf3c5af58ff4caefa8b143cf195666a2c0a29b197da1c1ac4bfa3670`. The export reports app **0.1.25**, Android 13, AYN Thor, Wine `wine-9.2-custom`, runtime `wine-9.2-pd2-1`, and rootfs version 24. Analysis reads archive members without extracting executable content.

## Attempt attribution

Archive suffixes record when reports were archived, not when the contained launch began. Use `launchId` and `startedAt` to join launch, memory, controller and runtime reports.

| Report | Launch ID | Actual start, UTC | Relevant captured choices |
| --- | --- | --- | --- |
| Current `launch.json` | `be204332-1d07-43ec-a602-63c445e1b44a` | October 28, 22:40:05.318 | 60 FPS, Eco, 32 ms, lighter effects off, Classic cursor off |
| `attempt-1793227205282` | `95f8ae5c-b76b-43f7-9797-c99aa2147f2f` | October 28, 03:28:49.009 | Previous 0.1.24 Eco run, 60 FPS, 32 ms, lighter effects off |
| `attempt-1793158128987` | `f5f30870-a88f-4588-9c11-301695c2c067` | October 28, 03:26:34.934 | Previous 0.1.24 Standard run, 60 FPS, 32 ms, lighter effects on |
| `attempt-1793157994898` | `d55a10aa-27e8-4193-a963-329afd7282d4` | October 27, 03:12:08.336 | Older attempt; X Present pacing requested; no new audio telemetry |
| `attempt-1793070728311` | `40d09532-fb9c-4d49-9584-2858fad4891f` | October 27, 00:35:03.105 | Older attempt; no new audio telemetry |

The current launch is October 28 **17:40:05 CDT**, matching the latest launcher-log entry. Before-launch and after-stop cleanup both passed. The older Eco report is retained from the previous export, not a second new test of 0.1.25.

## Captured settings and cursor limitation

The current launch uses `turnip,zink`, `-3dfx -dxnocompatmodefix`, and the Stability CPU preset. D2GL config validation succeeded with the foreground cap active at **60** and V-Sync false; zero fields needed changing. Android accepted the separate 60 Hz surface request. Neither result measures actual refresh or game FPS.

Lighter effects were not requested, but bloom, FXAA and sharpen were already false. The existing config also recorded **1280×720**, shader **`fsr\fsr-easu.slangp`**, and frame latency **1**. Config SHA-256 remains `8ba2a90db984d4dc3f44a080d9d27536c3f8711f41809857b3e28ec45eb6faec`.

The launch snapshot says `classicCursorRequested=false`, `cursorApplied=false`, and `features.hd_cursor=true`. Thus the user's successful Classic cursor change is confirmed by their report, but this prelaunch snapshot cannot attribute the fix to the launcher's Classic application. It may precede a later in-game change. The whole run skipped the Android cursor overlay on all 2,968 compositor passes. Do not infer an Android overlay regression from the earlier black-square symptom.

## Audio remains stable at the Android boundary

Eco module verification succeeded with SHA-256 `c3c344f2f8740aaca3db6ba6216b6908dcceb367358cdca31e1907ec7043a3bd`. Active AudioTrack samples consistently show **22,050 Hz, stereo float**, requested 32 ms / 768 frames, and actual allocation **1,335 frames / 60.54 ms**. This is the negotiated stream rate, not necessarily the physical output-device rate.

Final counters contain **5,085 writes** and **15,936,504 bytes** written, with zero observed Android underruns, prepare failures, short/zero writes, write errors/exceptions or buffer growths. Across the 85.555-second span between the first and last active samples, throughput is approximately **176,379 bytes/s** and **56.27 writes/s**, essentially unchanged from the prior Eco run. The earlier Standard run negotiated 48,000 Hz; the prior [audio analysis](2026-10-27-cursor-performance-followup.md#audio-result) remains applicable.

One Wine `DSOUND_PerformMix Probable buffer underrun` warning remains. It describes upstream mixer padding, separately from Android track starvation, and has no measured audible duration. The single ALSA `default` open failure `-95` is consistent with unsupported capture probing; successful playback is directly observed. `writeNanos` measures elapsed blocking-write time, including waiting for playback capacity, not CPU cost. There is no evidence here to change the working audio settings to address FPS.

## CPU and active image path

Compare `memory.json` snapshots 7–19 at **22:40:40.655–22:41:41.041 UTC**, a **60.386-second** window. Only the current attempt has these CPU diagnostics. Process identity is PID plus start-time ticks; CPU totals include each process's threads and exclude its children. The device reports 100 clock ticks per second.

| Process | Identity | CPU tick increase | CPU seconds | Average, one core = 100% |
| --- | --- | ---: | ---: | ---: |
| `Game.exe` | `9562:173315093` | 7,438 | 74.38 | 123.17% |
| Android app | `8902:173311499` | 826 | 8.26 | 13.68% |
| `wineserver` | `9485:173314845` | 287 | 2.87 | 4.75% |
| `winhandler.exe` | `9556:173315070` | 140 | 1.40 | 2.32% |

These approximate interval values come from sequential process reads, not per-thread profiling or GPU timing. The game process dominates observed CPU execution; this does not alone prove a CPU bottleneck, identify a hot function, or exclude GPU/driver waits.

In the same window, MIT-SHM recorded **1,944 requests**, all on the existing borrowed-buffer branch, with **7,166,361,600 logical pixel bytes**, no copy-branch requests, offsets or subrectangles. Content redraw forwarded **1,944 requests**, merged **zero**, and deferred callbacks only **three** times. The compositor drew **1,945 windows** in 1,945 passes and skipped 1,945 covered windows. X Present and X Sync remained unused.

The logical-byte estimate equals 1,944 × 1280 × 720 × 4. It describes requested image extent, not measured memory traffic, completed GPU uploads, or proof of zero-copy operation. These counts establish the active shared-image route and little redraw merging in this window; dividing them by time must not be labelled game FPS. The previously documented [borrowed-buffer lifetime concern](2026-10-27-cursor-performance-followup.md#shared-image-observation-and-deferred-fix) is now relevant to an observed route, but is still not proven to cause the cursor symptom or FPS limit.

## Primary-source basis for a shader comparison

Pinned PD2 D2GL [`55148de`](https://github.com/Project-Diablo-2/d2gl/blob/55148de0aec79d487f5bcef1842cfdc3effd2f62/d2gl/src/graphic/upscaler.cpp#L240) selects `bilinear.slangp` as its default fallback. Its [render submission](https://github.com/Project-Diablo-2/d2gl/blob/55148de0aec79d487f5bcef1842cfdc3effd2f62/d2gl/src/graphic/context.cpp#L479) invokes the upscaler even when optional effects are off; [`Upscaler::process`](https://github.com/Project-Diablo-2/d2gl/blob/55148de0aec79d487f5bcef1842cfdc3effd2f62/d2gl/src/graphic/upscaler.cpp#L377) copies its input and draws each configured preset pass.

In libretro slang-shaders commit `a4f3aeec04fcb2624ec6df5dd17e38f9b575eab9`, [bilinear](https://github.com/libretro/slang-shaders/blob/a4f3aeec04fcb2624ec6df5dd17e38f9b575eab9/bilinear.slangp) uses a [stock fragment shader with one texture-sampling expression](https://github.com/libretro/slang-shaders/blob/a4f3aeec04fcb2624ec6df5dd17e38f9b575eab9/stock.slang#L32), whereas [FSR EASU](https://github.com/libretro/slang-shaders/blob/a4f3aeec04fcb2624ec6df5dd17e38f9b575eab9/edge-smoothing/fsr/fsr-easu.slangp) uses [EASU processing and texture gathers](https://github.com/libretro/slang-shaders/blob/a4f3aeec04fcb2624ec6df5dd17e38f9b575eab9/edge-smoothing/fsr/shaders/fsr-pass0.slang#L44). **Both presets declare one pass**: fewer shader passes are not established. This supports testing a simpler sampling shader, not claiming an FPS gain. The imported preset bytes and D2GL DLL identity are unverified; the captured shader name establishes configuration, not execution of these exact sources.

## Constraints for the next change

All 22 current thermal samples report **26°C battery temperature** and Android thermal status **none**. These are not CPU/GPU temperatures, and the prior run's 25°C reading is not a controlled comparison. Honor the user's reported benefit from the 60 FPS cap without inventing a measured reduction.

The observed 720p image route and retained scaling shader make a reversible resolution/shader comparison a reasonable next experiment. Fewer source pixels can reduce per-image work; FPS or temperature gains remain unmeasured until tested on the device. Keep the accepted cap, Classic cursor choice, Eco audio and controller repair intact while evaluating that change. Do not treat additional redraw throttling or an unrelated X Present optimization as a demonstrated solution for this capture.
