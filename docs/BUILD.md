# Building the preview

Use JDK 17, Android SDK platform 35, build tools 35.0.0, NDK 24.0.8215888,
and CMake 3.22.1. Set `ANDROID_HOME` or put `sdk.dir` in `local.properties`.
The Gradle wrapper pins Gradle 8.14.5 with its published SHA-256. Android Gradle
Plugin 8.4.2 matches the imported Winlator build. Binary runtime assets are fetched at build time from the immutable upstream
commit recorded in `RUNTIME-DEPENDENCIES.json`, with SHA-256 verification before
placement. The installed APK contains its runtime and requires no build-time
download step on the device. No proprietary Diablo II or Project Diablo 2 installation is included.
Python 3.11 or newer with zstandard 0.25.0 is required for the deterministic runtime relocation
script. It applies only the equal-length package-path substitution and is
idempotent; `--check` validates the result before compiling.

```sh
python3 -m pip install zstandard==0.25.0
python3 -m unittest discover -s tests -p 'test_runtime_fetch.py' -v
python3 scripts/fetch-runtime.py
python3 scripts/relocate-runtime.py
python3 scripts/relocate-runtime.py --check
python3 scripts/fetch-runtime.py --check-final
python3 -m unittest discover -s tests -p 'test_runtime_relocation.py' -v
./scripts/test-import.sh
python3 tests/test_input_router.py
python3 tests/test_crash_recovery.py
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;24.0.8215888' 'cmake;3.22.1'
./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.winlator.pd2.Pd2*Test'
./gradlew --no-daemon :app:assembleDebug
./scripts/package-preview.sh
```

The first source build downloads approximately 161 MB of pinned upstream binary
assets. `scripts/fetch-runtime.py` reuses already verified original or relocated
files, streams downloads to temporary files, and places them only after checking
their original SHA-256 and size. It preserves unexpected local modifications by
failing rather than overwriting them. Up to four downloads run concurrently, with
bounded retries. For offline builds, a complete original upstream file tree can
be supplied with `--cache-dir /path/to/tree`, or the already verified assets can
be retained locally. `--check` verifies existing dependencies without downloads;
`--check-final` verifies the exact post-relocation hashes used by the APK.

The output is `app/build/distributions/PD2-Android-0.1.1-preview.apk` and a
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
GitHub prerelease with the APK and checksum; the startup diagnostics preview tag is `v0.1.1`.

## Startup verification boundary

The 0.1.1 JVM crash-recovery check exercises the production Java report storage
and uncaught-handler delegation using small Android metadata stand-ins. It does
not demonstrate that Android delivers the same exception on the Thor or that the
recovery sharing UI works there. APK assembly, manifest inspection, and modeled
Android UI tests are separate checks; none identifies the original 0.1.0 device
crash without a device report or successful retest.

The Gradle unit-test task runs `Pd2ActivityStartupTest` and
`Pd2ServiceAndRecoveryTest` through Robolectric 4.14.1 with Android 13/API 33.
All six tests passed for 0.1.1, covering the actual manifest/themed launcher,
both platform entry routes, recovery fallback and retry report retention, and
foreground notification before protected jobs. An isolated original `ebcd311`
baseline opened its UI but failed the regression assertion because startup
requested `Pd2WorkService`. This verifies the changed startup behavior; it does
not reproduce the original device crash. Robolectric runs in the JVM and does
not reproduce Android's service watchdog, ARM64 native-library loading, Wine,
graphics drivers, or the physical Thor. The ARM64 0.1.1 APK build also passed,
with version code 2 and the same application ID and preview signing key.

Install the preview as an update using the same signing key and application ID
to retain imported files. The immediate device check is two launcher opens of
five seconds each, before runtime preparation. The full sequence is in
[Testing](TESTING.md).

Preview certificate SHA-256: `A6:12:97:BE:F1:BE:26:52:B5:3B:76:37:30:27:5E:1F:0E:09:75:22:22:94:60:2E:D8:FF:A5:F8:14:7D:DB:1E`.
