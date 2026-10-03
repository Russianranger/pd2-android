# Third-party notices and runtime provenance

PD2 Android is an independent launcher. Diablo II, Lord of Destruction, and
Project Diablo 2 game files are not distributed here. Users import their own
working installation. The launcher is not endorsed by Blizzard or the PD2 team.

## Winlator source baseline

The Android Java/C/C++ code and bundled runtime assets were imported from
[brunodev85/winlator-app](https://github.com/brunodev85/winlator-app), commit
`a030f552f452158a2db64fdb32b490fa19c0b48d` (Winlator 11.2). Its preceding main
commit is `b6b2259158cf38d06067c34430d840d56b46d220`.
The upstream GNU Lesser General Public License 2.1 is retained in [LICENSE](LICENSE).
This repository publishes the imported application source and launcher changes.
Original source copyright and license headers remain in place.

The repository records 205 inherited binary dependencies in
`docs/RUNTIME-DEPENDENCIES.json` rather than storing their large binary payloads.
`scripts/fetch-runtime.py` fetches only those exact files from the immutable
upstream commit and verifies the recorded original SHA-256 and size. Their
post-relocation SHA-256 values are also recorded; the APK still bundles the
runtime and does not delegate launching to another Android app.

Native Java packages remain `com.winlator` because the JNI symbols use that
package. The installation identity is separate: `com.pd2.thor`.

## Embedded components

This table records upstream component source locations and the locations used
by this preview. It is an inventory, not a claim that every inherited prebuilt
binary has been independently rebuilt or that its exact corresponding source
revision was established.

| Component | Location in this repository | Upstream source / licensing information |
|---|---|---|
| Wine and Linux/glibc runtime | `app/src/main/assets/rootfs.tzst`, `rootfs_patches.tzst`, `container_pattern.tzst` | [Wine](https://gitlab.winehq.org/wine/wine), [Winlator runtime](https://github.com/brunodev85/winlator), [Termux Pacman glibc packages](https://github.com/termux-pacman/glibc-packages) |
| Box64 0.4.4 | `app/src/main/assets/box64/` | [Box64](https://github.com/ptitSeb/box64) |
| Mesa Turnip, Zink, VirGL | `app/src/main/assets/graphics_driver/`, `app/src/main/cpp/virglrenderer/` | [Mesa](https://gitlab.freedesktop.org/mesa/mesa), [VirGL renderer](https://gitlab.freedesktop.org/virgl/virglrenderer) |
| Winlator Vortek and Gladio renderers | `app/src/main/cpp/vortekrenderer/`, `gladiorenderer/`, matching driver assets | [Winlator source](https://github.com/brunodev85/winlator-app) |
| DXVK and D8VK | `app/src/main/assets/dxwrapper/` | [DXVK](https://github.com/doitsujin/dxvk), [D8VK](https://github.com/AlpyneDreams/d8vk) |
| VKD3D | `app/src/main/assets/dxwrapper/vkd3d-2.14.1.tzst` | [VKD3D](https://gitlab.winehq.org/wine/vkd3d) |
| D7VK | `app/src/main/assets/dxwrapper/d7vk-1.11.tzst` | [D7VK](https://github.com/WinterSnowfall/d7vk) |
| CNC DDraw 6.6 | `app/src/main/assets/dxwrapper/cnc-ddraw-6.6/` | [CNC DDraw](https://github.com/FunkyFr3sh/cnc-ddraw) |
| libadrenotools and linkernsbypass | `app/src/main/cpp/libadrenotools/` | [libadrenotools](https://github.com/bylaws/libadrenotools), BSD 2-Clause; included `LICENSE` files |
| FluidSynth | `app/src/main/cpp/midihandler/fluidsynth/`, `app/src/main/jniLibs/arm64-v8a/libfluidsynth*.so` | [FluidSynth](https://github.com/FluidSynth/fluidsynth), included LGPL 2.1 `LICENSE` |
| PulseAudio | `app/src/main/assets/pulseaudio.tzst`, `app/src/main/jniLibs/arm64-v8a/libpulse*.so` | [PulseAudio](https://gitlab.freedesktop.org/pulseaudio/pulseaudio) |
| GLib, PCRE, libltdl, libinstpatch, libsndfile | `app/src/main/jniLibs/arm64-v8a/` | [GLib](https://gitlab.gnome.org/GNOME/glib), [PCRE](https://www.pcre.org/), [libtool](https://www.gnu.org/software/libtool/), [libinstpatch](https://github.com/swami/libinstpatch), [libsndfile](https://github.com/libsndfile/libsndfile) |
| FLAC, Ogg, Vorbis, Opus | `app/src/main/jniLibs/arm64-v8a/` | [Xiph](https://gitlab.xiph.org/), [Opus](https://github.com/xiph/opus) |
| Oboe and C++ shared runtime | `app/src/main/jniLibs/arm64-v8a/` | [Oboe](https://github.com/google/oboe), [Android NDK](https://android.googlesource.com/platform/ndk/) |
| SONiVOX soundfont | `app/src/main/assets/soundfont/` | Inherited from the Winlator asset baseline; provenance requires further verification before a production redistribution |
| Windows components | `app/src/main/assets/wincomponents/` | Inherited archives include DirectX/media components and `vcrun2005`/`vcrun2010` DLLs; exact licenses and redistribution conditions require review rather than assuming the app LGPL covers these binaries |
| AndroidX and Material libraries | Gradle dependencies in `app/build.gradle` | [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Material Components](https://github.com/material-components/material-components-android) |
| Python zstandard (build-time relocation) | `scripts/relocate-runtime.py` | [python-zstandard](https://github.com/indygreg/python-zstandard) |
| zstd-jni, XZ, Commons Compress | Gradle dependencies in `app/build.gradle` | [zstd-jni](https://github.com/luben/zstd-jni), [XZ Java](https://tukaani.org/xz/java.html), [Commons Compress](https://commons.apache.org/proper/commons-compress/) |

A complete production redistribution review must map each prebuilt archive to
its exact source revision, build configuration, license texts, and any required
corresponding source or offer, and replace/remove components whose redistribution
permission is not established. The preview retains the upstream baseline for
initial device qualification; this document does not label that audit complete.

`docs/UPSTREAM-RUNTIME-ASSET-SHA256.txt` records the inherited asset and prebuilt
library hashes. `docs/RUNTIME-ASSET-SHA256.txt` records the relocated runtime
shipped here. Runtime archives receive a deterministic equal-length replacement
of the original package path `com.winlator` with `com.pd2.thor` in tar member
bytes and symlink targets, preserving ELF string offsets. Java/JNI namespace
symbols remain unchanged. Archive/license headers bundled by upstream remain
untouched. [BUILD.md](docs/BUILD.md) documents build and preview signing details.
