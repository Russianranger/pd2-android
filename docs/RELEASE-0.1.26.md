# PD2 Android 0.1.26 preview

The 60 FPS cap helps temperature and the Classic cursor fallback fixes the black square, according to device feedback. FPS is still poor. The new capture reveals an active FSR EASU scaling shader even though optional bloom, FXAA and sharpening are already off.

This build defaults to **Simple image scaling**, which requests D2GL's built-in `bilinear.slangp`. It uses a simpler scaling shader and may look softer; the game resolution stays unchanged. **Graphics & audio → Image scaling → Restore game shader** restores the prior launcher-owned preset. Explicit choices persist. Changes apply after Stop client and Play, through the existing verified-cleanup, atomic backup/journal transaction. Missing, malformed or unsafe preset settings are kept and reported. Support exports distinguish requested scaling from configured scaling.

The accepted controller repair, cursor movement, Classic cursor selection, 60 FPS cap, 60 Hz display request, Eco audio, runtime, saves and gothic UI remain intact. No Wine/Box64/graphics driver or game DLL is replaced.

See [capture evidence](evidence/2026-10-28-fps-scaling-followup.md) and the [device comparison](TESTING.md). CPU and image-transfer measurements favor targeting guest rendering work, but do not prove FSR is the bottleneck. Physical FPS improvement remains unverified.

[Full CI](https://github.com/Russianranger/pd2-android/actions/runs/40785221636) passes **493 Android tests / 52 suites**, ARM64 assembly, runtime pins and the packaged controller recovery gate. Twenty-one native previews match the inspected layouts. Independent APK checks verify the unchanged signature, 81 assets and 35 native libraries. These checks do not establish device FPS gains.

APK: `PD2-Android-0.1.26-preview.apk`, version code 27, **174,262,628 bytes**. SHA-256: `9c47de427999fca5bd5178c91c1b9b8761f7598e9ce59f00f9003d27a446ea90`.

Tested source: `7a50c466a19ca5f307da51dd526224f0464c81a7`.
