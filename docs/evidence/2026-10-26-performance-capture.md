# Accepted-controller performance capture and diagnostic overhead

Inspected `pd2-support-20261026-181042.zip` directly against source revision
`3f8683bbb5b3e25215850fb3f0891dc91dfcf064`. Archive SHA-256:
`ddf98eb7da2558db6a4c51890ac05768355a792f2f8cbfe529fd255620f81372`.
Only aggregate evidence is recorded here; device identifiers, device names,
process identifiers, network addresses and character information are omitted.

The user reports that the controller now works. Preserve that physical acceptance
and the accepted reader/runtime pins during a performance pass. This capture is
not a measured before/after performance or temperature comparison.

## Coverage and retained size

The current app is **0.1.21** on Android 13/ARM64. The launch begins at
**2026-10-26 23:07:59.130 UTC**; the controller report is captured at
**23:10:41.827 UTC**, **162.697 seconds** later. The first recorded game window
appears about 5.3 seconds after launch, and the game text log records startup
about 4.1 seconds after launch. These are startup observations, not a measured
time to a playable world. The launcher records a successful explicit Stop cleanup
at the end of the attempt; no low-memory kill is recorded for this attempt.

| Retained artifact | Bytes | Scope |
|---|---:|---|
| Compressed support archive | 422,677 | 24 entries, including previous attempts. |
| All uncompressed entries | 9,974,104 | Not the current session's live working memory or logging rate. |
| Previous-attempt artifacts | 7,684,404 | Four independent archived attempts; filenames indicate archive times, not necessarily their original launch times. |
| Current exported `runtime.log` | 2,097,152 | Bounded head/tail snapshot, with an explicit omitted-middle marker. |
| Original current runtime file | 3,052,196 | Source-file length recorded in the exported snapshot metadata. |
| Current `memory.json` | 108,347 | 24 retained samples. |
| Current `controller.json` | 20,446 | Counters and bounded snapshots, not a per-event state-value log. |

The export omits approximately 955 KB of the original runtime file, in addition
to adding its own metadata and omission marker. The retained 19,203 lines include
startup and shutdown, but cannot establish a complete event chronology. The
original runtime-file growth averages approximately **18.3 KiB/s** across the
launch-to-capture interval, including startup. That byte rate is not a CPU-time,
storage-latency or energy measurement.

## Routine Wine tracing dominates the retained log

The one-launch API relay trace is **off** in this capture. Nevertheless, the
launch environment still requests the previous routine diagnostic policy:

```text
WINEDEBUG=-all,err+all,warn+all,+seh,+loaddll,+xinput,+rawinput
```

The following counts and byte totals are measured only in the retained
head/tail snapshot. A matching line is counted once in its severity/channel
category, and its bytes include its line ending. The total rows include the
channel rows, so they must not be added together. Snapshot metadata, unmatched
lines and the omitted middle are outside these category totals.

| Retained severity/channel | Matching lines | Matching bytes |
|---|---:|---:|
| `trace:rawinput` | 8,884 | 1,067,454 |
| `trace:xinput` | 3,584 | 440,582 |
| `trace:seh` | 1,053 | 96,536 |
| `trace:loaddll` | 239 | 24,789 |
| **All `trace` channels** | **13,760** | **1,629,361** |
| **All `warn` channels** | **3,861** | **383,189** |
| **All `err` channels** | **1** | **98** |

XInput/RawInput trace alone contributes **12,468 lines and 1,508,036 bytes**,
approximately **71.9% of the entire exported runtime snapshot**. All trace
channels contribute approximately 77.7%. These percentages describe retained
bytes, not the full original log's distribution or the fraction of runtime CPU
spent logging. Some reader diagnostic strings also appear through Wine's
exception/debug-string paths; counting string occurrences as distinct reader
updates would double-count evidence.

The largest retained warning categories include 1,416 `esync:get_object`
messages, 1,046 font messages, 544 file messages and 352 logical-processor/cache
messages. Missing OpenGL extension warnings also occur. The one error-severity
line reports startup in experimental WoW64 mode. These messages do not by
themselves establish an ESYNC regression, a graphics bottleneck, a crash or a
reason to replace the working runtime. No fatal, out-of-memory or device-lost
message was found in the retained current runtime sections.

At the inspected source revision, `Pd2LaunchPolicy.WINE_DEBUG` requests those
channels on every ordinary launch. `LogView.append` sends file-backed output
through a `PrintStream`; `Pd2LogOutputStream.write` seeks and writes to a
`RandomAccessFile` under the shared log lock. The stream explicitly avoids
per-line `fsync`, and its live limit is 8 MiB with retained startup/current tail.
The current 3.05 MB original file is below that live rotation threshold. Removing
routine verbose channels therefore removes demonstrated message formatting,
pipe delivery and file writes without modifying controller transport, mappings
or DLLs.

The performance pass implements **`-all,err+all,warn+all` for normal launches**.
An explicitly armed, one-launch API trace adds
**`+seh,+loaddll,+xinput,+rawinput,+relay`**. The pinned Wine source's
[`parse_options`](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/ntdll/unix/debug.c#L141)
sets and clears severity/channel flags, and its debug writer emits accepted
messages directly to stderr. Filtering therefore removes the selected generated
trace messages. The pinned
[`OutputDebugStringA`](https://github.com/brunodev85/wine-9.2-custom/blob/a4ef2bf8963fe4bfab390eebf73336364ce209bc/dlls/kernelbase/debug.c#L182)
first emits a `WARN` on the `seh` channel, so keeping `warn+all` retains the
reader's bounded aggregate diagnostics while removing `trace:seh` detail. This
is a source-qualified output reduction; its FPS and temperature benefit still
needs device measurement.

## Memory and diagnostic sampling

The current memory report retains 24 samples from **23:08:44.520 to
23:10:40.147 UTC**. Nominal sampling is every 5 seconds; observed adjacent
sample intervals range from 5,024 to 5,035 ms, averaging 5,027 ms. These intervals
include scheduling and collection/persistence work and are not a profiler result.

- Device available memory ranges from **6,193,401,856 to 6,683,967,488 bytes**;
  `deviceLowMemory` remains false. No trim is recorded.
- App Java used heap ranges from **20,987,688 to 76,613,320 bytes**; committed
  Java heap is 80,715,648 bytes. App native allocation ranges from
  **16,418,304 to 20,343,280 bytes**. These are app heaps, not total guest/GPU use.
- Visible game-process RSS ranges from **445,160 to 511,776 KiB**, with 21
  threads in most samples and 22 in one sample. The game process is absent in
  the last two samples after game exit. Shared pages can be counted in several
  processes; RSS sums are not unique physical memory. The short changing-world
  sample does not establish a leak.
- Each retained process scan is complete for its visible scope, covering 10 or
  11 same-UID processes. Hidden processes and GPU memory remain outside scope.

`Pd2MemoryDiagnostics.checkpoint` scans process status, rebuilds a bounded report
and atomically persists it with `FileDescriptor.sync`; it then requests the
controller checkpoint, whose `save` also syncs a bounded report. Those operations
run on a background diagnostic worker at the fixed-delay interval. They are a
second source-visible recurring cost, but this capture does not measure their
CPU time or latency. Input events update counters rather than performing a file
sync for every event. The current report does not justify treating memory
pressure as the performance problem or deleting crash/support evidence.

## Preserve the accepted runtime; measure the remaining performance question

The launch retains Wine 9.2 custom, Box64 dynarec with the Stability preset,
Turnip/Zink, the Glide launch arguments and a 1280 x 720 X server. Requested
environment values include ESYNC, `mesa_glthread`, and a 512 MB Mesa shader-cache
limit. Their presence records requested policy, not an isolated benchmark of
each setting. The working `wine9-xinput-reader-1` image is recorded installed;
the separate HID-read experiment and API relay tracing are off. Preserve those
choices and exact module pins while changing diagnostic verbosity.

**No actual FPS, frame times, frame pacing, temperature, thermal status,
throttling state, battery power, CPU utilization, GPU utilization or CPU/GPU
clock samples are supplied.** Box64's 19.2 MHz counter / emulated 2.4 GHz and
the game's emulated Intel CPU/MHz text are guest timing/identity information,
not physical ARM-core frequencies. No conclusion about cooling, thermal
headroom or the best frame cap can be derived from them. The capture also does
not establish that rendering is uncapped: no current imported graphics-wrapper
cap configuration or frame-rate measurement is included.

Qualification needs the same device power/fan setting, brightness, scene and
session duration before and after the quiet policy, with externally observed
FPS/frame pacing and temperature. First confirm unchanged initial Native input
and Save/Quit/re-entry; do not trade the newly accepted controller behavior for
an unmeasured performance claim.
