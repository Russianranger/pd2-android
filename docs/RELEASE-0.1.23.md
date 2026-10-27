# PD2 Android 0.1.23 Preview

Three graphics optimizations follow the user's report that 0.1.22's display/log changes made little difference to performance and temperature:

1. **Pace frame submissions.** PD2's X Present path defaults to 60 submissions per second per window. Waiting occurs before the manager/content locks, with no spin and no accumulated catch-up burst. Synchronous CPU copies release their idle notification after the copy. Asynchronous GPU copies with a positive target keep the legacy delayed release; actual cadence can be lower than the selected limit. **Performance & temperature** also offers 30 fps and Unlimited. This applies to the Present path, not all possible graphics backends or measured display FPS.
2. **Sleep while graphics fences are pending.** X Sync waits use a monitor signaled by fence completion/destruction instead of 500 yields followed by repeated short polling. Stop cancels waiters before joining the connector thread; a generation guard keeps restarted sessions separate.
3. **Reduce compositor work.** Skip lower windows only when a later opaque quad covers the entire viewport; retain the scene and re-evaluate each pass. Transparent, partial, offscreen and transformed cases remain conservative. Opaque draws bypass alpha blending, blending is restored for overlays, and a hidden cursor skips unused setup.

The existing five-second support sampler includes bounded aggregate counts for all three paths. These establish whether the optimizations are active; they do not prove a particular FPS or temperature gain. The previous capture has no per-frame timing, CPU utilization or GPU temperature. See [evidence](evidence/2026-10-26-graphics-optimization.md) and [device checks](TESTING.md).

The accepted UI, separate 60 Hz display request, controller default, exact XInput reader DLL and cursor behavior are retained. Wine/Box64/graphics assets, game files, saves, input mappings and the preview signing identity are unchanged. Install over the existing app; no Prepare runtime or re-import is needed.

## Validation

[CI 40429777390](https://github.com/Russianranger/pd2-android/actions/runs/40429777390) passes **404 tests across 46 suites**, zero failures/ignored tests, ARM64 assembly, retained host/runtime checks and the actual packaged Wine recovery gate. The production Present handler harness passes ten scenarios/219 checks. All fourteen reader lifecycle scenarios pass; the real Wine probe reproduces the donor failure and passes repair recovery, held-state heartbeats, stale expiry and resume (1,269 checks). The same 404 Android tests also pass locally on byte-identical Java sources.

All nine native launcher previews match the local renders; the new settings dialogs were visually inspected. APK integrity, alignment, package/version/SDKs and unchanged V2 preview signer are independently verified. All **78 assets and 35 ARM64 libraries match 0.1.22 byte-for-byte**, including the accepted controller reader.

APK: **174,207,644 bytes**. SHA-256: `cdc2ae14e4ebfa217332765b56d16d0668c1198cef4a9b8034eda9faa4ad1be3`. Built from `22525db3a26543737cae33823c6882b456be62a9` (tree `5ea47a97e9b58676421ee0c9267a8d101a97797b`). Physical performance/temperature gains remain unmeasured.
