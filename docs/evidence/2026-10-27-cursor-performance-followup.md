# Cursor regression and performance follow-up

The user reports continued FPS lag on 0.1.24, audio improvement after selecting Eco, and a black striped square in place of the game cursor. Supplied capture: `pd2-support-20261027-223035.zip`, 252,063 bytes, SHA-256 `7f5f346eb302711995cbadef9cb6eccbe220049a4fdb0630a4ad04c611e4c6f6`. The screenshot is `Screenshot_20261027-222700.png`.

## Correct attempt attribution

The screenshot's 22:27 local time belongs to launch `f5f30870-a88f-4588-9c11-301695c2c067`, started October 27 at 22:26:34 CDT. Its reports are archived under `attempt-1793158128987` (archival time). It requested Standard audio, 32 ms buffering, 60 fps and lighter effects. The subsequent current launch `95f8ae5c-b76b-43f7-9797-c99aa2147f2f` started at 22:28:49 CDT with Eco and restore-game-effects selected. Three older archived attempts do not contain the new audio telemetry.

Both new launches and the export snapshot report the same D2GL config SHA-256 `8ba2a90db984d4dc3f44a080d9d27536c3f8711f41809857b3e28ec45eb6faec`: V-Sync false, foreground limit active at 60, bloom/FXAA/sharpen false. The first launch changed one field; the next changed/restored none. Given the ownership algorithm and no reported conflicts, this narrows the first write to a frame field. The original JSON and journal are not exported, so do not identify the exact field from the current capture. Upstream defaults make V-Sync a plausible candidate, not an observed cause of the cursor corruption.

## Cursor

The screenshot-matching attempt skipped the Android cursor overlay on every sampled compositor pass: final 3,137 passes and 3,137 hidden-cursor skips. The screenshot is therefore consistent with corruption inside the guest framebuffer, not the launcher's gold Menu reticle. No app cursor texture or blend code changed in 0.1.24. Only 19 of 2,781 content requests were coalesced in that attempt; these are not measurements of game frames saved.

Official pinned PD2 D2GL source reads `features.hd_cursor` in [config.cpp](https://github.com/Project-Diablo-2/d2gl/blob/55148de0aec79d487f5bcef1842cfdc3effd2f62/d2gl/src/option/config.cpp). [hd_cursor.cpp](https://github.com/Project-Diablo-2/d2gl/blob/55148de0aec79d487f5bcef1842cfdc3effd2f62/d2gl/src/modules/hd_cursor.cpp) skips its texture-array cursor when false; [funcs.cpp](https://github.com/Project-Diablo-2/d2gl/blob/55148de0aec79d487f5bcef1842cfdc3effd2f62/d2gl/src/d2/funcs.cpp) then permits the original cursor draw. This supports a reversible classic-cursor fallback. The capture does not contain the HD flag, so it does not prove this was the active failing path. No direct V-Sync-to-HD-texture lifecycle dependency was found in the pinned source.

0.1.25 uses the existing journal to restore prior frame values by default instead of hardcoding V-Sync. Explicit saved frame choices remain. The classic cursor option is separate and changes only an existing typed field, with exact restoration and conflict preservation. Shader preset, dimensions and frame latency are diagnostic-only fields.

## Audio result

| Observation | Standard attempt | Eco attempt |
| --- | --- | --- |
| Actual AudioTrack stream rate | 48,000 Hz | 22,050 Hz |
| Format | Stereo float | Stereo float |
| Requested buffer | 32 ms | 32 ms |
| Actual buffer | 2,886 frames / 60.13 ms | 1,335 frames / 60.54 ms |
| Observed Android underruns | 0 | 0 |
| Prepare/write/short/zero errors | 0 | 0 |
| Active PCM throughput | Approximately 383,991 bytes/s | Approximately 176,378 bytes/s |

Eco's verified module and negotiated stream support the user's subjective improvement. PCM throughput fell about 54%, consistent with the rate ratio; this is not a measured CPU/FPS/power improvement. Android may resample to the physical device's rate. Blocking write duration includes waiting for playback capacity and cannot be interpreted as CPU cost. Wine still reported probable DirectSound underrun warnings (one Standard, two Eco), which concern upstream mixer padding and do not establish an audible failure interval. Battery readings stayed 25°C and Android thermal status was none; neither measures CPU/GPU temperature.

## Remaining FPS evidence

The cap was configured, so lack of improvement is not an application-status miss. Redraws often arrived below 60 per second; the logs contain no per-process CPU execution, GPU utilization or actual game frame-time series. The existing shader remains active even when optional effects are off, but its selection was omitted from this capture. The packaged Zink library imports the Xlib image/shared-image frontend, whereas the proposed `glCopyTexImage2D` optimization belongs to separate Gladio/VirGL paths; do not present that as a demonstrated optimization for this run.

The new bounded CPU fields reuse the sampler's existing `/proc/<pid>/stat` reads: fields 14/15 for process user/system ticks, field 22 for identity, and the device's `sysconf(_SC_CLK_TCK)` for conversion. The [kernel proc documentation](https://www.kernel.org/doc/html/v6.14/filesystems/proc.html) and [Android Os API](https://developer.android.com/reference/android/system/Os#sysconf(int)) define these values. Percentages compare adjacent monotonic samples for the same UID/PID/start-time identity; missing, reset or reused identities do not inherit deltas. One core is 100%, and multithreaded processes may exceed 100%. No additional `/proc` reads, timer or per-frame file logging is introduced.

## Shared-image observation and deferred fix

An actual-source host fixture also reproduces an existing borrowed-buffer hazard in `MITSHMExtension`/`Drawable`: the shared branch retains a reference to guest shared memory and returns before the later texture upload. Mutating that guest buffer changes pending drawable pixels. That branch also ignores offsets and subrectangles; no SHM completion event is sent. This fixture uses synthetic transport/Android stand-ins and does not prove the screenshot's cause or that the branch ran during this capture. The packaged Zink library's Xlib/shared-image imports make it a candidate path, not runtime evidence.

0.1.25 adds bounded `displayRefresh.mitShm` counters for valid request-path selection, borrowed versus copy branch, nonzero offset, subrectangles and estimated logical pixel bytes. Counts are observations of requests, not completed copies, game FPS or bytes actually uploaded to the GPU. Pixel ownership and the existing custom shared-data protocol remain unchanged in this pass. A validated owned-buffer copy could address the hazard, but would add roughly 3.7 MB of CPU copying per full 1280×720 frame and needs offset/capacity/clipping and GPU/CPU transition tests. Confirm the active path before imposing that cost or changing the protocol.
