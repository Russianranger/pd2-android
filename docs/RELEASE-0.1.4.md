# PD2 Android 0.1.4 Wine baseline comparison preview

The 0.1.3 GameNative-arguments test still failed: Fog export 10021 returned 23, then export 10019 did not return before the same native stack overflow. **A Wine 10 compatibility issue remains a hypothesis requiring device validation. This preview is not a confirmed fix or performance improvement.**

## Changed

- Replace Wine 10.10 with Wine 9.2 (Custom), including the matching prefix template/common DLLs from the official Winlator 10.1 APK.
- Retain Box64 0.4.4, current graphics components, and imported game files/saves. This does not recreate GameNative's Bionic Proton 9.0 runtime.
- Require one runtime preparation after upgrade. Rootfs version 24 and managed revision `wine-9.2-pd2-1` create/select a fresh prefix on first Play; previous prefixes are retained.
- Include Wine/rootfs/runtime-revision identity in support and launch records; retain existing Fog export tracing and bounded diagnostics.
- Match Wine 9.2's legacy controller protocol while retaining the modern bridge for other runtimes. The baseline exposes one gamepad, digital XInput triggers, and no rumble; physical controller behavior is still unqualified.

Version code is 5. Application/signing identity and SDK levels remain unchanged. Native controller, rendering/audio, saves, online play, and physical game startup remain unqualified.

## One test

1. Install over the existing app; **do not uninstall, clear storage, or re-import**.
2. Press **Prepare runtime once**, then select **Turnip + Zink · Glide (GameNative arguments)** and **CPU mode → Stability (default)**.
3. Press **Play once**, wait at most 60 seconds for the title/menu, then stop the client if needed.
4. **Export support logs** and upload the ZIP whether the attempt succeeds or fails. Report the last visible screen.

Local verification passed all 39 Android 13/API 33 tests, the import/crash/session-log/input-router checks, 24 runtime tests, and the ARM64 APK build. All 206 dependency hashes and 49 relocated assets verified. Published 0.1.4 CI and the physical launch comparison remain pending. APK SHA-256: `c1eeb0e0968cfa852f736b62ed4d045d57abeaa336f0973ae768656fdd61e413`. See [Testing](TESTING.md) for the full sequence and [Handoff](HANDOFF.md) for the evidence boundary.
