# Building the preview

Use JDK 17, Android SDK platform 35, build tools 35.0.0, NDK 24.0.8215888,
and CMake 3.22.1. Set `ANDROID_HOME` or put `sdk.dir` in `local.properties`.
The Gradle wrapper pins Gradle 8.14.5 with its published SHA-256. Android Gradle
Plugin 8.4.2 matches the imported Winlator build. Binary runtime assets are fetched at build time from pinned sources recorded in `RUNTIME-DEPENDENCIES.json`, with SHA-256 verification before placement. The installed APK contains its runtime and requires no build-time
download step on the device. No proprietary Diablo II or Project Diablo 2 installation is included.
Python 3.11 or newer with zstandard 0.25.0 is required by both runtime composition
and relocation. `fetch-runtime.py` verifies the official Winlator 10.1 APK,
replaces the complete `opt/wine` tree with Wine 9.2 (Custom), and installs that
APK's matching prefix template/common-DLL list. Other rootfs members, graphics,
and Box64 0.4.4 retain their existing source. Relocation then applies the
equal-length package-path substitution; it is idempotent, and `--check`
validates the result before compiling.
Use a Linux host with GNU binutils (`readelf`/`nm`) for the controller backend
artifact checks. Rebuilding that backend also requires host x86-64 GCC and the
exact packaged Wine 9 `ntdll.so`; this is separate from the Android NDK build.

```sh
python3 -m pip install zstandard==0.25.0
python3 -m unittest discover -s tests -p 'test_runtime_fetch.py' -v
python3 -m unittest discover -s tests -p 'test_runtime_baseline.py' -v
python3 scripts/fetch-runtime.py
python3 scripts/relocate-runtime.py
python3 scripts/relocate-runtime.py --check
python3 scripts/fetch-runtime.py --check-final
python3 scripts/build-controller-runtime.py --check
python3 -m unittest discover -s tests -p 'test_controller_runtime.py' -v
python3 -m unittest discover -s tests -p 'test_runtime_relocation.py' -v
./scripts/test-import.sh
python3 tests/test_input_router.py
python3 tests/test_native_controller_input.py
python3 tests/test_captured_pointer.py
python3 tests/test_crash_recovery.py
python3 tests/test_session_logs.py
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;24.0.8215888' 'cmake;3.22.1'
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
./scripts/package-preview.sh
```

The first source build downloads pinned upstream assets and the roughly 148 MB
[official Winlator 10.1 APK](https://github.com/brunodev85/winlator/releases/tag/v10.1.0)
when Wine baseline assets need composition. APK, selected members, original
rootfs, composed output, and relocated output have recorded hashes.
`scripts/fetch-runtime.py` reuses verified original or relocated files, stages
downloads, and validates their SHA-256 and size before placement. Recognized
superseded donor bytes are upgraded; unexpected local modifications cause an
error. Up to four ordinary asset downloads run concurrently, with bounded retries.

For offline builds, retain the verified final assets, or provide both a complete
original upstream tree with `--cache-dir /path/to/tree` and the exact donor APK
with `--wine-apk /path/to/Winlator_10.1.apk`. The cached original rootfs must match
the original donor hash, not an already composed rootfs. The APK still undergoes
pin verification. `--check` verifies existing dependencies without downloads;
`--check-final` verifies the exact post-relocation hashes used by the APK.

The current output is `app/build/distributions/PD2-Android-0.1.13-preview.apk` and a
`SHA256SUMS` file. This describes a repeatable toolchain and stable signing
identity, not a claim that two independent builds are byte-for-byte identical.

## Targeted Wine 9 controller backend

`runtime/controller/` contains the producer and pinned Wine 9 headers/glue,
including its LGPL notice and source inventory. The normal APK build packages
`app/src/main/assets/pd2/controller/winebus.so` and its manifest. The build check
verifies vendored and producer/build source hashes, manifest identity, artifact
hash/size, ELF dependencies, and the 17-entry Wine 9 Unix ABI. It does not run
PD2 or prove device compatibility.

To rebuild the artifact, extract the exact `x86_64-unix/ntdll.so` from the
prepared Wine 9 rootfs and run:

```sh
python3 scripts/build-controller-runtime.py --ntdll /path/to/opt/wine/lib/wine/x86_64-unix/ntdll.so
python3 scripts/build-controller-runtime.py --check
```

The module links only to `ntdll.so` and libc and is installed before Play with
verified original-backend backup/replacement. Wine version, rootfs archives,
prefix, and imported PD2 files are retained. The backend uses the Wine 9 Unix
ABI through matching headers/glue.

## Preview identity

`signing/pd2-preview.jks` is a deliberately public testing key with password
`android`, alias `pd2-preview`, and key password `android`. It gives local and
CI previews the same signing identity so they can update each other without
uninstalling or losing imported files. It provides no production security.
Use the supplied preview APK or this repository's verified release/Actions links
when published.
A production signing key must be kept private and will require a separately
planned migration or application ID before production distribution.

The Java/JNI namespace stays `com.winlator`, while the Android application ID is
`com.pd2.thor`. The package name has the same byte length as `com.winlator`, allowing the
embedded runtime to be relocated without changing ELF string offsets. It can
install alongside Winlator. The preview intentionally retains target SDK 28 because the embedded runtime executes
native Wine/Box64 child processes from its app data directory. Raising this
requires a different executable packaging strategy. Compile SDK is 35.

GitHub Actions runs tests and builds an ARM64 preview on main pushes, pull
requests, and manual dispatches. Pushing an immutable `v*` tag also creates a
GitHub prerelease with the APK and checksum. If this preview is published through that path, use an immutable version tag; no 0.1.13 tag was requested or published.

## Startup verification boundary

The 0.1.1 JVM crash-recovery check exercises the production Java report storage
and uncaught-handler delegation using small Android metadata stand-ins. It does
not demonstrate that Android delivers the same exception on the Thor or that the
recovery sharing UI works there. APK assembly, manifest inspection, and modeled
Android UI tests are separate checks; none identifies the original 0.1.0 device
crash without a device report or successful retest.

The Gradle unit-test task includes the launcher, container, and controller-bridge
tests. The Android framework tests use Robolectric 4.14.1 with Android 13/API 33.
All six launcher tests passed for 0.1.1, covering the actual manifest/themed launcher,
both platform entry routes, recovery fallback and retry report retention, and
foreground notification before protected jobs. An isolated original `ebcd311`
baseline opened its UI but failed the regression assertion because startup
requested `Pd2WorkService`. This verifies the changed startup behavior; it does
not reproduce the original device crash. Robolectric runs in the JVM and does
not reproduce Android's service watchdog, ARM64 native-library loading, Wine,
graphics drivers, or the physical Thor. The ARM64 0.1.1 APK build also passed,
with version code 2 and the same application ID and preview signing key.

For 0.1.2, all 13 API 33 tests passed locally, including the previous six and
seven new diagnostic/policy/PE/status/log-cap checks. The 26 session-log host
checks and ARM64 APK build also passed; version code is 3 with the same package,
signing certificate, minimum SDK 26, and target SDK 28.

For 0.1.3, all 20 API 33 tests, the import/crash/session-log/input-router checks,
and the ARM64 APK build passed locally. Its [CI run 37151153178](https://github.com/Russianranger/pd2-android/actions/runs/37151153178)
also succeeded for commit `3fc973`.

For 0.1.4, all 39 API 33 Robolectric tests, the 47 import/11 crash/26 session-log
checks, input-router checks, and 24 runtime tests (13 fetch, six composition,
five relocation) passed. All 206 final dependency hashes and 49 relocated assets
verified. The ARM64 APK build passed in 28 seconds as version code 5, retaining
the same identity/certificate and SDK levels. Its [CI run 37153029246](https://github.com/Russianranger/pd2-android/actions/runs/37153029246)
also succeeded for commit `3537161`.
The baseline fixtures verify whole-Wine-tree replacement, preservation of other
members, pin rejection, and failed-conversion cleanup. They do not run Wine or PD2.

For 0.1.5, all 49 API 33 Robolectric tests, the 47 import/11 crash/26 session-log
checks, input-router checks, and the ARM64 APK build passed. The build took
16 seconds as version code 6; all 35 packaged native libraries and 25 runtime
archives match 0.1.4. Identity/certificate and SDK levels are unchanged.
Its [CI run 37162841712](https://github.com/Russianranger/pd2-android/actions/runs/37162841712)
also succeeded for commit `f9f0618`. Title startup was accepted on 0.1.4, while
the 0.1.5 device bundle still lacked PD2 controller activation.

For 0.1.6, all 76 API 33 Robolectric tests, the 47 import/11 crash/26 session-log
checks, input-router checks, and three native controller tests passed. Native
source/artifact/ABI checks passed for the 31,064-byte `wine9-hid-1` module,
linking only `ntdll.so`/libc with maximum GLIBC 2.17. Native tests exercise the
production UDP/HID code through a host harness; complete Wine/HID/PD2 integration
was not validated. Final ARM64 assembly/signature/version checks passed. All 35
Android native libraries and 70 existing assets match 0.1.5; only the controller
backend/manifest are added, with the final module verified inside the APK.
Version 0.1.6/code 7 retains the same package/certificate, min SDK 26, and target
SDK 28. [Published 0.1.6 CI run 37167615176](https://github.com/Russianranger/pd2-android/actions/runs/37167615176) succeeded. The user subsequently accepted native input inside a character, with a menu failure after Save/Exit.

For 0.1.7, all 78 Robolectric tests across 13 suites passed with zero failures,
errors, or skips. Eight real-Java-router host scenarios and 749 logging assertions
passed. ARM64 assembly and identity/signature checks passed as `com.pd2.thor`,
0.1.7/code 8, min SDK 26, target SDK 28, with the same preview certificate.
All 35 Android native libraries and 70 baseline assets match the verified 0.1.5
APK byte-for-byte; both custom controller assets match tracked 0.1.6 source.
The native module is unchanged. APK size is 171,296,970 bytes; SHA-256:
`d36d82927bcc7ee745fe7a5ae704545c8cc27d256b15c5a928000fd409c30d1a`.
Published 0.1.7 CI run 37169294205 passed. The user rejected the physical menu test; re-entry remains unqualified.

Install 0.1.13 over the existing accepted preview with the same signing key/application ID to retain
imported files. The targeted controller update reuses rootfs version 24 and the current
`wine-9.2-pd2-1` managed prefix; **no Prepare runtime or re-import is needed**.
The backend installs automatically on Play. To restore the original, select
**Launch settings → Controller → Controller notifications disabled**, force-stop
the app, reopen, and Play.
A first installation or an upgrade from the older Wine 10 baseline still needs
runtime preparation. The menu-transition test and remaining qualification sequence
are in [Testing](TESTING.md).

Preview certificate SHA-256: `A6:12:97:BE:F1:BE:26:52:B5:3B:76:37:30:27:5E:1F:0E:09:75:22:22:94:60:2E:D8:FF:A5:F8:14:7D:DB:1E`.

## 0.1.8 menu-pointer verification

Local 0.1.8 verification passed: 86 Android 13/API 33 Robolectric tests across 13 suites, with no failures, errors or skips; 9 real-router scenarios; and 11 menu-pointer scenarios with 145 checks. These include the actual mouse/keyboard datagram layout, queued-action expiry, fresh Windows cursor synchronization, lost-feedback retry, resize/clipping, and balanced controls. ARM64 assembly and APK identity/signature checks passed: `com.pd2.thor`, 0.1.8/code 9, min SDK 26, target SDK 28, unchanged preview certificate. All 35 Android native libraries and 72 runtime assets match the verified 0.1.7 APK byte-for-byte, including the accepted HID module. APK size: 171,307,574 bytes. SHA-256: `53cec30a4ee97ef6bb30453ee694c618631572eb853c86dfcc2944e2e4d4c550`. Packaging drift rejection and correct-version artifact fixtures passed. [Published 0.1.8 CI run 37172243169](https://github.com/Russianranger/pd2-android/actions/runs/37172243169) passed. The user accepted Menu cursor but reported Native failure after Save/Exit; Native after character re-entry remains unqualified. The accepted Wine/HID backend is unchanged. Install over the existing app without runtime preparation or re-import; see [Testing](TESTING.md).

The packaging script rejects metadata that differs from the configured version before copying the APK. If an incremental build reports this mismatch, remove `app/build/outputs/apk/debug/output-metadata.json` and rerun `:app:packageDebug :app:assembleDebug`, then package again. APK manifest metadata was independently checked with `aapt` for this preview.

## 0.1.9 native recovery and visual changes

0.1.9/code 10 adds bounded Game.exe foreground recovery when Native resumes and during game-window transitions, with stale queued work cancelled on route loss. It retains the accepted runtime/HID backend and imported game files. A generated fiery PD2/skull icon is packaged in `drawable-nodpi/pd2_icon_art.png`, referenced through `pd2_icon.xml` and the adaptive launcher icon. The session theme and gear menu/dialogs use dark surfaces while retaining fullscreen settings.

All 93 Android 13/API 33 Robolectric tests across 14 suites passed with zero failures, errors, or skips. Nine real-router scenarios and 12 menu-pointer/native-focus scenarios with 156 checks passed, alongside import/crash/session-log checks, 24 runtime tests, three native-controller tests, and pinned dependency/relocation/source/artifact/ABI checks.

ARM64 assembly completed in 1 minute 35 seconds. The final APK is `com.pd2.thor`, 0.1.9/code 10, min SDK 26, target SDK 28; V2 signature verification confirms the same preview certificate as 0.1.8. ZIP integrity and alignment passed. All 72 packaged runtime assets match 0.1.8 byte-for-byte, including Wine/HID assets.

Of the 35 Android native libraries, 26 match 0.1.8 byte-for-byte and eight rebuilt libraries differ only in their GNU build IDs. VirGL's rebuilt string pool and address references reflect the changed scratch build path: all normalized strings and referenced targets match, without opcode/register/control-flow changes. Native source files are unchanged.

APK size: 174,020,117 bytes. SHA-256: `e6b1a0215b3502b3fe6fc57d915bdeb1d66b136db89720cde65162c9db5d9e16`. [Published 0.1.9 CI run 37191608087](https://github.com/Russianranger/pd2-android/actions/runs/37191608087) passed. The subsequent device check failed Native input after Save/Exit and character re-entry; Menu cursor remained usable. Automated checks do not qualify physical recovery. Follow [Testing](TESTING.md) without runtime preparation or re-import.

## 0.1.10 Native reconnect and white cursor control

0.1.10/code 11 adds Java-side HID soft reconnect on every explicit Native selection, including reselecting Native as a retry. The existing absent/present protocol holds an absence interval of at least 600 ms, with legacy XInput connected and neutral. The request begins once Native regains its active input/focus gate; modal/lifecycle changes alone do not request reconnects. Foreground recovery is retained.

The saved **Hide white cursor** gear toggle defaults Off. It changes app root-cursor rendering while preserving the game's cursor and input. No Wine/controller/game DLL or runtime/prefix migration is part of this preview.

All 105 unique automated tests across 15 suites passed with zero failures, errors, or skips: 100 Android 13/API 33 Robolectric tests and five plain cursor-rendering tests. The full run passed 104 tests; a focused 20-test legacy-controller rerun passed with the final added neutral-attach failure test. Nine real-router scenarios, 12 menu-pointer/native-focus scenarios with 156 checks, three native-controller host tests including production UDP/replug under ASan/UBSan, and all 206 pinned dependency checks passed.

Final ARM64 packaging and APK identity/signature/payload checks passed: `com.pd2.thor`, 0.1.10/code 11, min SDK 26, target SDK 28, ARM64 only, retaining the preview certificate. All 72 runtime assets and 35 Android native libraries match the verified 0.1.9 APK byte-for-byte.

Local APK size: 174,025,221 bytes. SHA-256: `e2f8ea363b5f2d6c3b332998bad91b612bdd348d12e1c6f920b6af1186ce5440`. [Published 0.1.10 CI run 37196226447](https://github.com/Russianranger/pd2-android/actions/runs/37196226447) passed. The subsequent physical report still rejects Native on Save/Quit. See [Testing](TESTING.md) and [Handoff](HANDOFF.md); update over the existing app without Prepare runtime or re-import.

## 0.1.11 controller and cursor verification

0.1.11/code 12 corrects independent trigger handling and immediate Native thumb-button edges, guards captured pointer forwarding, balances held mouse buttons, expands cursor-overlay hiding, and tests synchronized Java-side HID + legacy XInput reconnect. The packaged Wine/HID assets, runtime/prefix/import, and signing identity remain the accepted baseline. No physical Save/Quit recovery or shoulder-tab fix is claimed.

Local verification passed **122 tests across 16 suites**, with no failures, errors or skips; all host input/import/recovery/log and runtime checks passed, including the Wine backend sanitizer checks and all 206 final runtime pins. Final ARM64 assembly, package identity, matching preview certificate/V2 signature, ZIP integrity and alignment passed. All 72 runtime assets match the verified 0.1.10 CI APK byte-for-byte. Of 35 Android native libraries, 26 match exactly, eight differ only in build IDs, and VirGL rebuild differences were statically traced to source-path strings and address/relocation adjustments; this is not physical graphics qualification. No Wine/HID binary changes are included.

APK: `PD2-Android-0.1.11-preview.apk`, **174,055,860 bytes**. SHA-256: `0a307e4cde8a88312ec82f4413e76f71cf1f7d935a1597e86557081460e7a89c`. Identity: `com.pd2.thor`, 0.1.11/code 12, min SDK 26, target SDK 28, ARM64 only. [Published 0.1.11 CI run 37206816716](https://github.com/Russianranger/pd2-android/actions/runs/37206816716) passed for code commit `2f5e1a9086c3131a9de265813c636f4c57eda6c0`; physical Save/Quit and shoulder-tab qualification remain pending. See [Testing](TESTING.md) and the [research evidence](evidence/2026-10-04-controller-research.md) for the separate physical qualification.
