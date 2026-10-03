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

```sh
python3 -m pip install zstandard==0.25.0
python3 -m unittest discover -s tests -p 'test_runtime_fetch.py' -v
python3 -m unittest discover -s tests -p 'test_runtime_baseline.py' -v
python3 scripts/fetch-runtime.py
python3 scripts/relocate-runtime.py
python3 scripts/relocate-runtime.py --check
python3 scripts/fetch-runtime.py --check-final
python3 -m unittest discover -s tests -p 'test_runtime_relocation.py' -v
./scripts/test-import.sh
python3 tests/test_input_router.py
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

The output is `app/build/distributions/PD2-Android-0.1.4-preview.apk` and a
`SHA256SUMS` file. This describes a repeatable toolchain and stable signing
identity, not a claim that two independent builds are byte-for-byte identical.

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
GitHub prerelease with the APK and checksum; the runtime comparison preview tag is `v0.1.4`.

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
the same identity/certificate and SDK levels. Published 0.1.4 CI remains pending.
The baseline fixtures verify whole-Wine-tree replacement, preservation of other
members, pin rejection, and failed-conversion cleanup. They do not run Wine or PD2.

Install 0.1.4 as an update using the same signing key/application ID to retain
imported files. Press **Prepare runtime once** for rootfs version 24. First Play
creates a fresh `wine-9.2-pd2-1` managed prefix; previous prefixes and the private
imported game/save tree remain in place. Do not re-import. The physical runtime
comparison and remaining qualification sequence are in [Testing](TESTING.md).

Preview certificate SHA-256: `A6:12:97:BE:F1:BE:26:52:B5:3B:76:37:30:27:5E:1F:0E:09:75:22:22:94:60:2E:D8:FF:A5:F8:14:7D:DB:1E`.
