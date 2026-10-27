# PD2 Android 0.1.23 Preview

Three graphics optimizations follow the user's report that 0.1.22's display/log changes made little difference to performance and temperature:

1. **Pace frame submissions.** PD2's X Present path defaults to 60 submissions per second per window. Waiting occurs before the manager/content locks, with no spin and no accumulated catch-up burst. Synchronous CPU copies release their idle notification after the copy. Asynchronous GPU copies with a positive target keep the legacy delayed release; actual cadence can be lower than the selected limit. **Performance & temperature** also offers 30 fps and Unlimited. This applies to the Present path, not all possible graphics backends or measured display FPS.
2. **Sleep while graphics fences are pending.** X Sync waits use a monitor signaled by fence completion/destruction instead of 500 yields followed by repeated short polling. Stop cancels waiters before joining the connector thread; a generation guard keeps restarted sessions separate.
3. **Reduce compositor work.** Skip lower windows only when a later opaque quad covers the entire viewport; retain the scene and re-evaluate each pass. Transparent, partial, offscreen and transformed cases remain conservative. Opaque draws bypass alpha blending, blending is restored for overlays, and a hidden cursor skips unused setup.

The existing five-second support sampler includes bounded aggregate counts for all three paths. These establish whether the optimizations are active; they do not prove a particular FPS or temperature gain. The previous capture has no per-frame timing, CPU utilization or GPU temperature. See [evidence](evidence/2026-10-26-graphics-optimization.md) and [device checks](TESTING.md).

The accepted UI, separate 60 Hz display request, controller default, exact XInput reader DLL and cursor behavior are retained. Wine/Box64/graphics assets, game files, saves, input mappings and the preview signing identity are unchanged. Install over the existing app; no Prepare runtime or re-import is needed.

Validation and the qualified APK digest will be recorded after the complete Android/Wine build.
